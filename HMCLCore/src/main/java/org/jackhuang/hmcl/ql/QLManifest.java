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
import org.jetbrains.annotations.Unmodifiable;
import org.jackhuang.hmcl.util.gson.JsonUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/// Describes the desired remote state of a Qinglan modpack instance.
///
/// <p>The launcher compares this manifest against the files on disk and performs
/// the minimum set of operations (download, rename, delete) required to make the
/// instance match the manifest. The manifest is produced by the server-side
/// publishing tool and can be served from any HTTP storage.</p>
///
/// <h2>Channel model</h2>
/// <p>The manifest groups files into two channels:</p>
/// <ul>
///     <li>{@code std} — the modpack's mods (the only mods channel; the historical
///     {@code std}/{@code pfm} split is gone, a pack either is the official pack or is not
///     syncable at all);</li>
///     <li>{@code common} — mod configs and {@code options.txt}, resolved relative to the
///     game directory.</li>
/// </ul>
///
/// <p>Entries of the {@code std}/{@code pfm} channels use paths relative to the mods folder
/// (for example {@code mods/Sodium-0.5.13.jar}) so that swapping the active folder does not
/// invalidate manifest paths. Entries of the {@code common} channel embed their directory prefix
/// (for example {@code config/create-server.toml}).</p>
///
/// <p>Resource packs and shaders are deliberately <em>not</em> part of any channel: the pack
/// updates them in game through its RPU mod, and {@code QLSyncManager} ignores such entries
/// (and hands over any it used to track) so the launcher never manages those folders.</p>
///
/// <h2>Optional mods</h2>
/// <p>An entry may set the {@code optional} field to the name of an optional-mod group
/// (for example {@code voxy}). Optional mods are only kept on disk when the corresponding
/// group is enabled by the user; otherwise the launcher stores them as {@code *.jar.disabled}
/// in the mods folder of each channel that lists them, keeping HMCL's native mod list intact.</p>
///
/// <h2>Example</h2>
/// <pre>{@code
/// {
///   "schema": 1,
///   "pack": { "name": "Qinglan-2nd", "version": "26.8.1", "updatedAt": "2026-10-13T19:00:00Z" },
///   "channels": {
///     "std":  { "label": "模组", "mods": [ { "path": "mods/Sodium-0.5.13.jar", "...": "..." } ] },
///     "common": { "label": "公共文件", "files": [ ... ] }
///   },
///   "servers": [ { "id": "main", "name": "Example server", "address": "example.invalid:25565", "kind": "minecraft" } ]
/// }
/// }</pre>
@NotNullByDefault
public final class QLManifest {

    /// Manifest schema version supported by this launcher build.
    public static final int SCHEMA = 1;

    /// Wire name of the single mods channel. It keeps the historical {@code std} spelling for
    /// compatibility with published tooling; there is no second mods channel any more.
    public static final String CHANNEL_MODS = "std";

    /// The single mods channel ({@link #CHANNEL_MODS}); kept as a set for the layout helpers.
    public static final Set<String> MODS_CHANNELS = Set.of(CHANNEL_MODS);

    /// Name of the shared channel whose files resolve directly against the game directory.
    public static final String CHANNEL_COMMON = "common";

    /// Folders the in-game RPU mod owns. The launcher never downloads, tracks or deletes
    /// files there: {@code qlrpu} is the client's own store ({@code cache/current.json} plus
    /// encrypted {@code cache/objects/*.obj}). Resource packs are <em>not</em> in this list:
    /// the {@code .mrpack} still ships some optional packs and the launcher syncs them.
    public static final List<String> RPU_FOLDERS = List.of("qlrpu");

    /// Individual files inside launcher-owned folders that still belong to the RPU client,
    /// because it is the only writer of its own settings.
    public static final List<String> RPU_FILES = List.of("config/qlrpu-client.json");

    /// @return whether the logical path belongs to the in-game RPU mod instead of the launcher.
    public static boolean isRpuOwned(@Nullable String logicalPath) {
        if (logicalPath == null)
            return false;
        String normalized = logicalPath.replace('\\', '/');
        if (RPU_FILES.contains(normalized))
            return true;
        int slash = normalized.indexOf('/');
        String segment = slash < 0 ? normalized : normalized.substring(0, slash);
        return RPU_FOLDERS.contains(segment);
    }

    /// Manifest schema version; must equal {@link #SCHEMA} for the manifest to be accepted.
    public int schema;

    /// Identity of the modpack: name, version and publication timestamp.
    public @Nullable Pack pack;

    /// Channels by name; must contain {@code std} and {@code common}. A legacy {@code pfm}
    /// channel may still be present and is simply ignored.
    public @Nullable Map<String, Channel> channels;

    /// Reserved for future use: list of servers shown in the launcher UI.
    public @Nullable List<QLServer> servers;

    /// Identity of the modpack as published by the server-side tool.
    @NotNullByDefault
    public static final class Pack {

        /// Name of the modpack, e.g. {@code Qinglan-2nd}.
        public @Nullable String name;

        /// Version string of the modpack, e.g. {@code 26.8.1}.
        public @Nullable String version;

        /// ISO-8601 timestamp of the moment this manifest was published.
        public @Nullable String updatedAt;
    }

    /// A channel groups files that are installed or removed together.
    @NotNullByDefault
    public static final class Channel {

        /// Human-readable label of the channel, e.g. official pack or optimized pack.
        public @Nullable String label;

        /// Mod files of this channel, paths relative to the mods folder (with a {@code mods/} prefix).
        public List<FileEntry> mods = new ArrayList<>();

        /// Shared files of the game directory; only used by the {@code common} channel.
        public List<FileEntry> files = new ArrayList<>();
    }

    /// A single managed file of the modpack.
    @NotNullByDefault
    public static final class FileEntry {

        /// Path of the file relative to the channel root, always using {@code /} as separator.
        public @Nullable String path;

        /// Lower-case SHA-1 hex digest of the file content.
        public @Nullable String sha1;

        /// Size of the file in bytes; used as a fast-path before full hashing.
        ///
        /// <p>{@link #fromJson(String)} rejects entries that declare no non-negative size;
        /// only hand-built manifests (tests) may carry {@code null}.</p>
        public @Nullable Long size;

        /// Candidate download URLs; the first reachable URL wins.
        public List<String> urls = new ArrayList<>();

        /// Name of the optional group this file belongs to, or {@code null} when always installed.
        public @Nullable String optional;
    }

    /// Reserved UI-side description of a game server. Not consumed by the sync engine.
    @NotNullByDefault
    public static final class QLServer {
        /// Stable identifier of the server, e.g. {@code qinglan}.
        public @Nullable String id;

        /// Display name.
        public @Nullable String name;

        /// Address, {@code host} or {@code host:port}.
        public @Nullable String address;

        /// Kind of service, e.g. {@code minecraft}, {@code mtr-web}, {@code mtr-reporter}.
        public @Nullable String kind;
    }

    private QLManifest() {
    }

    /// @return the human-readable pack name, or {@code "Qinglan"} when absent.
    public String packNameOrDefault() {
        return pack == null || pack.name == null || pack.name.isBlank() ? "Qinglan" : pack.name;
    }

    /// @return the pack version string, or {@code "?"} when absent.
    public String packVersionOrDefault() {
        return pack == null || pack.version == null || pack.version.isBlank() ? "?" : pack.version;
    }

    /// @return an unmodifiable view of the managed channels, aligned with the manifest document.
    public Map<String, Channel> channelsOrThrow() {
        if (channels == null)
            throw new IllegalStateException("Manifest does not contain channels");
        return Collections.unmodifiableMap(channels);
    }

    /// @return the channel with the given name, throwing when missing from the manifest.
    public Channel channelOrThrow(String name) {
        Channel channel = channelsOrThrow().get(name);
        if (channel == null)
            throw new IllegalStateException("Manifest does not contain channel " + name);
        return channel;
    }

    /// @return names of optional-mod groups declared by the manifest, in declaration order.
    public @Unmodifiable List<String> optionalGroups() {
        List<String> result = new ArrayList<>();
        for (Channel channel : channelsOrThrow().values()) {
            collectGroups(channel.mods, result);
            collectGroups(channel.files, result);
        }
        return List.copyOf(result);
    }

    private static void collectGroups(List<FileEntry> entries, List<String> sink) {
        for (FileEntry entry : entries) {
            if (entry.optional != null && !entry.optional.isBlank() && !sink.contains(entry.optional))
                sink.add(entry.optional);
        }
    }

    /// @return all entries from the given channel, ignoring entries of disabled optional groups.
    public @Unmodifiable List<FileEntry> effectiveEntries(String channelName, Set<String> enabledOptional) {
        if (MODS_CHANNELS.contains(normalizeChannel(channelName))) {
            return effective(channelOrThrow(channelName).mods, enabledOptional);
        } else {
            return effective(channelOrThrow(channelName).files, enabledOptional);
        }
    }

    private static List<FileEntry> effective(List<FileEntry> entries, Set<String> enabledOptional) {
        List<FileEntry> result = new ArrayList<>(entries.size());
        for (FileEntry entry : entries) {
            if (entry.optional == null || entry.optional.isBlank() || enabledOptional.contains(entry.optional))
                result.add(entry);
        }
        return Collections.unmodifiableList(result);
    }

    /// Normalizes an unknown channel name, mapping unknown values to {@link #CHANNEL_COMMON}.
    public static String normalizeChannel(String channel) {
        if (MODS_CHANNELS.contains(channel.toLowerCase(Locale.ROOT)))
            return channel.toLowerCase(Locale.ROOT);
        return CHANNEL_COMMON;
    }

    /// Parses a manifest document, verifying the schema and the presence of required channels.
    ///
    /// @param json the manifest text
    /// @return a validated manifest
    /// @throws IllegalStateException when the JSON is not a valid manifest for this launcher
    public static QLManifest fromJson(String json) {
        QLManifest manifest = JsonUtils.fromJson(json, QLManifest.class);
        if (manifest == null)
            throw new IllegalStateException("Manifest is empty");
        if (manifest.schema != SCHEMA)
            throw new IllegalStateException("Unsupported manifest schema: " + manifest.schema);
        Map<String, Channel> channels = manifest.channels;
        if (channels == null || channels.isEmpty())
            throw new IllegalStateException("Manifest does not contain channels");
        for (String required : MODS_CHANNELS)
            if (!channels.containsKey(required))
                throw new IllegalStateException("Manifest is missing channel " + required);
        if (!channels.containsKey(CHANNEL_COMMON))
            throw new IllegalStateException("Manifest is missing channel " + CHANNEL_COMMON);

        for (Map.Entry<String, Channel> channel : channels.entrySet())
            validateChannel(channel.getKey(), channel.getValue());
        assert manifest.servers == null || manifest.servers instanceof List;
        return manifest;
    }

    private static void validateChannel(String channelName, Channel channel) {
        Map<String, FileEntry> map = new LinkedHashMap<>();
        validateEntries(channelName, channel.mods, map);
        validateEntries(channelName, channel.files, map);
    }

    private static void validateEntries(String channelName, List<FileEntry> entries, Map<String, FileEntry> map) {
        for (FileEntry entry : entries) {
            if (entry.path == null || entry.sha1 == null || entry.urls.isEmpty())
                throw new IllegalStateException("Manifest entry is incomplete: " + channelName + "/" + entry.path);
            String path = entry.path;
            if (path.startsWith("/") || path.startsWith("\\") || path.contains("..") || path.contains(":"))
                throw new IllegalStateException("Unsafe manifest path: " + path);
            if (entry.size == null || entry.size < 0)
                throw new IllegalStateException("Manifest entry " + channelName + "/" + path
                        + " declares no valid size: " + entry.size);
            if (map.containsKey(entry.path))
                throw new IllegalStateException("Duplicate manifest path: " + entry.path);
            map.put(entry.path, entry);
        }
    }
}
