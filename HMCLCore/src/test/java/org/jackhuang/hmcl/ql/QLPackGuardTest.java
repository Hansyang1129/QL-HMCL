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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for the gate that decides whether an instance may use the sync page: only the
/// pack's own instances carry the RPU mod and only those may be updated.
@NotNullByDefault
class QLPackGuardTest {

    private @TempDir Path gameDir;

    /// The instance shipped with the pack carries the RPU client and is syncable.
    @Test
    void recognisesThePacksRpuMod() throws IOException {
        Path mods = Files.createDirectories(gameDir.resolve("mods"));
        Files.writeString(mods.resolve("[C]QingLanRPUClient-1.0.0-updated.jar"), "x");
        Files.writeString(mods.resolve("fabric-api-0.92.jar"), "x");

        assertEquals("[C]QingLanRPUClient-1.0.0-updated.jar", QLPackGuard.findRpuMod(gameDir));
        assertTrue(QLPackGuard.isSyncable(gameDir));
    }

    /// The marker is case-insensitive, so a differently cased build still counts.
    @Test
    void markerIsCaseInsensitive() throws IOException {
        Path mods = Files.createDirectories(gameDir.resolve("mods"));
        Files.writeString(mods.resolve("qlrpuclient-QingLanRPUClient-1.1.0.jar"), "x");

        assertTrue(QLPackGuard.isSyncable(gameDir));
    }

    /// A mod that merely mentions "rpu" must not unlock the sync page.
    @Test
    void unrelatedRpuNamedModDoesNotCount() throws IOException {
        Path mods = Files.createDirectories(gameDir.resolve("mods"));
        Files.writeString(mods.resolve("rpubridge-1.0.jar"), "x");
        Files.writeString(mods.resolve("QLRPU-helper.jar"), "x");

        assertFalse(QLPackGuard.isSyncable(gameDir));
    }

    /// A personal instance without the RPU mod must never be updated.
    @Test
    void rejectsInstancesWithoutTheRpuMod() throws IOException {
        Path mods = Files.createDirectories(gameDir.resolve("mods"));
        Files.writeString(mods.resolve("sodium-0.5.13.jar"), "x");
        Files.writeString(mods.resolve("rpu-notes.txt"), "x");

        assertFalse(QLPackGuard.isSyncable(gameDir));
        assertNull(QLPackGuard.findRpuMod(gameDir));
    }

    /// A parked (disabled) RPU copy does not make the instance syncable: the updater is off.
    @Test
    void disabledRpuCopyDoesNotCount() throws IOException {
        Path mods = Files.createDirectories(gameDir.resolve("mods"));
        Files.writeString(mods.resolve("[C]QingLanRPUClient-1.0.0-updated.jar.disabled"), "x");

        assertFalse(QLPackGuard.isSyncable(gameDir));
    }

    /// An instance that was never installed has no mods folder at all.
    @Test
    void missingModsFolderIsNotSyncable() {
        assertFalse(QLPackGuard.isSyncable(gameDir));
        assertNull(QLPackGuard.findRpuMod(gameDir));
    }
}
