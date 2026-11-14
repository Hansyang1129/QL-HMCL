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

import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Locator for the single Qinglan instance a directory should be managed through.
///
/// The Qinglan fork only manages one instance per game directory: the one that
/// was installed or seeded from the Qinglan modpack. Every other instance that
/// may live next to it is left untouched.
@NotNullByDefault
public final class QLInstances {
    private QLInstances() {
    }

    /// Returns the Qinglan instance of the repository, or `null` when the directory holds none.
    ///
    /// An instance counts as Qinglan-owned when its run directory carries any of the
    /// QL state markers ({@code dblpack.json} or {@code ql-manifest-state.json}).
    ///
    /// @param repository the game repository to scan
    /// @return the id of the Qinglan instance, or `null` when there is none
    public static @Nullable String findQinglanInstance(HMCLGameRepository repository) {
        for (String id : repository.getDisplayVersions().map(org.jackhuang.hmcl.game.Version::getId).toList()) {
            if (isQinglanInstance(repository, id)) {
                return id;
            }
        }
        return null;
    }

    /// Returns whether the run directory of the instance carries a Qinglan state marker.
    ///
    /// Instances named with the Qinglan prefix are also accepted so that an install
    /// done by an older launcher (which never wrote the markers) can still be adopted.
    ///
    /// @param repository the game repository the instance belongs to
    /// @param instanceId the id of the instance to inspect
    /// @return whether the instance was created or seeded by the Qinglan fork
    public static boolean isQinglanInstance(HMCLGameRepository repository, String instanceId) {
        if (!repository.hasVersion(instanceId)) {
            return false;
        }
        var runDirectory = repository.getRunDirectory(instanceId);
        return instanceId.toLowerCase(java.util.Locale.ROOT).startsWith("qinglan")
                || java.nio.file.Files.isRegularFile(runDirectory.resolve(QLConstants.DBLPACK_FILE))
                || java.nio.file.Files.isRegularFile(runDirectory.resolve(QLSyncState.STATE_FILE));
    }
}
