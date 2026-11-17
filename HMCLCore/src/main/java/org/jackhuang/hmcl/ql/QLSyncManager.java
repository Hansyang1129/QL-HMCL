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

import org.jackhuang.hmcl.task.AsyncTaskExecutor;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
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

/// Plans and executes the incremental update of a Qinglan instance towards a manifest.
///
/// <p>A sync run is always restartable and performs the following steps in order:</p>
/// <ol>
///     <li>recover the mods folder layout for the recorded active channel;</li>
///     <li>apply the requested channel switch, if any (folder renames only);</li>
///     <li>delete managed files that disappeared from the manifest;</li>
///     <li>reconcile every manifest entry with the disk: renames between
///     {@code *.jar} and {@code *.jar.disabled} where only the sibling exists, downloads
///     for everything else, with a fast SHA-1 skip when the bytes already match;</li>
///     <li>run all downloads in parallel through HMCL's downloader, trying each candidate
///     URL in order;</li>
///     <li>persist the resulting state, including the new pack version.</li>
/// </ol>
///
/// <p>Only files tracked by the previous sync state are ever deleted. Anything a user placed
/// into the game directory manually (own mods, screenshots, saves...) is never touched.</p>
///
/// <p><b>Resource packs and shaders are out of scope.</b> The pack updates those in game
/// through its RPU mod, so this engine ignores such manifest entries and, when an older
/// state still tracks them, <em>untracks</em> those files instead of deleting them: the
/// launcher simply stops managing them and the RPU mod keeps them current.</p>
@NotNullByDefault
public final class QLSyncManager {

    private static final Logger LOG = Logger.getLogger(QLSyncManager.class.getName());

    private static final String SHA1_ALGORITHM = "SHA-1";

    

    private final Path gameDir;
    private final QLManifest manifest;
    private final QLSyncState state;
    private final @Nullable BiConsumer<Integer, Integer> listener;

    private QLSyncManager(Path gameDir, QLManifest manifest, QLSyncState state,
                          @Nullable BiConsumer<Integer, Integer> listener) {
        this.gameDir = gameDir;
        this.manifest = manifest;
        this.state = state;
        this.listener = listener;
    }

    /// Runs a full synchronization of the instance towards {@code manifest}.
    ///
    /// @param gameDir        the game directory of the instance
    /// @param manifest       the validated remote manifest to apply
    /// @param targetChannel  the channel that should be active after the run; pass the recorded
    ///                       channel when no switch is requested
    /// @param optionalExtras requested enabled-state of optional groups; entries are applied on
    ///                       top of the persisted state, {@code null} keeps the persisted state
    /// @param listener       optional progress consumer receiving ({@code finished}, {@code total});
    ///                       one unit of work per manifest entry that was hashed; downloads do not
    ///                       report intermediate progress
    /// @return the outcome summary
    /// @throws IOException when a channel switch or repair fails, or when a download cannot
    ///                     be completed after trying every candidate URL
    public static SyncOutcome sync(Path gameDir, QLManifest manifest, String targetChannel,
                                   @Nullable Map<String, Boolean> optionalExtras,
                                   @Nullable BiConsumer<Integer, Integer> listener) throws IOException {
        Objects.requireNonNull(gameDir, "gameDir");
        Objects.requireNonNull(manifest, "manifest");
        QLSyncState state = QLSyncState.load(gameDir);
        QLSyncManager manager = new QLSyncManager(gameDir, manifest, state, listener);
        try {
            return manager.run(targetChannel, optionalExtras);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private SyncOutcome run(String targetChannel, @Nullable Map<String, Boolean> optionalExtras) throws IOException {
        SyncOutcome outcome = new SyncOutcome();

        // --- 1. Channel switch ----------------------------------------------------------------
        String marker = QLDirectoryLayout.detectActiveChannel(gameDir, state.activeChannel);
        String fromChannel = marker != null ? marker : (QLManifest.MODS_CHANNELS.contains(state.activeChannel) ? state.activeChannel : "std");
        String toChannel = QLManifest.MODS_CHANNELS.contains(targetChannel) ? targetChannel : fromChannel;

        if (!fromChannel.equals(toChannel)) {
            QLDirectoryLayout.switchChannel(gameDir, fromChannel, toChannel);
            outcome.channelSwitched = true;
        }
        state.activeChannel = toChannel;
        QLDirectoryLayout.recover(gameDir, toChannel);

        // --- 2. Optional group toggles ----------------------------------------------------------
        if (optionalExtras != null) {
            for (Map.Entry<String, Boolean> toggle : optionalExtras.entrySet())
                state.setOptionalEnabled(toggle.getKey(), toggle.getValue());
        }
        Set<String> enabledOptional = state.enabledOptionalGroups();

        // --- 3. Desired state --------------------------------------------------------------------
        List<PlannedFile> planned = collectDesiredFiles(enabledOptional);

        // --- 4. Purge managed files that are gone from the manifest ------------------------------
        Set<String> desiredKeys = new LinkedHashSet<>();
        for (PlannedFile file : planned)
            desiredKeys.add(channelKey(file.channel(), file.logicalPath()));

        for (Map.Entry<String, Map<String, String>> channelEntry : new LinkedHashMap<>(state.trackMap()).entrySet()) {
            String channel = channelEntry.getKey();
            for (Map.Entry<String, String> fileEntry : new LinkedHashMap<>(channelEntry.getValue()).entrySet()) {
                String logicalPath = fileEntry.getKey();
                if (desiredKeys.contains(channelKey(channel, logicalPath)))
                    continue;
                if (QLManifest.isRpuOwned(logicalPath) || isRetiredChannel(channel)) {
                    // Resource packs belong to the in-game RPU mod, and the retired pfm
                    // channel belongs to the user now: drop our claim, keep the bytes.
                    // replaceTracked() below finishes the handover.
                    LOG.info("Handing " + logicalPath + " over instead of deleting it");
                    outcome.handedOver++;
                    continue;
                }
                deleteTrackedFile(channel, logicalPath);
                outcome.deleted++;
            }
        }

        // --- 5. Reconcile every manifest entry with the disk --------------------------------------
        List<PlannedFile> unchanged = new ArrayList<>();
        List<PlannedFile> downloads = new ArrayList<>();
        int renames = 0;
        int total = planned.size();
        int finished = 0;
        for (PlannedFile file : planned) {
            ReconcileResult result = reconcile(file);
            renames += result.renames();
            if (result.neededDownload())
                downloads.add(file);
            else if (result == ReconcileResult.OK)
                unchanged.add(file); // RENAMEs were acted on, so they are not "unchanged".
            finished++;
            notifyProgress(finished, total);
        }
        outcome.renamed = renames;
        // Recorded before the downloads: a partial failure aborts the run, and the
        // summary should still report the files that were verified without action.
        outcome.unchanged = unchanged.size();

        // --- 6. Downloads --------------------------------------------------------------------------
        if (!downloads.isEmpty()) {
            // Keyed by the PlannedFile itself (record value semantics): several entries can share
            // the same display label (same file name in different folders/channels), so labels
            // must never be used as task keys.
            LinkedHashMap<PlannedFile, FileDownloadTask> downloadByFile = new LinkedHashMap<>();
            for (PlannedFile file : downloads) {
                List<URI> uris = new ArrayList<>();
                for (String url : file.entry().urls) {
                    // a malformed URL must surface as an IOException naming the
                    // file, not as an unchecked IllegalArgumentException (M4)
                    URI uri = NetworkUtils.toURIOrNull(url);
                    if (uri == null)
                        throw new IOException("Invalid download URL for " + file.logicalPath() + ": " + url);
                    uris.add(uri);
                }
                FileDownloadTask task = new FileDownloadTask(uris, file.physicalPath(),
                        new FileDownloadTask.IntegrityCheck(SHA1_ALGORITHM, Objects.requireNonNull(file.entry().sha1)));
                task.setSignificance(Task.TaskSignificance.MODERATE);
                task.setName(file.label());
                downloadByFile.put(file, task);
            }

            Task<Void> root = new Task<Void>() {
                @Override
                public void execute() throws Exception {
                    // All work is performed by dependents.
                }

                @Override
                public Collection<? extends Task<?>> getDependents() {
                    return List.copyOf(downloadByFile.values());
                }

                @Override
                public boolean isRelyingOnDependents() {
                    // Dependents are all downloads; we succeed even when one failed,
                    // so failures can be collected afterwards.
                    return false;
                }
            };

            boolean cancelled = !new AsyncTaskExecutor(root).test();
            for (Map.Entry<PlannedFile, FileDownloadTask> entry : downloadByFile.entrySet()) {
                FileDownloadTask task = entry.getValue();
                if (task.getState() == Task.TaskState.FAILED) {
                    Exception exception = task.getException();
                    if (exception instanceof java.util.concurrent.CancellationException) {
                        cancelled = true;
                        break;
                    }
                    LOG.log(Level.INFO, "Download failed: " + entry.getKey().label() + ", " + exception);
                    outcome.failedFiles.add(entry.getKey().label());
                } else if (task.getState() == Task.TaskState.SUCCEEDED || task.getState() == Task.TaskState.EXECUTED) {
                    outcome.downloaded++;
                } else {
                    LOG.log(Level.WARNING, "Download task " + task.getName() + " ended in unexpected state "
                            + task.getState() + (task.getException() == null ? "" : ", exception: " + task.getException()));
                }
            }
            if (cancelled) {
                outcome.cancelled = true;
                return outcome;
            }
            if (!outcome.failedFiles.isEmpty())
                return outcome; // Partial failure: the next run resumes by hashing again.
        }

        // --- 7. Bookkeeping -------------------------------------------------------------------------
        Map<String, Map<String, String>> newTracked = new TreeMap<>();
        for (PlannedFile file : planned) {
            String sha1 = Objects.requireNonNull(file.entry().sha1, "manifest sha1");
            newTracked.computeIfAbsent(file.channel(), k -> new LinkedHashMap<>())
                    .put(file.logicalPath(), sha1);
        }
        state.replaceTracked(newTracked);
        state.packVersion = manifest.packVersionOrDefault();
        state.save(gameDir);

        // Refresh the dblpack inventory so it describes the pack content now on disk.
        try {
            QLDblPack.scan(gameDir, manifest, toChannel, enabledOptional).save(gameDir);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Cannot refresh dblpack inventory", e);
        }

        outcome.packVersion = state.packVersion;
        return outcome;
    }

    private void notifyProgress(int finished, int total) {
        if (listener != null && total > 0)
            listener.accept(finished, total);
    }

    /// Builds the desired file set for the current channel and optional-mod configuration.
    private List<PlannedFile> collectDesiredFiles(Set<String> enabledOptional) {
        List<PlannedFile> planned = new ArrayList<>();
        for (String channel : QLManifest.MODS_CHANNELS) {
            Path modsFolder = QLDirectoryLayout.modsDirectory(gameDir, channel, state.activeChannel);
            // Every entry is planned, including disabled optional mods: those stay on disk
            // as `*.jar.disabled` instead of being deleted.
            for (QLManifest.FileEntry entry : manifest.channelOrThrow(channel).mods) {
                boolean enabled = entry.optional == null || enabledOptional.contains(entry.optional);
                String rawName = rawFileName(entry);
                Path target = modsFolder.resolve(enabled ? rawName : rawName + QLConstants.DISABLED_SUFFIX);
                planned.add(new PlannedFile(channel, entry.path, entry, target));
            }
        }
        for (QLManifest.FileEntry entry : manifest.channelOrThrow(QLManifest.CHANNEL_COMMON).files) {
            String logicalPath = Objects.requireNonNull(entry.path);
            if (QLManifest.isRpuOwned(logicalPath)) {
                // Published by an older tool: the RPU mod owns it, so the launcher must not
                // download it either (this is what keeps the 700 MB of packs off our back).
                LOG.fine("Ignoring resource-pack entry owned by the RPU mod: " + logicalPath);
                continue;
            }
            Path target = gameDir.resolve(Objects.requireNonNull(entry.path)
                    .replace("/", gameDir.getFileSystem().getSeparator()));
            planned.add(new PlannedFile(QLManifest.CHANNEL_COMMON, entry.path, entry, target));
        }
        return planned;
    }

    /// Reconciles a single manifest entry with the disk.
    ///
    /// <p>Handles the following cases:</p>
    /// <ul>
    ///     <li>the expected file matches the manifest SHA-1 — nothing to do;</li>
    ///     <li>the inverted-name sibling ({@code *.jar} vs {@code *.jar.disabled}) matches the
    ///     manifest SHA-1 — rename it into place, no download needed;</li>
    ///     <li>otherwise — download into the expected name.</li>
    /// </ul>
    private ReconcileResult reconcile(PlannedFile file) throws IOException {
        Path target = file.physicalPath();
        Path inverted = invertedPath(target);
        String expectedSha = Objects.requireNonNull(file.entry().sha1, "manifest sha1");

        if (Files.isRegularFile(target) && shaMatches(target, file.entry().size, expectedSha))
            return ReconcileResult.OK;

        if (Files.isRegularFile(inverted) && shaMatches(inverted, file.entry().size, expectedSha)) {
            moveFile(inverted, target);
            return ReconcileResult.RENAME;
        }

        return ReconcileResult.DOWNLOAD;
    }

    /// @return whether the channel no longer exists in this build (the retired {@code pfm});
    ///         its files stay with the user now that channel switching is gone.
    private static boolean isRetiredChannel(String channel) {
        return !QLManifest.CHANNEL_COMMON.equals(channel) && !QLManifest.MODS_CHANNELS.contains(channel);
    }

    /// Deletes a tracked file of the given channel; both naming variants are considered.
    private void deleteTrackedFile(String channel, String logicalPath) {
        Map<String, Path> candidates = new LinkedHashMap<>();
        Path folder = QLManifest.MODS_CHANNELS.contains(channel)
                ? QLDirectoryLayout.modsDirectory(gameDir, channel, state.activeChannel)
                : gameDir;
        String raw = logicalToRaw(logicalPath, channel);
        candidates.put("plain", folder.resolve(raw));
        candidates.put("disabled", folder.resolve(raw + QLConstants.DISABLED_SUFFIX));

        for (Map.Entry<String, Path> candidate : candidates.entrySet()) {
            Path path = candidate.getValue();
            if (!Files.exists(path))
                continue;
            try {
                Files.delete(path);
                LOG.info("Deleted file that is no longer in the manifest: " + path);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Cannot delete " + path, e);
            }
        }
    }

    private String logicalToRaw(String logicalPath, String channel) {
        if (QLManifest.MODS_CHANNELS.contains(channel)) {
            // Manifest paths look like "mods/Some.jar"; strip the "mods/" prefix.
            int slash = Objects.requireNonNull(logicalPath).indexOf('/', 1);
            return logicalPath.substring(slash + 1);
        }
        return logicalPath.replace("/", gameDir.getFileSystem().getSeparator());
    }

    private String channelKey(String channel, String logicalPath) {
        return channel + "::" + logicalPath;
    }

    private static String rawFileName(QLManifest.FileEntry entry) {
        String path = Objects.requireNonNull(entry.path);
        int slash = path.indexOf('/', 1);
        return path.substring(slash + 1);
    }

    /// @return {@code true} when the file matches {@code expectedSha}.
    ///
    /// <p>A declared size of {@code null} (only possible in hand-built manifests) or
    /// non-positive (rejected during manifest validation, tolerated defensively)
    /// skips the length precheck and is proven by digest alone, so a sizeless
    /// manifest degrades to slightly slower hashing instead of re-downloading
    /// every file that is actually up to date.</p>
    private static boolean shaMatches(Path file, @Nullable Long expectedSize, String expectedSha) throws IOException {
        if (expectedSize != null && expectedSize > 0 && Files.size(file) != expectedSize.longValue())
            return false;
        try {
            return DigestUtils.digestToString(SHA1_ALGORITHM, file).equals(expectedSha);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot hash " + file, e);
            return false;
        }
    }

    private static Path invertedPath(Path target) {
        String name = target.getFileName().toString();
        if (name.endsWith(QLConstants.DISABLED_SUFFIX))
            return target.resolveSibling(name.substring(0, name.length() - QLConstants.DISABLED_SUFFIX.length()));
        return target.resolveSibling(name + QLConstants.DISABLED_SUFFIX);
    }

    private static void moveFile(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        LOG.fine("Renamed " + source + " to " + target);
    }

    /// Result of checking a single manifest entry against the disk.
    private enum ReconcileResult {
        /// The expected file is already on disk with the correct SHA-1.
        OK(false, 0),
        /// The inverted-name sibling had the correct SHA-1 and was renamed into place.
        RENAME(false, 1),
        /// The file must be downloaded from remote storage.
        DOWNLOAD(true, 0);

        private final boolean neededDownload;
        private final int renames;

        ReconcileResult(boolean neededDownload, int renames) {
            this.neededDownload = neededDownload;
            this.renames = renames;
        }

        boolean neededDownload() {
            return neededDownload;
        }

        int renames() {
            return renames;
        }
    }

    /// A manifest file resolved to its concrete location on disk.
    private record PlannedFile(String channel, String logicalPath, QLManifest.FileEntry entry, Path physicalPath) {

        String label() {
            return (QLManifest.MODS_CHANNELS.contains(channel) ? channel + "/" : "")
                    + physicalPath.getFileName();
        }
    }

    /// Summary of one synchronization run.
    @NotNullByDefault
    public static final class SyncOutcome {

        /// Number of files downloaded from remote storage.
        public int downloaded;

        /// Number of files that were already up to date.
        public int unchanged;

        /// Number of managed files removed after disappearing from the manifest.
        public int deleted;

        /// Number of files the launcher stopped managing and left on disk because the
        /// in-game RPU mod owns their folder (resource packs, shaders).
        public int handedOver;

        /// Number of files renamed between {@code *.jar} and {@code *.jar.disabled}
        /// (and the number of channel folder renames is covered by {@link #channelSwitched}).
        public int renamed;

        /// Set of file labels whose download ultimately failed after every candidate URL.
        public Set<String> failedFiles = new LinkedHashSet<>();

        /// Whether the run was cancelled by the user.
        public boolean cancelled;

        /// Whether a mods channel switch was performed during this run.
        public boolean channelSwitched;

        /// The pack version declared by the applied manifest, or {@code null} when the run
        /// ended before bookkeeping (cancelled or failed downloads).
        public @Nullable String packVersion;
    }
}
