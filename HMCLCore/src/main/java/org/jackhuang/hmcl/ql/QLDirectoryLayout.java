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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.logging.Logger;

/// Owns the physical layout of the per-channel mods folders inside a game directory.
///
/// <p>A Qinglan instance keeps one folder per mods channel. The channel that is currently
/// active is exposed to the game under the standard name {@code mods}; every other channel
/// is parked under {@code mods-&lt;channel&gt;} (for example {@code mods-std} and {@code mods-pfm}).
/// Switching channels only renames directories, which is instantaneous and never copies mod files.</p>
///
/// <p>Each channel folder carries a hidden marker file (the channel name) so that the active
/// channel can always be determined from disk alone, even without any launcher state.
/// The rename sequence of a channel switch is crashable; two partial states can be observed:</p>
/// <ul>
///     <li>after the first rename only ({@code mods} moved away): {@code mods} and the parked
///     folder of the target channel are missing, while the parked folder of the previous
///     channel appeared — recovery re-applies the next rename of the recorded switch;</li>
///     <li>after the second rename only: layout already consistent.</li>
/// </ul>
@NotNullByDefault
public final class QLDirectoryLayout {

    /// Name of the folder that is currently loaded by the game.
    public static final String ACTIVE_MODS_FOLDER = "mods";

    /// Hidden marker file written into every managed mods folder; contains the channel name.
    public static final String CHANNEL_MARKER = ".qinglan-channel";

    private static final Logger LOG = Logger.getLogger(QLDirectoryLayout.class.getName());

    private QLDirectoryLayout() {
    }

    /// @return the parked folder name of {@code channel}, e.g. {@code mods-std}.
    public static String parkedFolderName(String channel) {
        return ACTIVE_MODS_FOLDER + "-" + channel.toLowerCase(Locale.ROOT);
    }

    /// @return the physical folder holding the mods of {@code channel},
    ///         given {@code activeChannel} as the currently active one.
    public static Path modsDirectory(Path gameDir, String channel, String activeChannel) {
        if (channel.equals(activeChannel))
            return gameDir.resolve(ACTIVE_MODS_FOLDER);
        return gameDir.resolve(parkedFolderName(channel));
    }

    /// Writes (or refreshes) the channel marker inside a mods folder.
    ///
    /// @param modsFolder the mods folder to stamp
    /// @param channel    the channel the folder belongs to
    public static void stampChannel(Path modsFolder, String channel) throws IOException {
        Files.createDirectories(modsFolder);
        Files.writeString(modsFolder.resolve(CHANNEL_MARKER), channel, StandardCharsets.UTF_8);
    }

    /// Reads the channel marker inside a mods folder.
    ///
    /// @return the channel name stamped in the folder, or {@code null} when the folder has no marker.
    public static @Nullable String readChannelMarker(Path modsFolder) {
        Path marker = modsFolder.resolve(CHANNEL_MARKER);
        if (!Files.isRegularFile(marker))
            return null;
        try {
            String channel = Files.readString(marker, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
            return QLManifest.MODS_CHANNELS.contains(channel) ? channel : null;
        } catch (IOException e) {
            LOG.warning("Cannot read channel marker in " + modsFolder + ": " + e);
            return null;
        }
    }

    /// Determines the currently active channel from the marker inside the {@code mods} folder.
    ///
    /// @param gameDir  the game directory
    /// @param fallback channel to report when the {@code mods} folder exists without a marker,
    ///                 e.g. after a plain seed import
    /// @return the detected or fallback active channel, or {@code null} when no mods folder exists
    public static @Nullable String detectActiveChannel(Path gameDir, String fallback) {
        Path mods = gameDir.resolve(ACTIVE_MODS_FOLDER);
        if (!Files.isDirectory(mods))
            return null;
        String marker = readChannelMarker(mods);
        return marker != null ? marker : fallback;
    }

    /// Heals a game directory to a consistent layout for {@code recordedActiveChannel}.
    ///
    /// <p>A channel marker already stamped inside the {@code mods} folder wins over the
    /// recorded channel: the disk knows which content the concrete folder holds, so a
    /// stale launcher record must never restamp it and blur the two channels. Safe to
    /// call on every startup and before every sync; a consistent layout is left untouched.</p>
    ///
    /// @param gameDir   the game directory
    /// @param recorded  the channel recorded as active by the launcher state,
    ///                  or {@code null} when no state exists yet
    public static void recover(Path gameDir, @Nullable String recorded) throws IOException {
        String activeChannel = recorded != null && QLManifest.MODS_CHANNELS.contains(recorded) ? recorded : "std";

        Path active = gameDir.resolve(ACTIVE_MODS_FOLDER);
        boolean modsExists = Files.isDirectory(active);

        // The disk wins over the records: a stamped marker means the concrete
        // folder really is that channel, so a stale launcher record (or a seed
        // import mentioning another channel) must never restamp it — every
        // branch below heals the layout around the disk's truth then.
        String marker = modsExists ? readChannelMarker(active) : null;
        if (marker != null && !marker.equals(activeChannel)) {
            LOG.warning("The active mods folder is stamped " + marker
                    + " while the launcher recorded " + activeChannel + "; trusting the disk");
            activeChannel = marker;
        }

        boolean activeParkedExists = Files.isDirectory(gameDir.resolve(parkedFolderName(activeChannel)));

        if (modsExists && !activeParkedExists) {
            stampChannel(active, activeChannel);
            return;
        }

        if (!modsExists && activeParkedExists && hasChannelMarker(gameDir.resolve(parkedFolderName(activeChannel)))) {
            // Interrupted switch: the first rename already happened, re-apply the second one.
            moveDirectory(gameDir.resolve(parkedFolderName(activeChannel)), active);
            return;
        }

        if (!modsExists && !activeParkedExists && hasAnyParkedFolder(gameDir)) {
            // The state and the disk disagree entirely; promote whichever parked folder is stamped
            // with the recorded channel, otherwise the last stamped parked folder found.
            Path promote = findPromotableParkedFolder(gameDir, activeChannel);
            if (promote != null) {
                moveDirectory(promote, active);
                return;
            }
        }

        Files.createDirectories(active);
        stampChannel(active, activeChannel);
    }

    /// Switches the active mods channel by renaming folders and stamping markers.
    ///
    /// @param gameDir  the game directory
    /// @param from     the currently active channel name
    /// @param to       the channel that should become active
    public static void switchChannel(Path gameDir, String from, String to) throws IOException {
        if (from.equals(to))
            return;

        Path active = gameDir.resolve(ACTIVE_MODS_FOLDER);
        Path parkedFrom = gameDir.resolve(parkedFolderName(from));
        Path parkedTo = gameDir.resolve(parkedFolderName(to));

        Files.createDirectories(active);

        // 1. Park the current active folder under its own channel name.
        if (!isSamePath(active, parkedFrom)) {
            moveDirectory(active, parkedFrom);
            stampChannel(parkedFrom, from);
        }

        // 2. Promote the target channel folder (or create it empty; the sync engine fills it).
        if (Files.isDirectory(parkedTo)) {
            moveDirectory(parkedTo, active);
            stampChannel(active, to);
        } else if (!Files.isDirectory(active)) {
            Files.createDirectories(active);
            stampChannel(active, to);
        } else if (isSamePath(parkedFrom.toAbsolutePath().normalize(), active.toAbsolutePath().normalize())) {
            // Only possible when parkedFrom == active renames failed; keep folder and stamp instead.
            stampChannel(active, to);
        }
    }

    private static boolean hasChannelMarker(Path folder) {
        return readChannelMarker(folder) != null;
    }

    private static boolean hasAnyParkedFolder(Path gameDir) {
        for (String channel : QLManifest.MODS_CHANNELS) {
            if (Files.isDirectory(gameDir.resolve(parkedFolderName(channel))))
                return true;
        }
        return false;
    }

    private static @Nullable Path findPromotableParkedFolder(Path gameDir, String preferred) {
        Path preferredFolder = null;
        Path lastStampedFolder = null;
        for (String channel : QLManifest.MODS_CHANNELS) {
            Path folder = gameDir.resolve(parkedFolderName(channel));
            if (!Files.isDirectory(folder))
                continue;
            if (channel.equals(preferred))
                preferredFolder = folder;
            if (readChannelMarker(folder) != null)
                lastStampedFolder = folder;
        }
        if (preferredFolder != null && readChannelMarker(preferredFolder) != null)
            return preferredFolder;
        if (lastStampedFolder != null)
            return lastStampedFolder;
        // Fall back to the preferred folder even without a marker.
        return preferredFolder;
    }

    private static boolean isSamePath(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        if (isSamePath(source, target))
            return;
        if (Files.exists(target)) {
            LOG.warning("Target folder " + target + " already exists while moving " + source);
            throw new IOException("Cannot move " + source + " to existing directory " + target);
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }
}
