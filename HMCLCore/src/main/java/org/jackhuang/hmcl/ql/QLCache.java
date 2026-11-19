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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Small on-disk cache for the launcher's read-only web feeds (the forum and the MTR map API).
 *
 * <p>Each response is stored as one JSON file keyed by the request URL. A fresh entry is served
 * without touching the network; when the network fails the last known copy is served instead of
 * an error, so both pages keep working offline. Writes are best effort: a cache problem never
 * breaks a page.</p>
 */
@NotNullByDefault
public final class QLCache {

    /// Logger for cache read/write problems; they never propagate to the caller.
    private static final Logger LOG = Logger.getLogger(QLCache.class.getName());

    /// One fetched response.
    ///
    /// @param body      the response text
    /// @param fromCache whether it came from the cache instead of the network
    /// @param stale     whether it is a cache copy served because the network failed
    /// @param fetchedAt when the copy was originally fetched
    public record Response(String body, boolean fromCache, boolean stale, Instant fetchedAt) {
    }

    /// One stored entry, as serialized to disk.
    private record Entry(String url, String fetchedAt, String body) {
    }

    private static final Gson GSON = new Gson();

    /// Cache root; lazily created next to the launcher configuration.
    private static volatile @Nullable Path root;

    /// The response of the most recent {@link #fetch} call, for the page hint.
    private static volatile @Nullable Response last;

    private QLCache() {
    }

    /// Overrides the cache directory (tests use this).
    public static void setRoot(@Nullable Path newRoot) {
        root = newRoot;
    }

    /// @return the cache directory, created on demand
    public static Path root() {
        Path current = root;
        if (current == null) {
            current = Path.of(System.getProperty("user.home"), ".hmcl", "ql-cache");
            root = current;
        }
        try {
            Files.createDirectories(current);
        } catch (IOException e) {
            LOG.log(Level.FINE, "Cannot create the QL cache directory " + current, e);
        }
        return current;
    }

    /// @return the response of the most recent {@link #fetch}, or {@code null}
    public static @Nullable Response lastResponse() {
        return last;
    }

    /// Fetches a URL, serving a fresh cache copy when possible and a stale one when the
    /// network fails.
    ///
    /// @param uri    the request URL
    /// @param maxAge how long a cached copy counts as fresh
    /// @return the body plus where it came from
    /// @throws IOException when the network fails and no cached copy exists
    public static Response fetch(URI uri, Duration maxAge) throws IOException {
        Entry cached = read(uri);
        Instant now = Instant.now();
        if (cached != null) {
            Instant fetchedAt = parseInstant(cached.fetchedAt());
            if (fetchedAt != null && Duration.between(fetchedAt, now).compareTo(maxAge) <= 0) {
                Response response = new Response(cached.body(), true, false, fetchedAt);
                last = response;
                return response;
            }
        }
        try {
            String body = NetworkUtils.doGet(uri);
            write(uri, body);
            Response response = new Response(body, false, false, now);
            last = response;
            return response;
        } catch (IOException failure) {
            if (cached != null) {
                Instant fetchedAt = parseInstant(cached.fetchedAt());
                Response response = new Response(cached.body(), true, true,
                        fetchedAt == null ? now : fetchedAt);
                last = response;
                return response;
            }
            throw failure;
        }
    }

    /// @return the cached body regardless of age, or {@code null} when none is stored
    public static @Nullable String staleOrNull(URI uri) {
        Entry entry = read(uri);
        return entry == null ? null : entry.body();
    }

    /// @return the last stored body for {@code uri}, or {@code null}
    private static @Nullable Entry read(URI uri) {
        Path file = entryFile(uri);
        if (file == null || !Files.isRegularFile(file))
            return null;
        try {
            JsonObject object = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            if (object == null || !object.has("body"))
                return null;
            return new Entry(
                    object.has("url") ? object.get("url").getAsString() : uri.toString(),
                    object.has("fetchedAt") ? object.get("fetchedAt").getAsString() : "",
                    object.get("body").getAsString());
        } catch (IOException | JsonParseException | IllegalStateException e) {
            LOG.log(Level.FINE, "Ignoring the corrupt QL cache entry " + file, e);
            return null;
        }
    }

    /// Stores one body; failures are logged and ignored.
    private static void write(URI uri, String body) {
        Path file = entryFile(uri);
        if (file == null)
            return;
        try {
            Files.createDirectories(file.getParent());
            JsonObject object = new JsonObject();
            object.addProperty("url", uri.toString());
            object.addProperty("fetchedAt", Instant.now().toString());
            object.addProperty("body", body);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(object), StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.log(Level.FINE, "Cannot write the QL cache entry for " + uri, e);
        }
    }

    private static @Nullable Path entryFile(URI uri) {
        try {
            String key = DigestUtils.digestToString("SHA-1", uri.toString().getBytes(StandardCharsets.UTF_8));
            return root().resolve(key + ".json");
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Cannot key the QL cache for " + uri, e);
            return null;
        }
    }

    private static @Nullable Instant parseInstant(String text) {
        try {
            return text == null || text.isBlank() ? null : Instant.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
