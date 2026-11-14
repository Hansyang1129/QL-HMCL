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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.CompressingUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/// Item-level inventory of a Qinglan modpack instance, persisted as {@code dblpack.json}.
///
/// <p>While the manifest only speaks in files (name and SHA-1), the dblpack speaks in
/// <em>items</em>: every mod with its parsed metadata and every resource pack with a
/// best-effort version parsed from the file name. Resource packs have no built-in
/// version identifier — the file name is the only place some of them carry one.</p>
///
/// <p>The inventory is generated from the manifest plus the files that are already on
/// disk right after a seed import or a sync; it travels with the game directory and is
/// used to describe the contents of the current pack in the user interface.</p>
@NotNullByDefault
public final class QLDblPack {

    /// Dblpack schema version supported by this launcher build.
    public static final int SCHEMA = 1;

    /// Version-less marker for items whose version could not be parsed.
    public static final String VERSION_UNKNOWN = "?";

    public int schema;

    /// Identity of the modpack snapshot this inventory was generated for.
    public @Nullable Pack pack;

    /// ISO-8601 timestamp of the moment this inventory was generated.
    public @Nullable String generatedAt;

    /// Mods currently managed by the manifest, with parsed metadata when available.
    public List<ModEntry> mods = new ArrayList<>();

    /// Resource packs managed by the manifest, with file-name parsed versions.
    public List<ItemEntry> resourcepacks = new ArrayList<>();

    /// Shader packs managed by the manifest, with file-name parsed versions.
    public List<ItemEntry> shaders = new ArrayList<>();

    /// Identity of the modpack snapshot reflected by this inventory.
    @NotNullByDefault
    public static final class Pack {

        /// Name of the modpack, e.g. {@code Qinglan-2nd}.
        public @Nullable String name;

        /// Version string of the modpack, e.g. {@code 26.8.1}.
        public @Nullable String version;
    }

    /// A single mod file with its best-effort metadata.
    @NotNullByDefault
    public static final class ModEntry {

        /// Manifest-style logical path, e.g. {@code mods/26.5mods/MTR.jar}.
        public @Nullable String path;

        /// Physical file name as present on disk, including {@link QLConstants#DISABLED_SUFFIX}.
        public @Nullable String filename;

        /// Mod name from embedded metadata, or {@code null} when it could not be parsed.
        public @Nullable String name;

        /// Mod version from embedded metadata, or {@code null} when it could not be parsed.
        public @Nullable String version;

        /// SHA-1 digest declared by the manifest.
        public @Nullable String sha1;

        /// Name of the optional group, or {@code null} when the mod is always installed.
        public @Nullable String optional;

        /// {@code true} when the file on disk is parked under a disabled state.
        public boolean disabled;
    }

    /// A resource pack or shader pack file without embedded metadata.
    @NotNullByDefault
    public static final class ItemEntry {

        /// Manifest-style logical path, e.g. {@code resourcepacks/Faithful_32x.zip}.
        public @Nullable String path;

        /// Physical file name as present on disk.
        public @Nullable String filename;

        /// Version parsed from the file name, {@code null} when unparsable.
        public @Nullable String version;

        /// SHA-1 digest declared by the manifest.
        public @Nullable String sha1;
    }

    private QLDblPack() {
    }

    /// Builds the inventory of the files the manifest currently manages and that are
    /// already present on disk.
    ///
    /// @param gameDir          the game directory of the instance
    /// @param manifest         the applied manifest
    /// @param activeChannel    the currently active mods channel
    /// @param enabledOptional  the optional-mod groups currently enabled
    /// @return the inventory; entries whose file is missing from disk are skipped
    /// @throws IOException when reading pack metadata unexpectedly fails
    public static QLDblPack scan(Path gameDir, QLManifest manifest, String activeChannel,
                                 Set<String> enabledOptional) throws IOException {
        QLDblPack dbl = new QLDblPack();
        dbl.schema = SCHEMA;

        QLDblPack.Pack pack = new QLDblPack.Pack();
        pack.name = manifest.packNameOrDefault();
        pack.version = manifest.packVersionOrDefault();
        dbl.pack = pack;
        dbl.generatedAt = Instant.now().toString();

        for (String channel : QLManifest.MODS_CHANNELS) {
            Path folder = QLDirectoryLayout.modsDirectory(gameDir, channel, activeChannel);
            for (QLManifest.FileEntry entry : manifest.channelOrThrow(channel).mods) {
                String raw = rawFileName(entry);
                ModEntry mod = new ModEntry();
                mod.path = entry.path;
                mod.sha1 = entry.sha1;
                mod.optional = entry.optional;
                boolean enabled = entry.optional == null || enabledOptional.contains(entry.optional);
                Path plain = folder.resolve(raw);
                Path parked = folder.resolve(raw + QLConstants.DISABLED_SUFFIX);
                // A disabled optional mod only counts when it is parked under its disabled name.
                Path found;
                if (enabled) {
                    found = Files.isRegularFile(plain) ? plain : Files.isRegularFile(parked) ? parked : null;
                } else {
                    found = Files.isRegularFile(parked) ? parked : null;
                }
                if (found == null)
                    continue; // the file is not on disk yet; it describes nothing on this instance
                mod.filename = found.getFileName().toString();
                mod.disabled = found.equals(parked);
                readModMetadata(found, mod);
                dbl.mods.add(mod);
            }
        }

        for (QLManifest.FileEntry entry : manifest.channelOrThrow(QLManifest.CHANNEL_COMMON).files) {
            String path = entry.path;
            if (path.startsWith("resourcepacks/") || path.startsWith("shaderpacks/")) {
                Path target = gameDir.resolve(path.replace("/", gameDir.getFileSystem().getSeparator()));
                if (!Files.isRegularFile(target))
                    continue;
                ItemEntry item = new ItemEntry();
                item.path = path;
                item.sha1 = entry.sha1;
                item.filename = target.getFileName().toString();
                item.version = versionFromFileName(item.filename);
                if (path.startsWith("resourcepacks/"))
                    dbl.resourcepacks.add(item);
                else
                    dbl.shaders.add(item);
            }
        }

        return dbl;
    }

    /// Loads the inventory of a game directory.
    ///
    /// @return the parsed inventory, or {@code null} when the instance has none
    /// @throws IOException when the file cannot be read
    public static @Nullable QLDblPack load(Path gameDir) throws IOException {
        Path file = gameDir.resolve(QLConstants.DBLPACK_FILE);
        if (!Files.isRegularFile(file))
            return null;
        QLDblPack dbl = JsonUtils.fromJsonFile(file, QLDblPack.class);
        if (dbl == null)
            return null;
        if (dbl.mods == null)
            dbl.mods = new ArrayList<>();
        if (dbl.resourcepacks == null)
            dbl.resourcepacks = new ArrayList<>();
        if (dbl.shaders == null)
            dbl.shaders = new ArrayList<>();
        return dbl;
    }

    /// Persists the inventory into the game directory.
    public void save(Path gameDir) throws IOException {
        JsonUtils.writeToJsonFile(gameDir.resolve(QLConstants.DBLPACK_FILE), this);
    }

    /// Reads the embedded mod metadata of a jar/zip into {@code mod}, leaving it untouched on failure.
    private static void readModMetadata(Path file, ModEntry mod) {
        JsonObject fabric;
        try {
            String json = CompressingUtils.readTextZipEntry(file, "fabric.mod.json");
            JsonElement element = JsonParser.parseString(json);
            fabric = element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (IOException | RuntimeException e) {
            fabric = null;
        }
        if (fabric != null) {
            mod.name = stringOrNull(fabric, "name", "id");
            mod.version = stringOrNull(fabric, "version");
            return;
        }

        try {
            String info = CompressingUtils.readTextZipEntry(file, "mcmod.info");
            JsonElement element = JsonParser.parseString(info);
            JsonArray array = element.isJsonArray() ? element.getAsJsonArray() : null;
            if (array != null && !array.isEmpty() && array.get(0).isJsonObject()) {
                JsonObject legacy = array.get(0).getAsJsonObject();
                mod.name = stringOrNull(legacy, "name", "modid");
                mod.version = stringOrNull(legacy, "version");
            }
        } catch (IOException | RuntimeException ignored) {
            // Neither format present; keep the entry without metadata.
        }
    }

    private static @Nullable String stringOrNull(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement element = object.get(key);
            if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                String value = element.getAsString();
                if (!value.isBlank())
                    return value;
            }
        }
        return null;
    }

    /// Parses a version suffix out of a file name, e.g. {@code Faithful_32x_v1.2.1.zip} to
    /// {@code 1.2.1}. Resource packs have no embedded metadata, so the file name is the
    /// only portable hint.
    ///
    /// @return the parsed version, {@code null} when the file name carries no dotted number
    public static @Nullable String versionFromFileName(String filename) {
        String stem = filename.replaceFirst("\\.[^./]+$", "");
        if (stem.endsWith(")"))
            stem = stem.substring(0, stem.length() - 1);
        Matcher matcher = Pattern.compile("([0-9]+(?:\\.[0-9]+){1,4})[a-zA-Z0-9]*$").matcher(stem);
        if (matcher.find() && matcher.group(1) != null)
            return matcher.group(1);
        matcher = Pattern.compile("[vV][_-]?([0-9]+(?:\\.[0-9]+)*)").matcher(stem);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String rawFileName(QLManifest.FileEntry entry) {
        String path = Objects.requireNonNull(entry.path, "manifest path");
        int slash = path.indexOf('/', 1);
        return path.substring(slash + 1);
    }
}
