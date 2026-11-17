/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ql;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.modpack.ModpackInstallTask;
import org.jackhuang.hmcl.task.AsyncTaskExecutor;
import org.jackhuang.hmcl.util.CacheRepository;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Tests for [QLSeedImporter] installing a synthetic seed pack the same way HMCL does,
/// then reconciling it against a manifest and generating the dblpack inventory.
@NotNullByDefault
public final class QLSeedImporterTest {

    private @TempDir Path gameDir;
    private @TempDir Path cacheDir;
    private @TempDir Path zipDir;
    private HttpServer server;
    private final Map<String, byte[]> payloads = new HashMap<>();

    @BeforeEach
    public void setup() throws IOException {
        // The downloader consults the singleton CacheRepository whose index
        // is only initialized by changeDirectory().
        CacheRepository.getInstance().changeDirectory(cacheDir);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = payloads.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    public void tearDown() {
        server.stop(0);
        payloads.clear();
    }

    /// Unpacks a synthetic mrpack with HMCL's native install task and seeds the instance.
    @Test
    public void seedsMatchingFilesSkipsDisabledOptionalAndWritesDblpack() throws Exception {
        byte[] basic = utf8("basic-mod");
        byte[] entityCulling = utf8("dh-mod");
        byte[] config = utf8("config-toml");
        byte[] resourcePack = utf8("faithful-pack");

        Path zip = buildSeedZip(Map.of(
                "mods/basic.jar", basic,
                "mods/entity-culling.jar", entityCulling,
                "config/cfg.toml", config,
                "resourcepacks/Faithful-32x_v1.2.1.zip", resourcePack));

        String payloadUrl = payloadOf("/basic.jar", basic);

        JsonObject manifest = manifest();
        JsonArray mods = new JsonArray();
        mods.add(fileEntry("mods/basic.jar", basic, payloadUrl));
        JsonObject optionalEntry = fileEntry("mods/entity-culling.jar", entityCulling, payloadOf("/dh.jar", entityCulling));
        optionalEntry.addProperty("optional", "dh");
        mods.add(optionalEntry);
        manifest.getAsJsonObject("channels").getAsJsonObject("std").add("mods", mods);

        JsonArray common = new JsonArray();
        common.add(fileEntry("config/cfg.toml", config, payloadOf("/cfg.toml", config)));
        common.add(fileEntry("resourcepacks/Faithful-32x_v1.2.1.zip", resourcePack, payloadOf("/rp.zip", resourcePack)));
        manifest.getAsJsonObject("channels").getAsJsonObject("common").add("files", common);

        unpack(zip);

        QLManifest parsed = QLManifest.fromJson(manifest.toString());
        QLSeedImporter.SeedOutcome outcome = QLSeedImporter.seedize(gameDir, parsed,
                "http://manifest.example/", "std", null, null);

        assertEquals(2, outcome.seeded, "basic.jar and cfg.toml are seeded; the pack file is the RPU mod's");
        assertEquals(1, outcome.missing, "the disabled optional mod is planned as *.disabled and not yet parked");
        assertEquals(0, outcome.mismatch);

        QLSyncState state = QLSyncState.load(gameDir);
        assertEquals("std", state.activeChannel);
        assertEquals("26.8.1", state.packVersion);
        assertEquals("http://manifest.example/", state.manifestUrl);
        assertEquals(Map.of("mods/basic.jar", sha1(basic)), state.trackedOf("std"));
        assertEquals(Map.of("config/cfg.toml", sha1(config)), state.trackedOf("common"),
                "resource packs belong to the in-game RPU mod and are never tracked");
        assertTrue(state.enabledOptionalGroups().isEmpty());

        QLDblPack dbl = QLDblPack.load(gameDir);
        assertNotNull(dbl);
        assertEquals(1, dbl.mods.size(), "only the enabled basic mod is part of the inventory");
        assertEquals("mods/basic.jar", dbl.mods.get(0).path);
        assertTrue(dbl.resourcepacks.size() == 1);
        assertEquals("1.2.1", dbl.resourcepacks.get(0).version);

        // A full sync completes the seed: the optional sibling is renamed, not downloaded.
        QLSyncManager.SyncOutcome synced = QLSyncManager.sync(gameDir, parsed, "std", null, null);

        assertEquals(0, synced.downloaded, () -> "failedFiles=" + synced.failedFiles);
        assertEquals(1, synced.renamed, "packaging keeps the plain name, sync parks it as *.disabled");
        assertEquals(2, synced.unchanged, "both mods verify; the pack file is not the launcher's");
        assertEquals(Map.of(
                "mods/basic.jar", sha1(basic),
                "mods/entity-culling.jar", sha1(entityCulling)), QLSyncState.load(gameDir).trackedOf("std"));
        assertFalse(Files.exists(gameDir.resolve(QLConstants.DBLPACK_FILE).resolve("x")),
                "dblpack.json must remain a regular file");
        assertTrue(Files.isRegularFile(gameDir.resolve(QLConstants.DBLPACK_FILE)));
    }

    /// Tests that a content mismatch is left untracked and replaced by the next sync run.
    @Test
    public void mismatchedSeedFileIsReplacedBySync() throws Exception {
        byte[] onDisk = utf8("stale-mod");
        byte[] expected = utf8("fresh-mod");

        Path zip = buildSeedZip(Map.of("mods/broken.jar", onDisk));

        JsonObject manifest = manifest();
        JsonArray mods = new JsonArray();
        mods.add(fileEntry("mods/broken.jar", expected, payloadOf("/broken.jar", expected)));
        manifest.getAsJsonObject("channels").getAsJsonObject("std").add("mods", mods);

        unpack(zip);

        QLSeedImporter.SeedOutcome outcome = QLSeedImporter.seedize(gameDir,
                QLManifest.fromJson(manifest.toString()), "http://manifest.example/", "std", null, null);

        assertEquals(0, outcome.seeded);
        assertEquals(1, outcome.mismatch);

        QLSyncState seededState = QLSyncState.load(gameDir);
        assertTrue(seededState.trackedOf("std").isEmpty(), "mismatched files must stay untracked");

        QLSyncManager.SyncOutcome synced = QLSyncManager.sync(gameDir,
                QLManifest.fromJson(manifest.toString()), "std", null, null);

        assertEquals(1, synced.downloaded, () -> "failedFiles=" + synced.failedFiles);
        assertArrayEquals(utf8("fresh-mod"), Files.readAllBytes(gameDir.resolve("mods").resolve("broken.jar")));
        assertEquals(Map.of("mods/broken.jar", sha1(expected)), QLSyncState.load(gameDir).trackedOf("std"));
    }

    /// Tests the file-name version parsing contract.
    @Test
    public void parsesVersionsFromResourcePackFileNames() {
        assertEquals("1.2.1", QLDblPack.versionFromFileName("Faithful-32x_v1.2.1.zip"));
        assertEquals("1.20.1", QLDblPack.versionFromFileName("Aspak-2 (1.20.1).zip"));
        assertEquals("3.4", QLDblPack.versionFromFileName("betterdogs-3.4.jar"));
        assertNull(QLDblPack.versionFromFileName("unknown.zip"));
    }

    /// The disk marker wins over the recorded channel: recover must never
    /// restamp a folder that already owns a concrete channel.
    @Test
    public void recoverKeepsTheStampedMarker() throws Exception {
        Path mods = gameDir.resolve(QLDirectoryLayout.ACTIVE_MODS_FOLDER);
        QLDirectoryLayout.stampChannel(mods, "std");

        QLDirectoryLayout.recover(gameDir, "pfm");
        assertEquals("std", QLDirectoryLayout.readChannelMarker(mods),
                "a stale record must not restamp a marker-stamped folder");

        // a leftover folder from the retired pfm channel is neither adopted nor renamed
        Path retired = gameDir.resolve("mods-pfm");
        Files.createDirectories(retired);
        Files.writeString(retired.resolve("old.jar"), "old");
        QLDirectoryLayout.recover(gameDir, QLManifest.CHANNEL_MODS);
        assertEquals("std", QLDirectoryLayout.readChannelMarker(mods),
                "the concrete folder keeps its own channel");
        assertTrue(Files.isRegularFile(retired.resolve("old.jar")),
                "the retired pfm folder is left untouched for the user");
    }

    /// Seeding an already-synced instance must keep the disk channel: the seed
    /// target may not restamp `mods` or entangle other content into it.
    @Test
    public void seedizeKeepsTheStampedChannel() throws Exception {
        byte[] onDisk = utf8("std-mod");
        Files.createDirectories(gameDir.resolve("mods"));
        Files.write(gameDir.resolve("mods").resolve("basic.jar"), onDisk);
        QLDirectoryLayout.stampChannel(gameDir.resolve("mods"), "std");

        JsonObject manifest = manifest();
        JsonArray mods = new JsonArray();
        mods.add(fileEntry("mods/basic.jar", onDisk, payloadOf("/basic.jar", onDisk)));
        manifest.getAsJsonObject("channels").getAsJsonObject("std").add("mods", mods);

        JsonArray pfmMods = new JsonArray();
        pfmMods.add(fileEntry("mods/entity-culling.jar", utf8("pfm-mod"),
                payloadOf("/dh.jar", utf8("pfm-mod"))));
        manifest.getAsJsonObject("channels").getAsJsonObject("pfm").add("mods", pfmMods);

        QLSeedImporter.SeedOutcome outcome = QLSeedImporter.seedize(gameDir,
                QLManifest.fromJson(manifest.toString()), "http://manifest.example/", "pfm", null, null);

        assertEquals(1, outcome.seeded, "the std mod is seeded into the stamped active folder");

        QLSyncState state = QLSyncState.load(gameDir);
        assertEquals("std", state.activeChannel,
                "the disk channel decides the active one, not the seed target");
        assertEquals(Map.of("mods/basic.jar", sha1(onDisk)), state.trackedOf("std"));
        assertTrue(state.trackedOf("pfm").isEmpty(), "the parked channel has no folder to seed into");
        assertEquals("std", QLDirectoryLayout.readChannelMarker(gameDir.resolve("mods")));
        assertArrayEquals(onDisk, Files.readAllBytes(gameDir.resolve("mods").resolve("basic.jar")),
                "no pfm content may entangle the stamped std folder");
    }

    /// Files that cannot be reconciled (deleted, or unreadable for the digest
    /// phase) are reported as missing/mismatch instead of aborting the import,
    /// and the next sync run fetches them from the CDN.
    @Test
    public void unreconcilableSeedFilesStayUntracked() throws Exception {
        byte[] expected = utf8("fresh-mod");

        Path zip = buildSeedZip(Map.of("mods/broken.jar", expected));
        JsonObject manifest = manifest();
        JsonArray mods = new JsonArray();
        mods.add(fileEntry("mods/broken.jar", expected, payloadOf("/broken.jar", expected)));
        manifest.getAsJsonObject("channels").getAsJsonObject("std").add("mods", mods);

        unpack(zip);
        Files.delete(gameDir.resolve("mods").resolve("broken.jar"));

        QLSeedImporter.SeedOutcome deleted = QLSeedImporter.seedize(gameDir,
                QLManifest.fromJson(manifest.toString()), "http://manifest.example/", "std", null, null);
        assertEquals(1, deleted.missing, "a deleted seed file is planned as missing");
        assertEquals(0, deleted.seeded + deleted.mismatch);
        assertTrue(QLSyncState.load(gameDir).trackedOf("std").isEmpty(), "a deleted seed file stays untracked");

        Files.write(gameDir.resolve("mods").resolve("broken.jar"), expected);
        ReadGate gate;
        try {
            gate = denyRead(gameDir.resolve("mods").resolve("broken.jar"));
        } catch (IOException | SecurityException e) {
            // Sandboxes and unprivileged Windows accounts refuse ACL writes, so the
            // unreadable-file branch cannot be exercised there.
            assumeTrue(false, "cannot restrict read permissions here: " + e);
            return;
        }
        try (ReadGate open = gate) {
            try {
                Files.newInputStream(gameDir.resolve("mods").resolve("broken.jar")).close();
            } catch (IOException e) {
                // the gate must actually block reads; platforms that ignore the
                // permission (e.g. root) skip the digest branch of this test
                assumeTrue(e instanceof AccessDeniedException, "read denial is effective here");
            }
            QLSeedImporter.SeedOutcome denied = QLSeedImporter.seedize(gameDir,
                    QLManifest.fromJson(manifest.toString()), "http://manifest.example/", "std", null, null);
            assertEquals(1, denied.mismatch, "an unhashable file degrades to a mismatch");
            assertTrue(QLSyncState.load(gameDir).trackedOf("std").isEmpty(),
                    "an unhashable seed file stays untracked");
        }

        QLSyncManager.SyncOutcome synced = QLSyncManager.sync(gameDir,
                QLManifest.fromJson(manifest.toString()), "std", null, null);
        assertEquals(0, synced.downloaded, () -> "failedFiles=" + synced.failedFiles);
        assertEquals(1, synced.unchanged,
                "after the gate opens the file already carries the manifest content and gets adopted");
        assertArrayEquals(expected, Files.readAllBytes(gameDir.resolve("mods").resolve("broken.jar")),
                "the adopted file keeps its content");
        assertEquals(Map.of("mods/broken.jar", sha1(expected)), QLSyncState.load(gameDir).trackedOf("std"),
                "the adopted file is tracked again");
    }

    /// Blocks every read of one file while leaving its attribute reads intact,
    /// so the reconcile's size check passes and the digest phase fails; the
    /// gate restores the original permissions on close.
    private ReadGate denyRead(Path file) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl != null) {
            List<AclEntry> original = List.copyOf(acl.getAcl());
            acl.setAcl(List.of(
                    AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(acl.getOwner())
                            .setPermissions(EnumSet.of(AclEntryPermission.READ_DATA)).build(),
                    AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                            .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            return () -> acl.setAcl(original);
        }
        Set<PosixFilePermission> original =
                Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
        Set<PosixFilePermission> denied = new HashSet<>(original);
        denied.removeAll(List.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ));
        Files.setPosixFilePermissions(file, denied);
        return () -> Files.setPosixFilePermissions(file, original);
    }

    /// Restores what [denyRead] changed when closed.
    private interface ReadGate extends AutoCloseable {
        @Override
        void close() throws IOException;
    }

    /// Unpacks the synthetic mrpack exactly like HMCL's modpack wizard does.
    private void unpack(Path zip) throws Exception {
        ModpackInstallTask<Object> task = new ModpackInstallTask<>(zip, gameDir, StandardCharsets.UTF_8,
                List.of("/client-overrides", "/overrides"), any -> true, null);
        assertTrue(new AsyncTaskExecutor(task).test(), "unpacking the seed zip must succeed");
    }

    /// Writes an mrpack with an {@code overrides} prefix.
    private Path buildSeedZip(Map<String, byte[]> files) throws IOException {
        Path zip = Files.createTempFile(zipDir, "seed", ".mrpack");
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                out.putNextEntry(new java.util.zip.ZipEntry("overrides/" + entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
            out.putNextEntry(new java.util.zip.ZipEntry("modrinth.index.json"));
            out.write(utf8("{}"));
            out.closeEntry();
            out.putNextEntry(new java.util.zip.ZipEntry("pack.toml"));
            out.write(utf8("name = \"Test Seed\"\nversion = \"26.8.1-full\"\n"));
            out.closeEntry();
        }
        return zip;
    }

    private String payloadOf(String path, byte[] payload) {
        payloads.put(path, payload);
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static String sha1(byte[] payload) {
        return DigestUtils.digestToString("SHA-1", payload);
    }

    private static JsonObject fileEntry(String path, byte[] content, String url) {
        JsonObject entry = new JsonObject();
        entry.addProperty("path", path);
        entry.addProperty("sha1", DigestUtils.digestToString("SHA-1", content));
        entry.addProperty("size", content.length);
        JsonArray urls = new JsonArray();
        urls.add(url);
        entry.add("urls", urls);
        return entry;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject manifest() {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("schema", 1);
        JsonObject pack = new JsonObject();
        pack.addProperty("name", "Qinglan-2nd");
        pack.addProperty("version", "26.8.1");
        manifest.add("pack", pack);

        JsonObject channels = new JsonObject();
        for (String channel : List.of("std", "pfm", "common")) {
            JsonObject channelObject = new JsonObject();
            channelObject.addProperty("label", channel);
            channelObject.add("mods", new JsonArray());
            channelObject.add("files", new JsonArray());
            channels.add(channel, channelObject);
        }
        manifest.add("channels", channels);
        return manifest;
    }
}
