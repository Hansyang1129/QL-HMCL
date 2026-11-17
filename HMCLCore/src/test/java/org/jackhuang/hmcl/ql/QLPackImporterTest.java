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

/// Tests for [QLPackImporter]: a seed pack is imported/updated onto an existing instance,
/// it never installs a new one, and the in-game RPU client keeps its own folders.
@NotNullByDefault
public final class QLPackImporterTest {

    private @TempDir Path gameDir;
    private @TempDir Path packDir;

    /// Applies a pack onto an existing instance: stale files are replaced from the overrides
    /// payload, missing ones are written, resource packs are left to the RPU client, and the
    /// state records everything for the next regular sync.
    @Test
    public void importsAndUpdatesAnExistingInstance() throws IOException {
        byte[] mod = utf8("fresh-mod");
        byte[] config = utf8("config");
        byte[] packFile = utf8("pack");

        Path mods = Files.createDirectories(gameDir.resolve("mods"));
        Files.write(mods.resolve("a.jar"), utf8("old-mod"));

        Path mrpack = buildPack(Map.of(
                "overrides/mods/a.jar", mod,
                "overrides/config/x.toml", config,
                "overrides/resourcepacks/RP.zip", packFile),
                List.of(indexFile("mods/a.jar", mod),
                        indexFile("config/x.toml", config),
                        indexFile("resourcepacks/RP.zip", packFile)));

        QLPackImporter.Outcome first = QLPackImporter.applyToInstance(
                gameDir, mrpack, "http://example.invalid/manifest.json", null);

        assertEquals(2, first.imported(), "the stale mod and the missing config are written");
        assertEquals(0, first.unchanged());
        assertEquals(0, first.skippedRpu(),
                "the manifest builder already filters RPU paths out, so none reaches the importer");
        assertEquals(0, first.failed());
        assertArrayEqualsContent(mod, mods.resolve("a.jar"));
        assertArrayEqualsContent(config, gameDir.resolve("config").resolve("x.toml"));
        assertFalse(Files.exists(gameDir.resolve("resourcepacks")),
                "the launcher must not create the resource pack folder");

        QLSyncState state = QLSyncState.load(gameDir);
        assertEquals("26.8.1", state.packVersion);
        assertEquals("std", state.activeChannel);
        assertEquals("http://example.invalid/manifest.json", state.manifestUrl);
        assertEquals(Map.of("mods/a.jar", DigestUtils.digestToString("SHA-1", mod)),
                state.trackedOf(QLManifest.CHANNEL_MODS));
        assertEquals(Map.of("config/x.toml", DigestUtils.digestToString("SHA-1", config)),
                state.trackedOf(QLManifest.CHANNEL_COMMON));

        QLPackImporter.Outcome second = QLPackImporter.applyToInstance(gameDir, mrpack, null, null);
        assertEquals(0, second.imported(), "a second run has nothing to write");
        assertEquals(2, second.unchanged());
        assertEquals(0, second.skippedRpu());
        assertEquals("http://example.invalid/manifest.json", QLSyncState.load(gameDir).manifestUrl,
                "a null source keeps the previously recorded one");
    }

    private Path buildPack(Map<String, byte[]> payload, List<JsonObject> files) throws IOException {
        JsonObject index = new JsonObject();
        index.addProperty("name", "Qinglan-2nd");
        index.addProperty("versionId", "26.8.1");
        JsonArray array = new JsonArray();
        for (JsonObject file : files)
            array.add(file);
        index.add("files", array);

        Path zip = Files.createTempFile(packDir, "seed", ".mrpack");
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

    private static void assertArrayEqualsContent(byte[] expected, Path file) throws IOException {
        assertEquals(new String(expected, StandardCharsets.UTF_8),
                Files.readString(file, StandardCharsets.UTF_8));
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
