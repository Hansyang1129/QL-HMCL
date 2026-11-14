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
import org.jackhuang.hmcl.util.DigestUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Tests for [QLSyncManager] against a synthetic game directory.
@NotNullByDefault
public final class QLSyncManagerTest {

    private @TempDir Path gameDir;
    private HttpServer server;
    private byte @Nullable [] served;

    @BeforeEach
    public void setup() throws IOException {
        // HMCL's downloader consults the singleton CacheRepository (ETag cache),
        // whose index is only initialized by changeDirectory().
        org.jackhuang.hmcl.util.CacheRepository.getInstance().changeDirectory(gameDir.resolve(".hmcl"));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = served != null ? served : new byte[0];
            // Content-Length is set implicitly by sendResponseHeaders; do not add it manually.
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
    }

    /// Tests that a valid manifest parses and a broken one is rejected.
    @Test
    public void validatesManifestDocument() {
        JsonObject pack = new JsonObject();
        pack.addProperty("name", "Qinglan-2nd");
        pack.addProperty("version", "26.8.1");
        JsonObject valid = manifest();
        valid.add("pack", pack);
        QLManifest parsed = QLManifest.fromJson(valid.toString());
        assertEquals("26.8.1", parsed.packVersionOrDefault());

        String broken = "{\"schema\": 0, \"channels\": {\"std\": {}, \"pfm\": {}, \"common\": {}}}";
        assertThrows(IllegalStateException.class, () -> QLManifest.fromJson(broken));
    }

    /// Tests that matching files are kept and optional mods can be toggled by renaming.
    @Test
    public void togglesOptionalModsByRenaming() throws IOException {
        byte[] core = "core-mod".getBytes(StandardCharsets.UTF_8);
        byte[] opt = "optional-mod".getBytes(StandardCharsets.UTF_8);

        JsonObject manifest = manifest();
        JsonArray mods = new JsonArray();
        mods.add(fileEntry("mods/Core.jar", core, List.of("http://example.invalid/")));
        JsonObject optEntry = fileEntry("mods/Opt.jar", opt, List.of("http://example.invalid/"));
        optEntry.addProperty("optional", "voxy");
        mods.add(optEntry);
        manifest.getAsJsonObject("channels").getAsJsonObject("std").add("mods", mods);

        Path modsFolder = gameDir.resolve("mods");
        Files.createDirectories(modsFolder);
        Files.write(modsFolder.resolve("Core.jar"), core);
        Files.write(modsFolder.resolve("Opt.jar.disabled"), opt);

        QLSyncManager.SyncOutcome first = QLSyncManager.sync(gameDir,
                QLManifest.fromJson(manifest.toString()), "std", null, null);

        assertEquals(2, first.unchanged);
        assertEquals(0, first.renamed);
        assertTrue(Files.exists(modsFolder.resolve("Core.jar")));
        assertTrue(Files.exists(modsFolder.resolve("Opt.jar.disabled")));
        assertTrue(Files.exists(modsFolder.resolve(QLDirectoryLayout.CHANNEL_MARKER)));

        QLSyncManager.SyncOutcome second = QLSyncManager.sync(gameDir,
                QLManifest.fromJson(manifest.toString()), "std", Map.of("voxy", true), null);

        assertEquals(1, second.renamed);
        assertFalse(Files.exists(modsFolder.resolve("Opt.jar.disabled")));
        assertTrue(Files.exists(modsFolder.resolve("Opt.jar")));
    }

    /// Tests that downloads fill in missing files and only tracked files are purged.
    @Test
    public void downloadsAndPurgesOnlyTrackedFiles() throws Exception {
        byte[] payload = "downloaded-payload".getBytes(StandardCharsets.UTF_8);
        served = payload;
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/payload";

        JsonObject manifest = manifest();
        JsonArray mods = new JsonArray();
        mods.add(fileEntry("mods/All.jar", payload, List.of("http://example.invalid/", url)));
        manifest.getAsJsonObject("channels").getAsJsonObject("std").add("mods", mods);

        QLSyncManager.SyncOutcome first = QLSyncManager.sync(gameDir,
                QLManifest.fromJson(manifest.toString()), "std", null, null);

        assertEquals(1, first.downloaded, () -> "failedFiles=" + first.failedFiles
                + " cancelled=" + first.cancelled + " unchanged=" + first.unchanged);
        assertTrue(Files.exists(gameDir.resolve("mods").resolve("All.jar")));

        // The user's own file is not tracked and must survive manifest changes.
        Files.write(gameDir.resolve("mods").resolve("MyOwn.jar"), "user-file".getBytes(StandardCharsets.UTF_8));

        JsonObject newer = manifest();
        newer.getAsJsonObject("channels").getAsJsonObject("std").add("mods", new JsonArray());
        QLSyncManager.SyncOutcome second = QLSyncManager.sync(gameDir,
                QLManifest.fromJson(newer.toString()), "std", null, null);

        assertEquals(1, second.deleted);
        assertFalse(Files.exists(gameDir.resolve("mods").resolve("All.jar")));
        assertTrue(Files.exists(gameDir.resolve("mods").resolve("MyOwn.jar")));
        assertEquals("26.8.1", second.packVersion);
    }

    /// Tests that manifest entries must declare a non-negative size, with the channel and
    /// path named in the error message for locating the offending entry.
    @Test
    public void rejectsEntriesWithoutDeclaredSize() {
        JsonObject sizeless = manifest();
        JsonArray mods = new JsonArray();
        JsonObject entry = fileEntry("mods/Fat.jar", "sizeless".getBytes(StandardCharsets.UTF_8),
                List.of("http://example.invalid/"));
        entry.remove("size");
        mods.add(entry);
        sizeless.getAsJsonObject("channels").getAsJsonObject("std").add("mods", mods);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> QLManifest.fromJson(sizeless.toString()));
        assertTrue(thrown.getMessage().contains("std"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("mods/Fat.jar"), thrown.getMessage());

        JsonObject negative = manifest();
        JsonArray negativeMods = new JsonArray();
        JsonObject badEntry = fileEntry("mods/Negative.jar", new byte[1], List.of("http://example.invalid/"));
        badEntry.addProperty("size", -1);
        negativeMods.add(badEntry);
        negative.getAsJsonObject("channels").getAsJsonObject("std").add("mods", negativeMods);

        thrown = assertThrows(IllegalStateException.class, () -> QLManifest.fromJson(negative.toString()));
        assertTrue(thrown.getMessage().contains("declares no valid size"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("std"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("mods/Negative.jar"), thrown.getMessage());
    }

    /// Tests that files verified before a download failure are still reported as unchanged;
    /// previously the early return after partial failures reported zero.
    @Test
    public void reportsUnchangedFilesWhenSomeDownloadsFail() throws IOException {
        byte[] good = "verified-payload".getBytes(StandardCharsets.UTF_8);

        JsonObject manifest = manifest();
        JsonArray mods = new JsonArray();
        mods.add(fileEntry("mods/Good.jar", good, List.of("http://example.invalid/")));
        mods.add(fileEntry("mods/Broken.jar", good, List.of("http://example.invalid/")));
        manifest.getAsJsonObject("channels").getAsJsonObject("std").add("mods", mods);

        Path modsFolder = gameDir.resolve("mods");
        Files.createDirectories(modsFolder);
        Files.write(modsFolder.resolve("Good.jar"), good);

        QLSyncManager.SyncOutcome outcome = QLSyncManager.sync(gameDir,
                QLManifest.fromJson(manifest.toString()), "std", null, null);

        assertFalse(outcome.failedFiles.isEmpty());
        assertEquals(1, outcome.unchanged);
        assertEquals(0, outcome.downloaded);
        assertNull(outcome.packVersion);
    }

    /// Tests that a truncated state file is parked aside as {@code *.corrupt} and starts
    /// from a fresh state instead of failing every later sync.
    @Test
    public void corruptStateStartsFromAFreshState() throws IOException {
        Path state = gameDir.resolve(QLSyncState.STATE_FILE);
        Files.writeString(state, "{\"activeChannel\": \"std\", \"tracked\": {\"std\": {\"mods/Bro",
                StandardCharsets.UTF_8);

        QLSyncState loaded = QLSyncState.load(gameDir);

        assertEquals("std", loaded.activeChannel);
        assertTrue(loaded.trackMap().isEmpty());
        assertTrue(Files.exists(gameDir.resolve(QLSyncState.STATE_FILE + ".corrupt")));
        assertFalse(Files.exists(state));
    }

    /// Tests that saving the state leaves no temporary file behind and reloads unchanged.
    @Test
    public void savedStateReloadsWithoutTemporaryFiles() throws IOException {
        QLSyncState state = QLSyncState.empty();
        state.activeChannel = "pfm";
        state.packVersion = "9.9.9";
        state.manifestUrl = "http://example.invalid/manifest.json";
        state.track("std", "mods/A.jar", "da39a3ee5e6b4b0d3255bfef95601890afd80709");

        state.save(gameDir);
        QLSyncState reloaded = QLSyncState.load(gameDir);

        assertEquals("pfm", reloaded.activeChannel);
        assertEquals("9.9.9", reloaded.packVersion);
        assertEquals("http://example.invalid/manifest.json", reloaded.manifestUrl);
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", reloaded.trackedOf("std").get("mods/A.jar"));
        assertFalse(Files.exists(gameDir.resolve(QLSyncState.STATE_FILE + ".tmp")));
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

    private static JsonObject fileEntry(String path, byte[] content, List<String> urls) {
        JsonObject entry = new JsonObject();
        entry.addProperty("path", path);
        entry.addProperty("sha1", DigestUtils.digestToString("SHA-1", content));
        entry.addProperty("size", content.length);
        JsonArray urlArray = new JsonArray();
        for (String url : urls)
            urlArray.add(url);
        entry.add("urls", urlArray);
        return entry;
    }
}
