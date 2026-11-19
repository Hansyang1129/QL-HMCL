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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for the three RPU interface points: trigger, progress projection and log projection.
@NotNullByDefault
public final class QLRpuBridgeTest {

    /// Instance directory handed to the updater.
    private @TempDir Path gameDir;

    /// Without a subscribed client the bridge refuses politely instead of guessing.
    @Test
    public void rejectsWhenNoUpdaterIsInstalled() {
        QLRpuBridge.install(null);
        assertFalse(QLRpuBridge.isInstalled());
        assertThrows(IllegalStateException.class, () -> QLRpuBridge.startUpdate(gameDir, new SinkRecorder()));
    }

    /// A subscribed client receives the run directory and its status/progress/log reach the page.
    @Test
    public void forwardsTriggerStatusProgressAndLog() throws Exception {
        QLRpuBridge.install((directory, sink) -> {
            sink.status("checking", 42.0);
            sink.log("line one");
            sink.log("line two");
        });
        assertTrue(QLRpuBridge.isInstalled());

        SinkRecorder recorder = new SinkRecorder();
        QLRpuBridge.startUpdate(gameDir, recorder);

        assertEquals("checking", recorder.status.get());
        assertEquals(42.0, recorder.percent.get());
        assertEquals(List.of("line one", "line two"), recorder.lines);
        QLRpuBridge.install(null);
    }

    /// Minimal sink recorder used by the assertions above.
    private static final class SinkRecorder implements QLRpuBridge.Sink {
        /// Last status text.
        private final AtomicReference<String> status = new AtomicReference<>();

        /// Last percentage.
        private final AtomicReference<Double> percent = new AtomicReference<>();

        /// Every appended log line.
        private final List<String> lines = new ArrayList<>();

        @Override
        public void status(@org.jetbrains.annotations.Nullable String text, @org.jetbrains.annotations.Nullable Double progress) {
            status.set(text);
            percent.set(progress);
        }

        @Override
        public void log(String line) {
            lines.add(line);
        }
    }
}
