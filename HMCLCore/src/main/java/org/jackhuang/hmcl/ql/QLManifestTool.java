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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Qinglan manifest publishing tool.
///
/// <p>Turns a Qinglan modpack into the incremental-update manifest consumed by
/// {@link QLSyncManager}. It is not launched by end users; release operators run it
/// through Gradle on the source machine:</p>
///
/// <pre>{@code
/// gradle :HMCLCore:runQlManifestTool -PqlToolArgs="from-mrpack C:/temp/pack.mrpack --out C:/temp/manifest.json"
/// gradle :HMCLCore:runQlManifestTool -PqlToolArgs="from-instance C:/temp/run --out C:/temp/manifest.json"
/// gradle :HMCLCore:runQlManifestTool -PqlToolArgs="validate C:/temp/manifest.json"
/// }</pre>
///
/// <p>Both FULL packs (payload embedded under {@code overrides/}, empty
/// {@code modrinth.index.json}) and LITE packs (file list referring to Modrinth/Forge
/// CDN URLs) are accepted. Index entries carry their CDN URLs first and the resource
/// server URL second; embedded override entries get only the resource server URL.
/// Arguments are split on whitespace, so paths containing spaces must be avoided.</p>
///
/// <p>Paths are routed by their leading segment: {@code mods/**} joins the {@code std}
/// channel, {@code config/**} and {@code options.txt} join the {@code common} channel,
/// and everything else is skipped and reported. The {@code PCL} folder,
/// {@code servers.dat}, {@code modrinth.index.json} and
/// {@code fabric-loader-}/{@code fabric-api} leftovers are never published.</p>
///
/// <p><b>Resource packs are not this launcher's business.</b> The pack ships the in-game
/// {@code QingLanRPUClient} mod, which keeps its own store under {@code qlrpu/}
/// ({@code cache/current.json} plus encrypted {@code cache/objects/*.obj}) and mounts the
/// packs virtually at runtime; its settings file {@code config/qlrpu-client.json} is its
/// own too. Those paths, and any {@code resourcepacks/**} content, are counted and
/// reported as {@code RPU} instead of being published. Shaders are different: they are
/// part of the minimal mrpack and join the {@code common} channel like the mod configs.</p>
@NotNullByDefault
public final class QLManifestTool {

    /// URLs of the resource server files, appended after CDN URLs as a fallback source.
    private static final String DEFAULT_BASE_URL = "https://resource.ota8a.cn/qinglan/files/";

    private static final String DEFAULT_PACK_NAME = "Qinglan-2nd";
    private static final String DEFAULT_PACK_VERSION = "26.8.1";

    /// Override folders distributed as common files: the mods' configs and the shader packs
    /// (shaders are part of the minimal mrpack; resource packs are not, see {@link #RPU_FOLDERS}).
    private static final List<String> COMMON_FOLDERS = List.of("config", "shaderpacks");

    /// Folders owned by the in-game RPU mod; never published by this launcher.
    private static final List<String> RPU_FOLDERS = List.of("resourcepacks", "shaderpacks");

    /// Override root files distributed as common files.
    private static final List<String> COMMON_ROOT_FILES = List.of("options.txt");

    /// Files and folders of the overrides payload never distributed to players.
    private static final List<String> SKIPPED_ROOT_FILES = List.of("servers.dat", "modrinth.index.json");
    private static final List<String> SKIPPED_ROOT_FOLDERS = List.of("PCL");

    /// Mod files excluded from publishing; they are known broken leftovers of the pack.
    private static final List<String> EXCLUDED_MOD_FILE_PREFIXES = List.of("fabric-loader-", "fabric-api");

    private QLManifestTool() {
    }

    /// CLI entry point.
    public static void main(String[] args) {
        if (args.length < 2) {
            usage();
            return;
        }

        try {
            run(args);
        } catch (Exception e) {
            LOG.error("Failed: " + e);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException {
        String mode = args[0];
        Path source = Path.of(args[1]);
        Map<String, String> options = parseOptions(args, 2);

        switch (mode) {
            case "from-mrpack" -> {
                JsonObject manifest = buildFromMrpack(source, options);
                Path out = outPath(options, source);
                Files.writeString(out, prettyPrint(manifest), StandardCharsets.UTF_8);
                System.out.println("Manifest written to " + out.toAbsolutePath());
            }
            case "from-instance" -> {
                JsonObject manifest = buildFromInstance(source, options);
                Path out = outPath(options, source);
                Files.writeString(out, prettyPrint(manifest), StandardCharsets.UTF_8);
                System.out.println("Manifest written to " + out.toAbsolutePath());
            }
            case "validate" -> validate(source);
            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    private static void usage() {
        System.out.println("""
                Usage:
                  java %s from-mrpack <pack.mrpack> [--out manifest.json] [--base-url URL] [--pack-name NAME] [--pack-version VER]
                  java %<s from-instance <runDirectory> [--out manifest.json] [--base-url URL] [--pack-name NAME] [--pack-version VER]
                  java %<s validate <manifest.json>"""
                .formatted("org.jackhuang.hmcl.ql.QLManifestTool"));
    }

    private static Map<String, String> parseOptions(String[] args, int from) {
        Map<String, String> options = new java.util.LinkedHashMap<>();
        Set<String> allowed = Set.of("out", "base-url", "pack-name", "pack-version");
        for (int i = from; i < args.length; i++) {
            String token = args[i];
            if (!token.startsWith("--"))
                throw new IllegalArgumentException("Unexpected argument: " + token);
            String key = token.substring(2);
            if (!allowed.contains(key))
                throw new IllegalArgumentException("Unknown option --" + key);
            options.put(key, args[++i]);
        }
        return options;
    }

    private static Path outPath(Map<String, String> options, Path source) {
        return Path.of(options.getOrDefault("out", source.resolveSibling("manifest.json").toString()));
    }

    // ============================================================================================
    // Manifest building
    // ============================================================================================

    /// Builds the manifest JSON object from a modpack zip.
    ///
    /// <p>Public because the seed import derives a manifest straight from the picked
    /// {@code .mrpack} when the instance has no configured update source; the regression
    /// tests drive the same collector without the CLI.</p>
    public static JsonObject buildFromMrpack(Path mrpack, Map<String, String> options) throws IOException {
        String baseUrl = trimTrailingSlash(options.getOrDefault("base-url", DEFAULT_BASE_URL));
        Report report = new Report();
        Set<String> knownPaths = new HashSet<>();
        String packName = options.getOrDefault("pack-name", DEFAULT_PACK_NAME);
        String packVersion = options.getOrDefault("pack-version", DEFAULT_PACK_VERSION);

        try (ZipFile zip = new ZipFile(mrpack.toFile())) {
            ZipEntry indexEntry = zip.getEntry("modrinth.index.json");
            if (indexEntry != null) {
                try (InputStream in = zip.getInputStream(indexEntry)) {
                    JsonObject index = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    if (!options.containsKey("pack-name"))
                        packName = stringOr(stringGet(index, "name"), packName);
                    if (!options.containsKey("pack-version"))
                        packVersion = normalizePackVersion(stringOr(stringGet(index, "versionId"), packVersion));
                    collectIndexEntries(index, baseUrl, report, knownPaths);
                }
            } else {
                LOG.warning("No modrinth.index.json in " + mrpack + ", embedding every file");
            }
            collectOverrideEntries(zip, baseUrl, report, knownPaths);
        }

        return renderManifest(packName, packVersion, report.stdMods, report.commonFiles, report);
    }

    /// Converts the entries of {@code modrinth.index.json} (already covered paths of
    /// a LITE pack); entries without a routed path or a usable SHA-1 are skipped and reported.
    private static void collectIndexEntries(JsonObject index, String baseUrl,
                                            Report report, Set<String> knownPaths) {
        JsonElement files = index.get("files");
        if (files == null || !files.isJsonArray())
            return; // FULL pack: the payload lives in overrides, so an empty list is fine.
        for (JsonElement element : files.getAsJsonArray()) {
            if (!(element instanceof JsonObject file)) {
                report.skippedEntries++;
                continue;
            }
            String path = stringGet(file, "path");
            if (path != null)
                path = path.replace('\\', '/');

            String sha1 = indexSha1Of(file);
            if (path == null || sha1 == null || isUnsafeRelativePath(path)) {
                report.skip(path);
                continue;
            }
            if (isRpuPath(path)) {
                report.rpu(path);
                continue; // the in-game RPU client owns it, whatever folder it lives in
            }
            String route = channelOf(path);
            if (route == null) {
                report.unrouted(path);
                continue;
            }
            if (isExcludedModPath(path)) {
                report.excluded(path);
                continue;
            }
            if (!knownPaths.add(path)) {
                duplicateAndSkip(report, path, "index");
                continue; // A duplicate index file must not be emitted a second time.
            }

            List<String> urls = new ArrayList<>();
            filesArrayUrls(file, urls);
            urls.add(baseUrl + encodeUrlPath(path));

            report.add(route, path, sha1, longGet(file, "fileSize", "size"), urls);
        }
    }

    /// Walks the embedded {@code overrides/} payload; entries already covered by the
    /// index are skipped, because the sync engine downloads those from the CDN instead.
    private static void collectOverrideEntries(ZipFile zip, String baseUrl,
                                               Report report, Set<String> knownPaths) throws IOException {
        List<ZipEntry> entries = new ArrayList<>();
        try (Stream<? extends ZipEntry> stream = zip.stream()) {
            stream.forEach(entries::add);
        }
        entries.sort(Comparator.comparing(ZipEntry::getName));

        for (ZipEntry zipEntry : entries) {
            String name = zipEntry.getName();
            if (zipEntry.isDirectory() || !name.startsWith("overrides/"))
                continue;
            String relative = name.substring("overrides/".length()).replace('\\', '/');

            if (isSkippedPath(relative)) {
                report.skip(relative);
                continue;
            }
            if (isRpuPath(relative)) {
                report.rpu(relative);
                continue; // the in-game RPU client owns it, whatever folder it lives in
            }
            String route = channelOf(relative);
            if (route == null) {
                report.unrouted(relative);
                continue;
            }
            if (isExcludedModPath(relative)) {
                report.excluded(relative);
                continue;
            }
            if (!knownPaths.add(relative)) {
                report.coveredByIndex++;
                report.duplicates.add(relative);
                continue; // Already published by the index; syncing fetches it from the CDN.
            }

            Hash hash = computeHash(zip.getInputStream(zipEntry));
            report.add(route, relative, hash.sha1(), hash.size(), List.of(baseUrl + encodeUrlPath(relative)));
        }
    }

    private static JsonObject buildFromInstance(Path runDir, Map<String, String> options) throws IOException {
        String baseUrl = trimTrailingSlash(options.getOrDefault("base-url", DEFAULT_BASE_URL));
        Report report = new Report();
        collectDirectoryEntries(runDir.resolve("mods"), "mods", "std", baseUrl, report);
        for (String folder : COMMON_FOLDERS)
            collectDirectoryEntries(runDir.resolve(folder), folder, "common", baseUrl, report);
        for (String rootFile : COMMON_ROOT_FILES)
            collectRootFile(runDir.resolve(rootFile), rootFile, baseUrl, report);
        for (String folder : RPU_FOLDERS)
            collectRpuEntries(runDir.resolve(folder), folder, report);

        String packName = options.getOrDefault("pack-name", DEFAULT_PACK_NAME);
        String packVersion = normalizePackVersion(options.getOrDefault("pack-version", DEFAULT_PACK_VERSION));
        return renderManifest(packName, packVersion, report.stdMods, report.commonFiles, report);
    }

    /// Lists a folder the in-game RPU mod owns, only to report it; nothing is hashed and
    /// nothing reaches the manifest.
    private static void collectRpuEntries(Path folder, String prefix, Report report) throws IOException {
        if (!Files.isDirectory(folder))
            return;
        try (Stream<Path> stream = Files.walk(folder)) {
            stream.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(file -> report.rpu(prefix + "/" + folder.relativize(file).toString().replace('\\', '/')));
        }
    }

    private static void collectDirectoryEntries(Path folder, String prefix, String route, String baseUrl,
                                                Report report) throws IOException {
        if (!Files.isDirectory(folder)) {
            LOG.warning("Folder " + folder + " does not exist, skipped");
            return;
        }
        try (Stream<Path> stream = Files.walk(folder)) {
            List<Path> files = stream.filter(Files::isRegularFile).sorted().toList();
            for (Path file : files) {
                String relative = prefix + "/" + slashify(folder.relativize(file));
                if (isRpuPath(relative)) {
                    report.rpu(relative);
                    continue;
                }
                if (isUnsafeRelativePath(relative)) {
                    report.skip(relative);
                    continue;
                }
                if (isExcludedModPath(relative)) {
                    report.excluded(relative);
                    continue;
                }
                Hash hash = computeHash(Files.newInputStream(file));
                report.add(route, relative, hash.sha1(), hash.size(), List.of(baseUrl + encodeUrlPath(relative)));
            }
        }
    }

    private static void collectRootFile(Path file, String name, String baseUrl, Report report) throws IOException {
        if (!Files.isRegularFile(file)) {
            LOG.warning("File " + file + " does not exist, skipped");
            return;
        }
        Hash hash = computeHash(Files.newInputStream(file));
        report.add("common", name, hash.sha1(), hash.size(), List.of(baseUrl + encodeUrlPath(name)));
    }

    // ============================================================================================
    // Entry assembly
    // ============================================================================================

    private record Hash(String sha1, long size) {
    }

    private static Hash computeHash(InputStream in) throws IOException {
        MessageDigest digest = newSha1();
        long size = 0;
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = in.read(buffer)) != -1) {
            digest.update(buffer, 0, count);
            size += count;
        }
        in.close();
        return new Hash(HexFormat.of().formatHex(digest.digest()), size);
    }

    private static MessageDigest newSha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is always available", e);
        }
    }

    /// @return the manifest channel of a pack-relative path, or {@code null} when unrouted.
    private static @Nullable String channelOf(String relative) {
        String segment = relative.contains("/") ? relative.split("/", 2)[0] : relative;
        if ("mods".equals(segment))
            return "std";
        if (COMMON_FOLDERS.contains(segment) || COMMON_ROOT_FILES.contains(segment))
            return "common";
        return null;
    }

    /// @return whether the path belongs to a folder the in-game RPU mod updates itself.
    private static boolean isRpuPath(String relative) {
        return QLManifest.isRpuOwned(relative);
    }

    private static boolean isSkippedPath(String relative) {
        String segment = relative.contains("/") ? relative.split("/", 2)[0] : relative;
        return SKIPPED_ROOT_FOLDERS.contains(segment) || SKIPPED_ROOT_FILES.contains(relative);
    }

    private static boolean isExcludedModPath(String path) {
        if (!path.startsWith("mods/"))
            return false;
        String fileName = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return EXCLUDED_MOD_FILE_PREFIXES.stream().anyMatch(fileName::startsWith);
    }

    /// @return the optional-mod group of a file, or {@code null} when the file is mandatory.
    private static @Nullable String optionalGroupOf(String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (fileName.contains("voxy"))
            return "voxy";
        if (fileName.contains("distant") && fileName.contains("horizon"))
            return "dh";
        if (fileName.contains("axiom"))
            return "axiom";
        return null;
    }

    private static boolean isUnsafeRelativePath(String path) {
        return path.isBlank() || path.startsWith("/") || path.startsWith("\\")
                || path.contains("..") || path.contains(":");
    }

    private static String normalizePackVersion(String requested) {
        String version = requested == null || requested.isBlank() ? DEFAULT_PACK_VERSION : requested.trim();
        String lower = version.toLowerCase(Locale.ROOT);
        int dash = lower.lastIndexOf("-full");
        return dash > 0 ? version.substring(0, dash) : version;
    }

    /// Percent-encodes every segment of a pack-relative path so that it can serve as an
    /// HTTP URL path; keeps {@code /} separators intact.
    private static String encodeUrlPath(String path) {
        return Arrays.stream(path.split("/"))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }

    private static String slashify(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank())
            return DEFAULT_BASE_URL;
        return url.endsWith("/") ? url : url + "/";
    }

    // ============================================================================================
    // Rendering
    // ============================================================================================

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static String prettyPrint(JsonObject manifest) {
        return GSON.toJson(manifest) + "\n";
    }

    private static JsonObject renderManifest(String packName, String packVersion,
                                             List<Entry> stdMods, List<Entry> commonFiles, Report report) {
        JsonArray servers = new JsonArray();
        servers.add(serverObject("qinglan-main", "轻蓝服务器", "play.simpfun.cn:32650", "minecraft"));
        servers.add(serverObject("mtr-web", "MTR 地图", "play.simpfun.cn:14614", "mtr-web"));
        servers.add(serverObject("mtr-reporter", "MTR 数据", "play.simpfun.cn:19451", "mtr-reporter"));

        JsonObject channels = new JsonObject();
        channels.add("std", channelObject("官方包", stdMods, null));
        channels.add("common", channelObject("公共文件", null, commonFiles));

        JsonObject pack = new JsonObject();
        pack.addProperty("name", packName);
        pack.addProperty("version", packVersion);
        pack.addProperty("updatedAt", Instant.now().toString());

        JsonObject manifest = new JsonObject();
        manifest.addProperty("schema", 1);
        manifest.add("pack", pack);
        manifest.add("channels", channels);
        manifest.add("servers", servers);

        System.out.println(report.summary());
        return manifest;
    }

    private static JsonObject channelObject(String label, @Nullable List<Entry> mods, @Nullable List<Entry> files) {
        JsonArray modsArray = new JsonArray();
        if (mods != null)
            for (Entry entry : mods)
                modsArray.add(entry.toJson());
        JsonArray filesArray = new JsonArray();
        if (files != null)
            for (Entry entry : files)
                filesArray.add(entry.toJson());

        JsonObject channel = new JsonObject();
        channel.addProperty("label", label);
        channel.add("mods", modsArray);
        channel.add("files", filesArray);
        return channel;
    }

    private static JsonObject serverObject(String id, String name, String address, String kind) {
        JsonObject server = new JsonObject();
        server.addProperty("id", id);
        server.addProperty("name", name);
        server.addProperty("address", address);
        server.addProperty("kind", kind);
        return server;
    }

    /// A single managed file, mirroring {@link QLManifest.FileEntry}.
    @NotNullByDefault
    private record Entry(String path, String sha1, long size, List<String> urls, @Nullable String optional) {

        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("path", path);
            object.addProperty("sha1", sha1);
            object.addProperty("size", size);
            JsonArray urlArray = new JsonArray();
            for (String url : urls)
                urlArray.add(url);
            object.add("urls", urlArray);
            if (optional != null)
                object.addProperty("optional", optional);
            return object;
        }
    }

    // ============================================================================================
    // JSON parsing helpers (reader side of modrinth.index.json)
    // ============================================================================================

    private static @Nullable String stringGet(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static String stringOr(@Nullable String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static long longGet(JsonObject object, String primary, String fallback) {
        JsonElement element = object.get(primary);
        if (element == null || !element.isJsonPrimitive())
            element = object.get(fallback);
        if (element == null || !element.isJsonPrimitive())
            return 0L;
        try {
            return element.getAsLong();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /// @return the {@code hashes.sha1} field of an index entry when it is a 40-digit hex digest.
    private static @Nullable String indexSha1Of(JsonObject file) {
        JsonElement hashes = file.get("hashes");
        if (hashes == null || !hashes.isJsonObject())
            return null;
        String sha1 = stringGet(hashes.getAsJsonObject(), "sha1");
        return sha1 != null && sha1.length() == 40 ? sha1.toLowerCase(Locale.ROOT) : null;
    }

    private static void filesArrayUrls(JsonObject file, List<String> sink) {
        JsonElement downloads = file.get("downloads");
        if (downloads != null && downloads.isJsonArray())
            for (JsonElement download : downloads.getAsJsonArray())
                if (download.isJsonPrimitive())
                    sink.add(download.getAsString());
    }

    private static void duplicateAndSkip(Report report, String path, String source) {
        report.duplicates.add(path);
        report.skip(path);
        LOG.warning("Duplicate " + source + " path: " + path);
    }

    // ============================================================================================
    // Validation mode
    // ============================================================================================

    private static void validate(Path manifestFile) throws IOException {
        QLManifest manifest;
        try {
            manifest = QLManifest.fromJson(Files.readString(manifestFile, StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOG.error("INVALID manifest: " + e.getMessage());
            System.exit(2);
            return;
        }

        System.out.println("Valid manifest: " + manifest.packNameOrDefault() + " " + manifest.packVersionOrDefault()
                + " (updated at " + (manifest.pack == null ? "?" : manifest.pack.updatedAt) + ")");
        for (Map.Entry<String, QLManifest.Channel> entry : manifest.channelsOrThrow().entrySet()) {
            QLManifest.Channel channel = entry.getValue();
            System.out.println("  channel " + entry.getKey() + ": "
                    + channel.mods.size() + " mods, " + channel.files.size() + " files, "
                    + manifest.optionalGroups().size() + " optional group(s)");
        }
        System.out.println("  servers: " + (manifest.servers == null ? 0 : manifest.servers.size()));
    }

    // ============================================================================================
    // Reporting
    // ============================================================================================

    /// Mutable accumulation of manifest entries and of every anomaly observed while
    /// collecting; anomalies are only printed, the manifest still gets written.
    @NotNullByDefault
    private static final class Report {
        final List<Entry> stdMods = new ArrayList<>();
        final List<Entry> commonFiles = new ArrayList<>();
        final List<String> duplicates = new ArrayList<>();
        final List<String> unroutedPaths = new ArrayList<>();
        final List<String> excludedPaths = new ArrayList<>();
        final List<String> skippedPaths = new ArrayList<>();
        final List<String> rpuPaths = new ArrayList<>();
        int coveredByIndex;
        int skippedEntries;

        void add(String channel, String path, String sha1, long size, List<String> urls) {
            // Only mods join an optional group; configs of an optional runtime (e.g. DistantHorizons.toml)
            // are always published so that no stale config remains when the group is toggled.
            Entry entry = new Entry(path, sha1, size, List.copyOf(urls),
                    "std".equals(channel) ? optionalGroupOf(path) : null);
            if ("std".equals(channel)) {
                stdMods.add(entry);
            } else if ("common".equals(channel)) {
                commonFiles.add(entry);
            }
        }

        void unrouted(String path) {
            unroutedPaths.add(path);
            skippedEntries++;
        }

        /// Records a file the in-game RPU mod owns; it is not part of this manifest.
        void rpu(String path) {
            rpuPaths.add(path);
        }

        void excluded(String path) {
            excludedPaths.add(path);
            skippedEntries++;
        }

        void skip(@Nullable String path) {
            skippedPaths.add(path == null ? "<unknown>" : path);
            skippedEntries++;
        }

        String summary() {
            int optionalCount = 0;
            for (Entry entry : stdMods)
                if (entry.optional() != null)
                    optionalCount++;

            StringBuilder builder = new StringBuilder();
            builder.append("std=").append(stdMods.size())
                    .append(", common=").append(commonFiles.size())
                    .append(", optional=").append(optionalCount)
                    .append(", coveredByIndex=").append(coveredByIndex)
                    .append(", skipped=").append(skippedEntries)
                    .append(", duplicates=").append(duplicates.size());
            if (!rpuPaths.isEmpty())
                builder.append(", rpu=").append(rpuPaths.size());
            append("RPU (in-game mod, not published)", rpuPaths, builder);
            append("Unrouted", unroutedPaths, builder);
            append("Excluded", excludedPaths, builder);
            append("Skipped", skippedPaths, builder);
            append("Duplicate", duplicates, builder);
            return builder.toString();
        }

        private static void append(String title, List<String> paths, StringBuilder builder) {
            if (paths.isEmpty())
                return;
            builder.append('\n').append(title).append(paths.size() > 20 ? " (" + paths.size() + " files)" : ":\n");
            for (String path : paths) {
                builder.append("  - ").append(path).append('\n');
                if (paths.indexOf(path) == 19)
                    break; // Keep the console readable; the count above covers the rest.
            }
        }
    }
}
