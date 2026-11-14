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

import org.jackhuang.hmcl.util.DigestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Turns a game directory that was installed from a seed pack into a Qinglan instance.
///
/// <p>The modpack files themselves are unpacked by HMCL's native install tasks
/// ({@code ModrinthInstallTask} / {@code ModpackInstallTask}) which remain the single
/// source of truth for the installation itself. What this importer adds is the
/// <em>QLization</em> step:</p>
/// <ol>
///     <li>heal and stamp the per-channel mods folder layout;</li>
///     <li>hash every manifest entry against the unpacked files and record the ones
///     that already match into a fresh {@link QLSyncState}, so the next update check
///     only has to fetch what the seed pack lacks;</li>
///     <li>generate the {@code dblpack.json} inventory describing the imported pack;</li>
///     <li>persist the state.</li>
/// </ol>
///
/// <p>Files that are present but mismatch the manifest SHA-1 are deliberately left
/// untouched and left untracked — the next sync run re-downloads and overwrites them.</p>
@NotNullByDefault
public final class QLSeedImporter {

    private static final Logger LOG = Logger.getLogger(QLSeedImporter.class.getName());

    private QLSeedImporter() {
    }

    /// QLizes an unpacked seed pack: initializes the QL layout, state file and dblpack.
    ///
    /// @param gameDir         the game directory containing the unpacked overrides
    /// @param manifest        the validated manifest to reconcile against
    /// @param manifestUrl     manifest URL to record for later update checks
    /// @param targetChannel   the channel that should be active
    /// @param optionalExtras  optional-mod groups the user wants enabled right away,
    ///                        {@code null} keeps everything disabled
    /// @param listener        optional progress consumer receiving ({@code finished},
    ///                        {@code total}); one unit per hashed manifest entry
    /// @return the outcome summary
    /// @throws IOException when the layout mutation or state persistence fails
    public static SeedOutcome seedize(Path gameDir, QLManifest manifest, @Nullable String manifestUrl,
                                      String targetChannel, @Nullable Map<String, Boolean> optionalExtras,
                                      @Nullable BiConsumer<Integer, Integer> listener) throws IOException {
        Objects.requireNonNull(gameDir, "gameDir");
        Objects.requireNonNull(manifest, "manifest");
        String channel = QLManifest.MODS_CHANNELS.contains(targetChannel) ? targetChannel : "std";

        QLSyncState state = QLSyncState.empty();
        state.activeChannel = channel;
        state.manifestUrl = manifestUrl;
        if (optionalExtras != null) {
            for (Map.Entry<String, Boolean> toggle : optionalExtras.entrySet())
                state.setOptionalEnabled(toggle.getKey(), toggle.getValue());
        }
        Set<String> enabledOptional = state.enabledOptionalGroups();

        // Layout first: recover stamps the channel marker and heals any partial state.
        // A channel already stamped on disk wins over the seed target, so re-running
        // the importer on an instance that owns a concrete channel can never restamp
        // its folder or blur other content into it. On a fresh directory the seed
        // target decides, exactly like the original flow.
        String detected = QLDirectoryLayout.detectActiveChannel(gameDir, channel);
        QLDirectoryLayout.recover(gameDir, detected != null ? detected : channel);
        channel = detected != null ? detected : channel;
        state.activeChannel = channel;

        SeedOutcome outcome = new SeedOutcome();

        int total = 0;
        for (QLManifest.Channel modsChannel : List.of(manifest.channelOrThrow("std"), manifest.channelOrThrow("pfm"), manifest.channelOrThrow(QLManifest.CHANNEL_COMMON)))
            total += modsChannel.mods.size() + modsChannel.files.size();

        int finished = 0;

        Map<String, Map<String, String>> tracked = new TreeMap<>();

        // ---Mods channels: only folders that exist already can be seeded.------------------
        for (String modsChannel : QLManifest.MODS_CHANNELS) {
            Path folder = QLDirectoryLayout.modsDirectory(gameDir, modsChannel, channel);
            if (!Files.isDirectory(folder)) {
                total -= manifest.channelOrThrow(modsChannel).mods.size();
                continue; // the engine downloads the parked channels on the next sync
            }
            for (QLManifest.FileEntry entry : manifest.channelOrThrow(modsChannel).mods) {
                String raw = QLManifest.MODS_CHANNELS.contains(modsChannel) ? rawFileName(entry) : entry.path;
                boolean enabled = entry.optional == null || enabledOptional.contains(entry.optional);
                Path target = folder.resolve(enabled ? raw : raw + QLConstants.DISABLED_SUFFIX);
                SeedStatus status = reconcileForSeedSoftly(target, entry);
                if (status == SeedStatus.SEEDED)
                    tracked.computeIfAbsent(modsChannel, k -> new TreeMap<>()).put(entry.path, entry.sha1);
                record(outcome, status);
                finished++;
                notifyProgress(listener, finished, total);
            }
        }

        // ---Common channel: plain game-directory paths.---------------------------------
        for (QLManifest.FileEntry entry : manifest.channelOrThrow(QLManifest.CHANNEL_COMMON).files) {
            Path target = gameDir.resolve(Objects.requireNonNull(entry.path)
                    .replace("/", gameDir.getFileSystem().getSeparator()));
            SeedStatus status = reconcileForSeedSoftly(target, entry);
            if (status == SeedStatus.SEEDED)
                tracked.computeIfAbsent(QLManifest.CHANNEL_COMMON, k -> new TreeMap<>()).put(entry.path, entry.sha1);
            record(outcome, status);
            finished++;
            notifyProgress(listener, finished, total);
        }

        state.replaceTracked(tracked);
        state.packVersion = manifest.packVersionOrDefault();
        state.save(gameDir);

        // The dblpack inventory is best-effort: a failure must not abort the import.
        try {
            QLDblPack.scan(gameDir, manifest, channel, enabledOptional).save(gameDir);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Cannot generate dblpack inventory", e);
        }

        return outcome;
    }

    private static void record(SeedOutcome outcome, SeedStatus status) {
        switch (status) {
            case SEEDED:
                outcome.seeded++;
                break;
            case MISMATCH:
                outcome.mismatch++;
                break;
            case MISSING:
                outcome.missing++;
                break;
        }
    }

    private static void notifyProgress(@Nullable BiConsumer<Integer, Integer> listener, int finished, int total) {
        if (listener != null && total > 0)
            listener.accept(finished, total);
    }

    /// Same as {@link #reconcileForSeed} but tolerant of unreadable files: an
    /// entry whose content cannot be hashed is reported as a mismatch, so the
    /// next sync replaces it instead of the whole import getting aborted and
    /// its already-prepared results lost.
    private static SeedStatus reconcileForSeedSoftly(Path target, QLManifest.FileEntry entry) {
        try {
            return reconcileForSeed(target, entry);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot hash the seed file, a sync run will replace it: " + target, e);
            return SeedStatus.MISMATCH;
        }
    }

    private static SeedStatus reconcileForSeed(Path target, QLManifest.FileEntry entry) throws IOException {
        String expectedSha = Objects.requireNonNull(entry.sha1, "manifest sha1");
        if (!Files.isRegularFile(target))
            return SeedStatus.MISSING;
        // Seed files predate manifest validation, so a sizeless entry degrades to
        // a digest-only check instead of reporting a false mismatch.
        if ((entry.size == null || Files.size(target) == entry.size)
                && DigestUtils.digestToString("SHA-1", target).equals(expectedSha))
            return SeedStatus.SEEDED;
        LOG.warning("Seed file mismatch, waiting for the sync run to replace it: " + target);
        return SeedStatus.MISMATCH;
    }

    private static String rawFileName(QLManifest.FileEntry entry) {
        String path = Objects.requireNonNull(entry.path, "manifest path");
        int slash = path.indexOf('/', 1);
        return path.substring(slash + 1);
    }

    private enum SeedStatus {
        SEEDED, MISMATCH, MISSING
    }

    /// Summary of one seed import.
    @NotNullByDefault
    public static final class SeedOutcome {

        /// Number of manifest entries found on disk with the exact manifest SHA-1.
        public int seeded;

        /// Number of manifest entries present but with different content; a sync run replaces them.
        public int mismatch;

        /// Number of manifest entries absent from disk, typically the CDN files missing from a LITE seed.
        public int missing;
    }
}
