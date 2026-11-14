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
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests for [QLManifestTool]'s mrpack manifest builder, focusing on the
/// deduplication guarantee the sync engine relies on: every managed path must
/// be covered by exactly one manifest entry, or one update would download and
/// rewrite the same file twice.
@NotNullByDefault
public final class QLManifestToolTest {

    private @TempDir Path tmpDir;

    /// An index file listed twice must produce one manifest entry, not two;
    /// the duplicate is recognized like an override already covered by the index.
    @Test
    public void duplicateIndexPathIsEmittedOnce() throws IOException {
        byte[] dup = utf8("dup-mod");
        byte[] solo = utf8("solo-mod");
        byte[] config = utf8("config");

        String indexJson = indexJson(List.of(
                indexFile("mods/dup.jar", dup, List.of()),
                indexFile("mods/dup.jar", dup, List.of()),
                indexFile("mods/solo.jar", solo, List.of("https://cdn.example/solo.jar"))));

        JsonObject manifest = runTool(Map.of(
                "modrinth.index.json", utf8(indexJson),
                "overrides/mods/dup.jar", dup,
                "overrides/config/a.toml", config));

        JsonArray mods = manifest.getAsJsonObject("channels").getAsJsonObject("std").getAsJsonArray("mods");
        assertEquals(List.of("mods/dup.jar", "mods/solo.jar"), entriesOf(mods),
                "the duplicate index path must be skipped after its first occurrence");

        JsonArray files = manifest.getAsJsonObject("channels").getAsJsonObject("common").getAsJsonArray("files");
        assertEquals(List.of("config/a.toml"), entriesOf(files),
                "the overrides payload must join common and must not repeat index paths");
    }

    private static String indexJson(List<JsonObject> files) {
        JsonObject index = new JsonObject();
        index.addProperty("name", "Qinglan-2nd");
        index.addProperty("versionId", "26.8.1");
        JsonArray array = new JsonArray();
        for (JsonObject file : files)
            array.add(file);
        index.add("files", array);
        return index.toString();
    }

    private static JsonObject indexFile(String path, byte[] content, List<String> downloads) {
        JsonObject object = new JsonObject();
        object.addProperty("path", path);
        object.addProperty("size", content.length);
        JsonObject hashes = new JsonObject();
        hashes.addProperty("sha1", sha1(content));
        object.add("hashes", hashes);
        JsonArray urls = new JsonArray();
        for (String download : downloads)
            urls.add(download);
        object.add("downloads", urls);
        return object;
    }

    /// Builds the mrpack and runs the manifest builder over it.
    private JsonObject runTool(Map<String, byte[]> zipContents) throws IOException {
        try {
            return JsonParser.parseString(QLManifestTool.buildFromMrpack(
                    writeZip(zipContents), Map.of()).toString()).getAsJsonObject();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private Path writeZip(Map<String, byte[]> contents) throws IOException {
        Path zip = Files.createTempFile(tmpDir, "pack", ".mrpack");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : contents.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return zip;
    }

    private static List<String> entriesOf(JsonArray array) {
        List<String> paths = new ArrayList<>();
        for (var element : array)
            paths.add(element.getAsJsonObject().getAsJsonPrimitive("path").getAsString());
        return paths;
    }

    private static String sha1(byte[] content) {
        return DigestUtils.digestToString("SHA-1", content);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
