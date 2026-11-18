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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for the launcher side of the RPU interface: the trigger and the progress sink.
@NotNullByDefault
public final class QLRpuBridgeTest {

    /// Instance directory handed to the updater.
    private @TempDir Path gameDir;

    /// Without a registered updater the bridge refuses politely instead of guessing.
    @Test
    public void rejectsWhenNoUpdaterIsInstalled() {
        QLRpuBridge.install(null);
        assertFalse(QLRpuBridge.isInstalled());
        assertThrows(IllegalStateException.class,
                () -> QLRpuBridge.startUpdate(gameDir, (finished, total) -> { }));
    }

    /// A registered updater receives the run directory and its progress reaches the sink.
    @Test
    public void forwardsTriggerAndProgress() throws Exception {
        AtomicReference<Path> seen = new AtomicReference<>();
        QLRpuBridge.install((directory, progress) -> {
            seen.set(directory);
            progress.progress(3, 7);
            progress.progress(7, 7);
        });
        assertTrue(QLRpuBridge.isInstalled());

        long[] last = new long[2];
        QLRpuBridge.startUpdate(gameDir, (finished, total) -> {
            last[0] = finished;
            last[1] = total;
        });

        assertEquals(gameDir, seen.get());
        assertEquals(7, last[0]);
        assertEquals(7, last[1]);
        QLRpuBridge.install(null);
    }
}
