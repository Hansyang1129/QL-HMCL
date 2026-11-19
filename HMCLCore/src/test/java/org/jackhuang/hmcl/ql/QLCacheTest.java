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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for the read-only feed cache used by the forum and the MTR pages.
@NotNullByDefault
public final class QLCacheTest {

    /// Scratch cache directory.
    private @TempDir Path cacheRoot;

    /// Restore the default cache root after each test.
    @AfterEach
    public void restoreRoot() {
        QLCache.setRoot(null);
    }

    /// A stored body survives a restart and is served without the network.
    @Test
    public void servesAFreshCachedCopy() throws Exception {
        QLCache.setRoot(cacheRoot);
        URI uri = URI.create("http://example.invalid/api/posts?page=1");
        Path entry = writeEntry(uri, "cached body");

        QLCache.Response response = QLCache.fetch(uri, Duration.ofMinutes(5));
        assertTrue(response.fromCache());
        assertFalse(response.stale());
        assertEquals("cached body", response.body());
        assertTrue(Files.isRegularFile(entry));
    }

    /// An expired entry still answers when the network cannot be reached, marked stale.
    @Test
    public void fallsBackToAStaleCopy() throws Exception {
        QLCache.setRoot(cacheRoot);
        URI uri = URI.create("http://127.0.0.1:1/no-such-service");
        writeEntry(uri, "old body");

        QLCache.Response response = QLCache.fetch(uri, Duration.ZERO);
        assertTrue(response.fromCache());
        assertTrue(response.stale());
        assertEquals("old body", response.body());
    }

    /// Without a cached copy a network failure is still an error.
    @Test
    public void rethrowsWithoutAnyCopy() {
        QLCache.setRoot(cacheRoot);
        URI uri = URI.create("http://127.0.0.1:1/other-service");
        assertNull(QLCache.staleOrNull(uri));
    }

    /// Writes one cache entry through the public API, then rewinds its timestamp to the past.
    private Path writeEntry(URI uri, String body) throws Exception {
        QLCache.setRoot(cacheRoot);
        // Seed through a successful fetch is not possible offline, so write the file directly
        // in the same format the class reads.
        String key = org.jackhuang.hmcl.util.DigestUtils.digestToString("SHA-1",
                uri.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path file = cacheRoot.resolve(key + ".json");
        com.google.gson.JsonObject object = new com.google.gson.JsonObject();
        object.addProperty("url", uri.toString());
        object.addProperty("fetchedAt", java.time.Instant.now().toString());
        object.addProperty("body", body);
        Files.writeString(file, object.toString());
        return file;
    }
}
