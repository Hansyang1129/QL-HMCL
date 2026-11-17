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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Opt-in integration run against a real {@code .mrpack}: set {@code QL_TEST_MRPACK} to its
/// path. Without the variable the test is skipped, so CI stays offline.
@NotNullByDefault
public final class QLPackImporterRealPackTest {

    /// Scratch instance the real pack is applied to.
    private @TempDir Path gameDir;

    /// Applies the real pack onto an empty instance: every entry must land (overrides payload
    /// or verified download), resource packs must not appear, and the state must be recorded.
    @Test
    public void appliesTheRealPack() throws Exception {
        String configured = System.getenv("QL_TEST_MRPACK");
        assumeTrue(configured != null && !configured.isBlank(),
                "set QL_TEST_MRPACK to a real .mrpack to run this test");
        Path mrpack = Path.of(configured);
        assumeTrue(Files.isRegularFile(mrpack), "pack file not found: " + mrpack);

        QLPackImporter.Outcome outcome = QLPackImporter.applyToInstance(gameDir, mrpack, null, null);
        System.out.println("real pack outcome: " + outcome);

        assertEquals(0, outcome.failed(), () -> "failed files: " + outcome.failedPaths());
        assertTrue(outcome.imported() > 100, "the pack should write most of its files");
        assertFalse(Files.exists(gameDir.resolve("resourcepacks")),
                "resource packs belong to the in-game RPU client");
        assertFalse(Files.exists(gameDir.resolve("qlrpu")),
                "the launcher must never create the RPU client's store");

        QLSyncState state = QLSyncState.load(gameDir);
        assertEquals("26.9.1", state.packVersion);
        assertEquals(QLManifest.CHANNEL_MODS, state.activeChannel);
        assertFalse(state.trackedOf(QLManifest.CHANNEL_MODS).isEmpty());

        QLPackImporter.Outcome second = QLPackImporter.applyToInstance(gameDir, mrpack, null, null);
        assertEquals(0, second.imported(), "a second run must not rewrite anything");
        assertEquals(0, second.failed());
    }
}
