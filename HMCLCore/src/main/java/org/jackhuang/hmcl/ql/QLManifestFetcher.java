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

import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;

/// Fetches, parses and validates the remote Qinglan manifest.
@NotNullByDefault
public final class QLManifestFetcher {

    private QLManifestFetcher() {
    }

    /// Fetches the manifest and validates its schema.
    ///
    /// @param manifestUrl the manifest URL recorded for the instance; a blank value means the
    ///                    instance has no update source and is rejected instead of guessing one
    /// @return the validated manifest
    /// @throws IOException              when the network request fails or the response is invalid
    /// @throws IllegalArgumentException when the configured URL cannot be parsed
    /// @throws IllegalStateException    when the response is syntactically valid JSON but not
    ///                                  a manifest accepted by this launcher
    public static QLManifest fetch(String manifestUrl) throws IOException {
        String rawUrl = manifestUrl == null || manifestUrl.isBlank()
                ? QLConstants.DEFAULT_MANIFEST_URL : manifestUrl.trim();
        if (rawUrl.isBlank())
            throw new IOException("No update source configured for this instance");
        URI uri;
        try {
            uri = URI.create(NetworkUtils.addHttpsIfMissing(rawUrl));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid manifest URL: " + rawUrl, e);
        }
        try {
            return QLManifest.fromJson(NetworkUtils.doGet(uri));
        } catch (RuntimeException e) {
            throw new IOException("Received malformed manifest from " + uri, e);
        }
    }

    /// Creates a background task that fetches and validates the manifest.
    public static Task<QLManifest> createFetchTask(@Nullable String manifestUrl) {
        return Task.supplyAsync(Schedulers.io(), () -> fetch(manifestUrl));
    }
}
