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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Decides whether an instance is allowed to use the Qinglan sync page.
 *
 * <p>The sync page is not a general "update this instance" button: only instances that
 * carry the pack's <b>RPU mod</b> (the in-game resource-pack updater, for example
 * {@code [C]QingLanRPUClient-1.0.0-updated.jar}) belong to the community pack and may be
 * updated by the launcher. Everything else is a personal instance and stays untouched --
 * mixing mods from the pack into someone's own world would be destructive.</p>
 */
@NotNullByDefault
public final class QLPackGuard {

    /// Marker in the file name of the RPU mod shipped with the pack; case-insensitive.
    public static final String RPU_MOD_MARKER = "rpu";

    private QLPackGuard() {
    }

    /**
     * Looks for the RPU mod in an instance's {@code mods} folder.
     *
     * @param gameDir the instance's run directory, e.g. {@code .minecraft/versions/Qinglan-2nd-RPU}
     * @return the file name of the enabled RPU mod, or {@code null} when the instance does not
     *         carry one (also when the only copy is parked as {@code *.jar.disabled})
     */
    public static @Nullable String findRpuMod(Path gameDir) {
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods))
            return null;
        try (Stream<Path> stream = Files.list(mods)) {
            return stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> {
                        String lower = name.toLowerCase(Locale.ROOT);
                        // A disabled copy does not count: the pack's updater is not running then.
                        return lower.endsWith(".jar") && lower.contains(RPU_MOD_MARKER);
                    })
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * @param gameDir the instance's run directory
     * @return whether the sync page may update this instance
     */
    public static boolean isSyncable(Path gameDir) {
        return findRpuMod(gameDir) != null;
    }
}
