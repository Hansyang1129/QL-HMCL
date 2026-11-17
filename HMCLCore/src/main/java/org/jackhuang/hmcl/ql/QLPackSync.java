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

import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The Qinglan update engine, driven by the pack's own {@code .mrpack}.
 *
 * <p>The update path is the pack file itself: local or remote, the {@code modrinth.index.json}
 * plus the embedded {@code overrides/} payload describe everything the instance should have.
 * The engine compares that against the instance by SHA-1 and only then downloads: matching
 * files cost nothing, stale ones are replaced, files the pack no longer lists are removed, and
 * every result is recorded in {@link QLSyncState} so the next run continues from here.</p>
 *
 * <p>Ownership: resource packs and the RPU client's own store ({@code qlrpu/}) belong to the
 * in-game RPU client and are never written or deleted here; shaders, configs and mods are the
 * launcher's. There is no custom manifest format involved any more.</p>
 */
@NotNullByDefault
public final class QLPackSync {

    /// Logger for recoverable per-file failures; the outcome still reports them.
    private static final Logger LOG = Logger.getLogger(QLPackSync.class.getName());

    /// Digest algorithm the pack index uses for content checks.
    private static final String SHA1 = "SHA-1";

    /// Directory inside the instance that caches a downloaded pack file.
    public static final String CACHE_DIR = ".ql-pack";

    private QLPackSync() {
    }

    /// Result of one run.
    ///
    /// @param downloaded paths written from a download or from the overrides payload
    /// @param unchanged  paths already matching the pack's SHA-1
    /// @param renamed    paths adopted by renaming the plain/disabled sibling
    /// @param deleted    paths the pack no longer lists and that were removed
    /// @param handedOver tracked paths skipped because the in-game RPU client owns them
    /// @param failed     paths that could not be written; {@code failedPaths} names them
    /// @param failedPaths the failed paths, for the UI message
    public record Outcome(int downloaded, int unchanged, int renamed, int deleted, int handedOver,
                          int failed, @Unmodifiable List<String> failedPaths) {
    }

    /// One file the instance is supposed to have after a run.
    ///
    /// @param channel     manifest channel of the entry
    /// @param logicalPath manifest path ({@code mods/x.jar}, {@code config/y.toml}...)
    /// @param entry       the pack entry with the expected SHA-1 and download URLs
    /// @param target      absolute path the content must end up at
    private record PlannedFile(String channel, String logicalPath, QLManifest.FileEntry entry, Path target) {
    }

    /// @return the {@code versionId} recorded in the pack's index, e.g. {@code 26.9.1}
    /// @throws IOException when the pack cannot be read or has no index
    public static String readPackVersion(Path mrpack) throws IOException {
        QLManifest manifest = QLManifest.fromJson(
                QLManifestTool.buildFromMrpack(mrpack, Map.of()).toString());
        return manifest.packVersionOrDefault();
    }

    /// @return the optional-mod groups the pack offers, in index order
    /// @throws IOException when the pack cannot be read
    public static @Unmodifiable Set<String> optionalGroups(Path mrpack) throws IOException {
        QLManifest manifest = QLManifest.fromJson(
                QLManifestTool.buildFromMrpack(mrpack, Map.of()).toString());
        Set<String> groups = new LinkedHashSet<>();
        for (QLManifest.FileEntry entry : manifest.channelOrThrow(QLManifest.CHANNEL_MODS).mods) {
            if (entry.optional != null)
                groups.add(entry.optional);
        }
        return Set.copyOf(groups);
    }

    /**
     * Applies a pack onto an instance: writes, replaces and removes files so the instance
     * matches the pack, without touching what the RPU client owns.
     *
     * @param gameDir  the instance's run directory
     * @param mrpack   the pack file, local
     * @param packUrl  update source to record for later runs; {@code null} keeps the recorded
     *                 one, a blank value is stored as blank
     * @param listener optional progress consumer receiving ({@code finished}, {@code total})
     * @return the outcome summary
     * @throws IOException when the pack cannot be read or the state cannot be persisted
     */
    public static Outcome sync(Path gameDir, Path mrpack, @Nullable String packUrl,
                               @Nullable BiConsumer<Integer, Integer> listener) throws IOException {
        Objects.requireNonNull(gameDir, "gameDir");
        Objects.requireNonNull(mrpack, "mrpack");

        QLManifest manifest = QLManifest.fromJson(
                QLManifestTool.buildFromMrpack(mrpack, Map.of()).toString());

        QLSyncState existing;
        try {
            existing = QLSyncState.load(gameDir);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot load the Qinglan state, starting from an empty one", e);
            existing = QLSyncState.empty();
        }
        // A fresh instance has no optional record yet: apply the pack as shipped. Once the
        // record exists, an absent group means the user disabled it.
        boolean respectsOptionalRecord = existing.optionalRecorded || !existing.enabledOptional.isEmpty();
        Set<String> enabledGroups = new LinkedHashSet<>();
        Set<String> allGroups = new LinkedHashSet<>();

        Path modsFolder = QLDirectoryLayout.modsDirectory(gameDir, QLManifest.CHANNEL_MODS,
                QLManifest.CHANNEL_MODS);
        List<PlannedFile> planned = new ArrayList<>();
        for (QLManifest.FileEntry entry : manifest.channelOrThrow(QLManifest.CHANNEL_MODS).mods) {
            String logical = Objects.requireNonNull(entry.path, "manifest path");
            String raw = logical.substring(logical.indexOf('/', 1) + 1);
            boolean enabled = true;
            if (entry.optional != null) {
                allGroups.add(entry.optional);
                enabled = !respectsOptionalRecord || existing.enabledOptional.contains(entry.optional);
                if (enabled)
                    enabledGroups.add(entry.optional);
            }
            planned.add(new PlannedFile(QLManifest.CHANNEL_MODS, logical, entry,
                    modsFolder.resolve(enabled ? raw : raw + QLConstants.DISABLED_SUFFIX)));
        }
        for (QLManifest.FileEntry entry : manifest.channelOrThrow(QLManifest.CHANNEL_COMMON).files) {
            String logical = Objects.requireNonNull(entry.path, "manifest path");
            if (QLManifest.isRpuOwned(logical))
                continue;
            planned.add(new PlannedFile(QLManifest.CHANNEL_COMMON, logical, entry,
                    gameDir.resolve(logical.replace("/", gameDir.getFileSystem().getSeparator()))));
        }

        Map<String, Map<String, String>> tracked = new TreeMap<>();
        Set<String> plannedPaths = new LinkedHashSet<>();
        int downloaded = 0;
        int unchanged = 0;
        int renamed = 0;
        int failed = 0;
        List<String> failedPaths = new ArrayList<>();
        int total = planned.size() + countTracked(existing);
        int finished = 0;

        try (ZipFile zip = new ZipFile(mrpack.toFile())) {
            for (PlannedFile file : planned) {
                plannedPaths.add(file.logicalPath());
                String expected = Objects.requireNonNull(file.entry().sha1, "manifest sha1");
                try {
                    if (matches(file.target(), expected)) {
                        unchanged++;
                    } else if (adoptSibling(file.target(), expected)) {
                        renamed++;
                    } else if (writeFile(zip, file, expected)) {
                        downloaded++;
                    } else {
                        failed++;
                        failedPaths.add(file.logicalPath());
                    }
                } catch (IOException e) {
                    failed++;
                    failedPaths.add(file.logicalPath());
                    LOG.log(Level.WARNING, "Cannot apply " + file.logicalPath(), e);
                }
                if (!failedPaths.contains(file.logicalPath()))
                    tracked.computeIfAbsent(file.channel(), key -> new TreeMap<>())
                            .put(file.logicalPath(), expected);
                finished++;
                notifyProgress(listener, finished, total);
            }
        }

        // Purge what the pack no longer lists. RPU-owned leftovers are only handed over.
        int deleted = 0;
        int handedOver = 0;
        Map<String, Map<String, String>> previous = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> channel : existing.trackMap().entrySet())
            previous.put(channel.getKey(), new LinkedHashMap<>(channel.getValue()));
        for (Map.Entry<String, Map<String, String>> channel : previous.entrySet()) {
            for (String logical : channel.getValue().keySet()) {
                if (plannedPaths.contains(logical))
                    continue;
                if (QLManifest.isRpuOwned(logical)) {
                    handedOver++;
                } else if (deleteManagedFile(gameDir, modsFolder, logical)) {
                    deleted++;
                }
                finished++;
                notifyProgress(listener, finished, total);
            }
        }

        existing.activeChannel = QLManifest.CHANNEL_MODS;
        existing.replaceTracked(tracked);
        existing.enabledOptional = new ArrayList<>(enabledGroups.isEmpty() ? allGroups : enabledGroups);
        existing.optionalRecorded = true;
        existing.packVersion = manifest.packVersionOrDefault();
        if (packUrl != null)
            existing.packUrl = packUrl.isBlank() ? null : packUrl.trim();
        existing.save(gameDir);

        try {
            QLDblPack.scan(gameDir, manifest, QLManifest.CHANNEL_MODS, existing.enabledOptionalGroups())
                    .save(gameDir);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Cannot regenerate the dblpack inventory", e);
        }

        return new Outcome(downloaded, unchanged, renamed, deleted, handedOver, failed,
                List.copyOf(failedPaths));
    }

    private static int countTracked(QLSyncState state) {
        int count = 0;
        for (Map<String, String> channel : state.trackMap().values())
            count += channel.size();
        return count;
    }

    private static void notifyProgress(@Nullable BiConsumer<Integer, Integer> listener, int finished, int total) {
        if (listener != null && total > 0)
            listener.accept(finished, total);
    }

    /// @return whether {@code file} exists and its SHA-1 equals the expected one
    private static boolean matches(Path file, String expectedSha1) throws IOException {
        return Files.isRegularFile(file) && DigestUtils.digestToString(SHA1, file).equals(expectedSha1);
    }

    /// Adopts the plain/disabled sibling of {@code target} when it already carries the bytes.
    ///
    /// @return whether the file was adopted by renaming the sibling
    private static boolean adoptSibling(Path target, String expectedSha1) throws IOException {
        String name = target.getFileName().toString();
        Path sibling = name.endsWith(QLConstants.DISABLED_SUFFIX)
                ? target.getParent().resolve(name.substring(0, name.length() - QLConstants.DISABLED_SUFFIX.length()))
                : target.getParent().resolve(name + QLConstants.DISABLED_SUFFIX);
        if (!matches(sibling, expectedSha1))
            return false;
        Files.createDirectories(target.getParent());
        moveReplacing(sibling, target);
        return true;
    }

    /// Writes one file from the overrides payload or from its download URLs.
    ///
    /// @return whether the file was written and verified
    private static boolean writeFile(ZipFile zip, PlannedFile file, String expectedSha1) throws IOException {
        Files.createDirectories(file.target().getParent());
        byte[] payload = readOverride(zip, file.logicalPath());
        if (payload != null) {
            writeAtomically(file.target(), payload);
            return verify(file.target(), expectedSha1);
        }
        for (String url : file.entry().urls) {
            URI uri = NetworkUtils.toURIOrNull(url);
            if (uri == null)
                continue;
            try {
                downloadTo(uri, file.target());
                if (verify(file.target(), expectedSha1))
                    return true;
                LOG.warning("Downloaded content does not match the pack hash: " + url);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Cannot download " + url, e);
            }
        }
        return false;
    }

    /// Reads an entry from the pack's {@code overrides/} payload.
    ///
    /// @return the bytes, or {@code null} when the pack does not embed this file
    private static byte @Nullable [] readOverride(ZipFile zip, String logicalPath) throws IOException {
        ZipEntry entry = zip.getEntry(logicalPath);
        if (entry == null)
            entry = zip.getEntry("overrides/" + logicalPath);
        if (entry == null || entry.isDirectory())
            return null;
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    /// Checks a freshly written file against the pack hash, deleting it when it is wrong.
    ///
    /// @return whether the content matched
    private static boolean verify(Path file, String expectedSha1) throws IOException {
        if (matches(file, expectedSha1))
            return true;
        Files.deleteIfExists(file);
        return false;
    }

    /// Downloads one URL into the target through the launcher's network stack (proxy,
    /// timeouts and TLS trust all come from there), following redirects.
    private static void downloadTo(URI uri, Path target) throws IOException {
        URLConnection connection = NetworkUtils.createConnection(uri);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        if (connection instanceof HttpURLConnection http)
            connection = NetworkUtils.resolveConnection(http);
        // Stream straight to the temporary file: pack entries can be tens of megabytes.
        Path temp = target.resolveSibling(target.getFileName() + ".qlimport");
        try (InputStream in = connection.getInputStream();
             OutputStream out = Files.newOutputStream(temp)) {
            in.transferTo(out);
        }
        moveReplacing(temp, target);
    }

    /// Writes through a sibling temporary file so a crash never leaves a half file behind.
    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".qlimport");
        try (OutputStream out = Files.newOutputStream(temp)) {
            out.write(content);
        }
        moveReplacing(temp, target);
    }

    /// Moves a finished temporary file over its target, atomically when the file system allows.
    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /// Deletes a file this launcher installed, whichever sibling form it currently has.
    ///
    /// @return whether a file was removed
    private static boolean deleteManagedFile(Path gameDir, Path modsFolder, String logicalPath) throws IOException {
        Path target;
        if (logicalPath.startsWith("mods/")) {
            String raw = logicalPath.substring("mods/".length());
            target = modsFolder.resolve(raw);
        } else {
            target = gameDir.resolve(logicalPath.replace("/", gameDir.getFileSystem().getSeparator()));
        }
        boolean removed = Files.deleteIfExists(target);
        String name = target.getFileName().toString();
        Path sibling = name.endsWith(QLConstants.DISABLED_SUFFIX)
                ? target.getParent().resolve(name.substring(0, name.length() - QLConstants.DISABLED_SUFFIX.length()))
                : target.getParent().resolve(name + QLConstants.DISABLED_SUFFIX);
        return Files.deleteIfExists(sibling) || removed;
    }
}
