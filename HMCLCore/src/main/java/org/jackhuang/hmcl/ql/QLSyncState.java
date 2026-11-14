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

import com.google.gson.JsonParseException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
import org.jackhuang.hmcl.util.gson.JsonUtils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Persisted user state of a Qinglan instance, stored as {@code ql-manifest-state.json}.
///
/// <p>The state has two jobs. First, it remembers the files that the <em>previous</em>
/// manifest installed, so files that disappear from a newer manifest can be removed
/// without ever touching anything the user placed into the game directory manually.
/// Second, it stores the user's choices: the active mods channel and the enabled
/// optional-mod groups.</p>
@NotNullByDefault
public final class QLSyncState {

    private static final Logger LOG = Logger.getLogger(QLSyncState.class.getName());

    /// File name used to persist the state inside the game directory.
    public static final String STATE_FILE = "ql-manifest-state.json";

    /// Version of the modpack this state belongs to, as published in the applied manifest.
    public @Nullable String packVersion;

    /// Manifest URL discovered or entered by the user; reused by update checks.
    ///
    /// <p>Kept per instance so the whole Qinglan setup travels with the game directory.
    /// Blank or {@code null} falls back to {@link QLConstants#DEFAULT_MANIFEST_URL}.</p>
    public @Nullable String manifestUrl;

    /// Channel that is currently active: {@code std} or {@code pfm}.
    public String activeChannel = "std";

    /// Optional-mod groups currently enabled by the user.
    public List<String> enabledOptional = new ArrayList<>();

    /// Files that the last-applied manifest installed: channel name to logical path to SHA-1.
    public Map<String, Map<String, String>> tracked = new LinkedHashMap<>();

    private QLSyncState() {
    }

    /// @return a fresh, unpopulated state.
    public static QLSyncState empty() {
        return new QLSyncState();
    }

    /// @return an unmodifiable view of the tracked files per channel.
    public @UnmodifiableView Map<String, @UnmodifiableView Map<String, String>> trackMap() {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : tracked.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null)
                continue;
            result.put(entry.getKey(), Collections.unmodifiableMap(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    /// Replaces the whole tracked-file registry, dropping any channels whose map is empty.
    public void replaceTracked(Map<String, Map<String, String>> newTracked) {
        tracked.clear();
        for (Map.Entry<String, Map<String, String>> entry : newTracked.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty())
                tracked.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
    }

    /// @return an unmodifiable view of the SHA-1 digests of the tracked files of {@code channel}.
    public @UnmodifiableView Map<String, String> trackedOf(String channel) {
        Map<String, String> map = tracked.get(channel);
        return map == null ? Map.of() : Collections.unmodifiableMap(map);
    }

    /// Registers a parsed-file digest of a tracked file for {@code channel}.
    public void track(String channel, String logicalPath, String sha1) {
        tracked.computeIfAbsent(channel, k -> new HashMap<>()).put(logicalPath, sha1);
    }

    /// Untracks a file that is no longer managed by the manifest.
    public void untrack(String channel, String logicalPath) {
        Map<String, String> map = tracked.get(channel);
        if (map != null)
            map.remove(logicalPath);
    }

    /// @return {@code true} when {@code group} is an enabled optional-mod group.
    public boolean isOptionalEnabled(String group) {
        return enabledOptional.contains(group);
    }

    /// @return unmodifiable copy of the enabled optional groups.
    public @Unmodifiable Set<String> enabledOptionalGroups() {
        return Set.copyOf(enabledOptional);
    }

    /// Marks an optional group enabled or disabled by the user.
    public void setOptionalEnabled(String group, boolean enabled) {
        if (enabled) {
            if (!enabledOptional.contains(group))
                enabledOptional.add(group);
        } else {
            enabledOptional.remove(group);
        }
    }

    /// Loads the state for a game directory; returns an empty state when none exists yet.
    ///
    /// <p>Content truncated by a previous crash is tolerated: the corrupt file is parked
    /// aside for diagnosis and an empty state is loaded instead, so a broken
    /// {@code ql-manifest-state.json} can never brick every later sync.</p>
    public static QLSyncState load(Path gameDir) throws IOException {
        Path file = stateFile(gameDir);
        if (!Files.isRegularFile(file))
            return new QLSyncState();
        QLSyncState state = readState(file);
        if (state == null)
            return new QLSyncState();
        if (state.tracked == null)
            state.tracked = new LinkedHashMap<>();
        if (state.enabledOptional == null)
            state.enabledOptional = new ArrayList<>();
        if (state.activeChannel == null || state.activeChannel.isBlank())
            state.activeChannel = "std";
        return state;
    }

    /// @return the parsed state file content, or {@code null} when the content was corrupt
    /// and has been parked aside.
    private static @Nullable QLSyncState readState(Path file) throws IOException {
        try {
            return JsonUtils.fromJsonFile(file, QLSyncState.class);
        } catch (JsonParseException e) {
            LOG.log(Level.WARNING, "Corrupt sync state " + file + ", starting from an empty state", e);
            try {
                Files.move(file, file.resolveSibling(file.getFileName().toString() + ".corrupt"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailure) {
                // The original file stays in place so the failure is not silently swallowed.
                moveFailure.addSuppressed(e);
                throw moveFailure;
            }
            return null;
        }
    }

    /// Persists the state into the game directory.
    ///
    /// <p>The state is serialized to a temporary file that is atomically moved into place,
    /// so a crash mid-write can never leave a truncated {@link #STATE_FILE}; a leftover
    /// temporary file from a failed write is removed before the error propagates.</p>
    public void save(Path gameDir) throws IOException {
        Path file = stateFile(gameDir);
        if (tracked.containsKey(null))
            tracked.remove(null);
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try {
            JsonUtils.writeToJsonFile(tmp, this);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private static Path stateFile(Path gameDir) {
        return gameDir.resolve(STATE_FILE);
    }
}
