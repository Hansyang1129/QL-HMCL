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
 * Applies a seed pack ({@code .mrpack}) onto an **existing** instance: it imports or
 * updates files, it does not install a new instance.
 *
 * <p>The pack's own index is the source of truth: every routed entry is either taken from
 * the embedded {@code overrides/} payload or downloaded from the entry's URLs, compared by
 * SHA-1 first so an already-current file costs nothing. Resource packs and the RPU client's
 * own store are skipped -- the in-game RPU client owns them -- while shaders, configs and
 * mods land exactly where sync would put them, and the resulting state is recorded so the
 * next regular sync continues from here.</p>
 */
@NotNullByDefault
public final class QLPackImporter {

    /// Logger for recoverable per-file failures; the outcome still reports them.
    private static final Logger LOG = Logger.getLogger(QLPackImporter.class.getName());

    /// Digest algorithm used by the pack index for content checks.
    private static final String SHA1 = "SHA-1";

    private QLPackImporter() {
    }

    /// Result of one import run.
    ///
    /// @param imported   files written (downloaded or copied from the overrides payload)
    /// @param unchanged  files already matching the pack's SHA-1
    /// @param renamed    files adopted by renaming the plain/disabled sibling
    /// @param skippedRpu pack entries the in-game RPU client owns
    /// @param unrouted   pack entries this launcher does not manage at all
    /// @param failed     entries that could not be written; {@code failedPaths} names them
    /// @param failedPaths paths of {@code failed} entries, for the UI message
    public record Outcome(int imported, int unchanged, int renamed, int skippedRpu, int unrouted,
                          int failed, @Unmodifiable List<String> failedPaths) {
    }

    /// One file this run intends to have on disk.
    ///
    /// @param channel     the manifest channel the entry belongs to
    /// @param logicalPath the manifest path ({@code mods/x.jar}, {@code config/y.toml}...)
    /// @param entry       the manifest entry carrying the expected SHA-1 and download URLs
    /// @param target      the absolute path the content must end up at
    private record PlannedFile(String channel, String logicalPath, QLManifest.FileEntry entry, Path target) {
    }

    /**
     * Imports or updates the given instance from a seed pack.
     *
     * @param gameDir    run directory of the existing instance
     * @param mrpack     the picked {@code .mrpack} file
     * @param manifestUrl update source to record for the instance; {@code null} keeps whatever
     *                    the instance already had (and leaves it without one when it had none)
     * @param listener   optional progress consumer receiving ({@code finished}, {@code total})
     * @return the outcome summary
     * @throws IOException when the pack cannot be read or the state cannot be persisted
     */
    public static Outcome applyToInstance(Path gameDir, Path mrpack, @Nullable String manifestUrl,
                                          @Nullable BiConsumer<Integer, Integer> listener) throws IOException {
        Objects.requireNonNull(gameDir, "gameDir");
        Objects.requireNonNull(mrpack, "mrpack");

        QLManifest manifest = QLManifest.fromJson(
                QLManifestTool.buildFromMrpack(mrpack, Map.of()).toString());

        QLSyncState existing;
        try {
            existing = QLSyncState.load(gameDir);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot load Qinglan state, starting from an empty one", e);
            existing = QLSyncState.empty();
        }
        Set<String> optionalGroups = new LinkedHashSet<>();

        List<PlannedFile> planned = new ArrayList<>();
        int skippedRpu = 0;
        int unrouted = 0;
        Path modsFolder = QLDirectoryLayout.modsDirectory(gameDir, QLManifest.CHANNEL_MODS,
                QLManifest.CHANNEL_MODS);
        for (QLManifest.FileEntry entry : manifest.channelOrThrow(QLManifest.CHANNEL_MODS).mods) {
            String logical = Objects.requireNonNull(entry.path, "manifest path");
            String raw = logical.substring(logical.indexOf('/', 1) + 1);
            if (entry.optional != null)
                optionalGroups.add(entry.optional);
            // A seed pack is applied exactly as shipped: a fresh instance has no optional
            // record yet, and parking the pack's own mods would silently change the pack.
            boolean enabled = true;
            planned.add(new PlannedFile(QLManifest.CHANNEL_MODS, logical, entry,
                    modsFolder.resolve(enabled ? raw : raw + QLConstants.DISABLED_SUFFIX)));
        }
        for (QLManifest.FileEntry entry : manifest.channelOrThrow(QLManifest.CHANNEL_COMMON).files) {
            String logical = Objects.requireNonNull(entry.path, "manifest path");
            if (QLManifest.isRpuOwned(logical)) {
                skippedRpu++;
                continue;
            }
            planned.add(new PlannedFile(QLManifest.CHANNEL_COMMON, logical, entry,
                    gameDir.resolve(logical.replace("/", gameDir.getFileSystem().getSeparator()))));
        }

        Map<String, Map<String, String>> tracked = new TreeMap<>();
        int imported = 0;
        int unchanged = 0;
        int renamed = 0;
        int failed = 0;
        List<String> failedPaths = new ArrayList<>();
        int total = planned.size();
        int finished = 0;

        try (ZipFile zip = new ZipFile(mrpack.toFile())) {
            for (PlannedFile file : planned) {
                String expected = Objects.requireNonNull(file.entry().sha1, "manifest sha1");
                try {
                    if (matches(file.target(), expected)) {
                        unchanged++;
                    } else if (adoptSibling(file.target(), expected)) {
                        renamed++;
                    } else if (!writeFile(zip, file, expected)) {
                        failed++;
                        failedPaths.add(file.logicalPath());
                        finished++;
                        notifyProgress(listener, finished, total);
                        continue;
                    } else {
                        imported++;
                    }
                    tracked.computeIfAbsent(file.channel(), key -> new TreeMap<>())
                            .put(file.logicalPath(), expected);
                } catch (IOException e) {
                    failed++;
                    failedPaths.add(file.logicalPath());
                    LOG.log(Level.WARNING, "Cannot apply " + file.logicalPath(), e);
                }
                finished++;
                notifyProgress(listener, finished, total);
            }
        }

        existing.activeChannel = QLManifest.CHANNEL_MODS;
        existing.enabledOptional = new ArrayList<>(optionalGroups); // every group ships enabled
        existing.replaceTracked(tracked);
        existing.packVersion = manifest.packVersionOrDefault();
        if (manifestUrl != null && !manifestUrl.isBlank())
            existing.manifestUrl = manifestUrl.trim();
        existing.save(gameDir);

        try {
            QLDblPack.scan(gameDir, manifest, QLManifest.CHANNEL_MODS, optionalGroups).save(gameDir);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Cannot regenerate the dblpack inventory", e);
        }

        return new Outcome(imported, unchanged, renamed, skippedRpu, unrouted, failed,
                List.copyOf(failedPaths));
    }

    /// Reports one finished file to the optional progress listener.
    private static void notifyProgress(@Nullable BiConsumer<Integer, Integer> listener, int finished, int total) {
        if (listener != null && total > 0)
            listener.accept(finished, total);
    }

    /// @return whether {@code file} exists and its SHA-1 equals the expected one
    private static boolean matches(Path file, String expectedSha1) throws IOException {
        return Files.isRegularFile(file) && DigestUtils.digestToString(SHA1, file).equals(expectedSha1);
    }

    /// Adopts the plain/disabled sibling of {@code target} when it already carries the bytes.
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
    /// @return whether the file was written; {@code false} when the pack carries neither the
    ///         payload nor a reachable download for it
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

    /// Checks a freshly written file against the pack hash, deleting it when it is wrong.
    ///
    /// @return whether the content matched
    private static boolean verify(Path file, String expectedSha1) throws IOException {
        if (matches(file, expectedSha1))
            return true;
        Files.deleteIfExists(file);
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

    /// Downloads one URL into the target through the launcher's network stack (proxy,
    /// timeouts and TLS trust all come from there), following redirects.
    private static void downloadTo(URI uri, Path target) throws IOException {
        URLConnection connection = NetworkUtils.createConnection(uri);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        if (connection instanceof HttpURLConnection http)
            connection = NetworkUtils.resolveConnection(http);
        // Stream straight to the temporary file: pack entries can be tens of megabytes and
        // buffering them in heap just to hash them afterwards is pointless.
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
}
