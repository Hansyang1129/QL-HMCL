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
import org.jackhuang.hmcl.util.DigestUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for [QLPackSync]: the pack file is the update source, so one run must bring an
/// instance to the pack's content, remove what the pack no longer lists, and leave the RPU
/// client's own store alone.
@NotNullByDefault
public final class QLPackSyncTest {

    /// Instance run directory the pack is applied to.
    private @TempDir Path gameDir;

    /// Scratch directory holding the synthesized packs.
    private @TempDir Path packDir;

    /// Applies a pack onto an existing instance: stale files are replaced, missing ones are
    /// written (resource packs included), and the RPU store is never touched.
    @Test
    public void appliesThePackOntoAnInstance() throws IOException {
        byte[] mod = utf8("fresh-mod");
        byte[] config = utf8("config");
        byte[] pack = utf8("pack");

        Path mods = Files.createDirectories(gameDir.resolve("mods"));
        Files.write(mods.resolve("a.jar"), utf8("old-mod"));

        Path mrpack = buildPack(Map.of(
                "overrides/mods/a.jar", mod,
                "overrides/config/x.toml", config,
                "overrides/resourcepacks/RP.zip", pack,
                "overrides/qlrpu/cache/current.json", utf8("{}")),
                List.of(indexFile("mods/a.jar", mod),
                        indexFile("config/x.toml", config),
                        indexFile("resourcepacks/RP.zip", pack)));

        QLPackSync.Outcome first = QLPackSync.sync(gameDir, mrpack, "https://example.invalid/p.mrpack", null);
        assertEquals(3, first.downloaded(), "stale mod, config and optional pack are written");
        assertEquals(0, first.unchanged());
        assertEquals(0, first.failed(), () -> "failed: " + first.failedPaths());
        assertContent(mod, mods.resolve("a.jar"));
        assertContent(config, gameDir.resolve("config").resolve("x.toml"));
        assertContent(pack, gameDir.resolve("resourcepacks").resolve("RP.zip"));
        assertFalse(Files.exists(gameDir.resolve("qlrpu")), "the RPU store is its own business");

        QLSyncState state = QLSyncState.load(gameDir);
        assertEquals("26.8.1", state.packVersion);
        assertEquals("std", state.activeChannel);
        assertEquals("https://example.invalid/p.mrpack", state.packUrl);
        assertEquals(2, state.trackedOf(QLManifest.CHANNEL_COMMON).size(),
                "config and the resource pack are tracked");

        QLPackSync.Outcome second = QLPackSync.sync(gameDir, mrpack, null, null);
        assertEquals(0, second.downloaded(), "a second run has nothing to do");
        assertEquals(3, second.unchanged());
        assertEquals(0, second.deleted());
        assertEquals("https://example.invalid/p.mrpack", QLSyncState.load(gameDir).packUrl,
                "a null source keeps the recorded one");
    }

    /// Files an earlier pack installed but the current one does not list are removed.
    @Test
    public void removesFilesThePackNoLongerLists() throws IOException {
        Path mods = Files.createDirectories(gameDir.resolve("mods"));
        Files.write(mods.resolve("gone.jar"), utf8("gone"));
        QLSyncState state = QLSyncState.load(gameDir);
        state.track(QLManifest.CHANNEL_MODS, "mods/gone.jar",
                DigestUtils.digestToString("SHA-1", utf8("gone")));
        state.save(gameDir);

        byte[] kept = utf8("kept");
        Path mrpack = buildPack(Map.of("overrides/mods/kept.jar", kept),
                List.of(indexFile("mods/kept.jar", kept)));

        QLPackSync.Outcome outcome = QLPackSync.sync(gameDir, mrpack, null, null);
        assertEquals(1, outcome.deleted());
        assertFalse(Files.exists(mods.resolve("gone.jar")), "the pack no longer ships it");
        assertContent(kept, mods.resolve("kept.jar"));
        assertEquals(Map.of("mods/kept.jar", DigestUtils.digestToString("SHA-1", kept)),
                QLSyncState.load(gameDir).trackedOf(QLManifest.CHANNEL_MODS));
    }

    /// Disabling an optional group parks its jars as {@code *.jar.disabled} on the next run.
    @Test
    public void parksOptionalModsWhenTheirGroupIsDisabled() throws IOException {
        byte[] voxy = utf8("voxy");
        Path mrpack = buildPack(Map.of("overrides/mods/[O]voxy-1.0.jar", voxy),
                List.of(indexFile("mods/[O]voxy-1.0.jar", voxy)));

        QLPackSync.sync(gameDir, mrpack, null, null);
        Path plain = gameDir.resolve("mods").resolve("[O]voxy-1.0.jar");
        assertTrue(Files.isRegularFile(plain));

        QLSyncState state = QLSyncState.load(gameDir);
        state.setOptionalEnabled("voxy", false);
        state.save(gameDir);

        QLPackSync.Outcome outcome = QLPackSync.sync(gameDir, mrpack, null, null);
        assertEquals(1, outcome.renamed());
        assertFalse(Files.exists(plain));
        assertTrue(Files.isRegularFile(gameDir.resolve("mods").resolve("[O]voxy-1.0.jar.disabled")));
    }

    /// Builds a minimal {@code .mrpack} with the given overrides payload and index entries.
    private Path buildPack(Map<String, byte[]> payload, List<JsonObject> files) throws IOException {
        JsonObject index = new JsonObject();
        index.addProperty("name", "Qinglan-2nd-RPU");
        index.addProperty("versionId", "26.8.1");
        JsonArray array = new JsonArray();
        for (JsonObject file : files)
            array.add(file);
        index.add("files", array);

        Path zip = Files.createTempFile(packDir, "pack", ".mrpack");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
            out.putNextEntry(new ZipEntry("modrinth.index.json"));
            out.write(utf8(index.toString()));
            out.closeEntry();
            for (Map.Entry<String, byte[]> entry : payload.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return zip;
    }

    /// Builds one {@code modrinth.index.json} entry (path, size, SHA-1, no downloads).
    private static JsonObject indexFile(String path, byte[] content) {
        JsonObject object = new JsonObject();
        object.addProperty("path", path);
        object.addProperty("size", content.length);
        JsonObject hashes = new JsonObject();
        hashes.addProperty("sha1", DigestUtils.digestToString("SHA-1", content));
        object.add("hashes", hashes);
        object.add("downloads", new JsonArray());
        return object;
    }

    /// Asserts the file content equals the expected UTF-8 text.
    private static void assertContent(byte[] expected, Path file) throws IOException {
        assertEquals(new String(expected, StandardCharsets.UTF_8), Files.readString(file, StandardCharsets.UTF_8));
    }

    /// @return the UTF-8 bytes of the given text
    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
