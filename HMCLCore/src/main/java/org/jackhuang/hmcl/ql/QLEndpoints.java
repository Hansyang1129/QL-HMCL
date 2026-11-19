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
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Deployment endpoints of this build, loaded from {@code ql-endpoints.json} next to the
 * launcher configuration ({@code %APPDATA%\.hmcl} on Windows).
 *
 * <p>The repository is a shell: <b>no server address is compiled in</b>. Without a config
 * file every accessor returns {@code null} or an empty list, and the pages show a "not
 * configured" state instead of sending requests anywhere.</p>
 */
@NotNullByDefault
public final class QLEndpoints {

    private static final Logger LOG = Logger.getLogger(QLEndpoints.class.getName());
    private static final Gson GSON = new GsonBuilder().create();

    /// One status probe endpoint of the deployment.
    ///
    /// @param name display name
    /// @param kind {@code slp} or {@code http}
    /// @param host host name or IP
    /// @param port TCP port
    /// @param url  optional HTTP URL used for the probe and for opening in a browser
    public record StatusEndpoint(String name, String kind, String host, int port, @Nullable String url) {
    }

    /// One Minecraft server entry published to clients.
    public record ServerEntry(String id, String name, String address, String kind) {
    }

    /// Parsed config file layout.
    private record Config(@Nullable String forumBase, @Nullable String mtrBase,
                          @Nullable String manifestBaseUrl, List<StatusEndpoint> status,
                          List<ServerEntry> servers) {
    }

    /// Cached config, {@code null} until first loaded.
    private static volatile @Nullable Config config;

    /// Test seam: overrides the config path.
    private static volatile @Nullable Path configPath;

    private QLEndpoints() {
    }

    /// @return the config file path currently in use
    public static Path configFile() {
        Path override = configPath;
        return override != null ? override
                : OperatingSystem.getWorkingDirectory("hmcl").resolve("ql-endpoints.json");
    }

    /// Points the loader at another file (tests) and drops the cached config.
    public static void setConfigPath(@Nullable Path path) {
        configPath = path;
        config = null;
    }

    /// Drops the cached config so the next accessor re-reads the file.
    public static void reload() {
        config = null;
    }

    /// @return the configured forum base URL, or {@code null} when the shell is unconfigured
    public static @Nullable String forumBase() {
        return current().forumBase();
    }

    /// @return the configured MTR map API base URL, or {@code null} when unconfigured
    public static @Nullable String mtrBase() {
        return current().mtrBase();
    }

    /// @return the configured manifest/resource base URL, or {@code null} when unconfigured
    public static @Nullable String manifestBaseUrl() {
        return current().manifestBaseUrl();
    }

    /// @return the configured status probes; empty when unconfigured
    public static @Unmodifiable List<StatusEndpoint> statusProbes() {
        return current().status();
    }

    /// @return the configured published servers; empty when unconfigured
    public static @Unmodifiable List<ServerEntry> servers() {
        return current().servers();
    }

    private static Config current() {
        Config loaded = config;
        if (loaded != null)
            return loaded;
        Config parsed = load();
        config = parsed;
        return parsed;
    }

    private static Config load() {
        Config empty = new Config(null, null, null, List.of(), List.of());
        Path file = configFile();
        if (!Files.isRegularFile(file))
            return empty;
        try {
            JsonObject root = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            if (root == null)
                return empty;
            List<StatusEndpoint> status = GSON.fromJson(root.get("status"),
                    new com.google.gson.reflect.TypeToken<List<StatusEndpoint>>() { }.getType());
            List<ServerEntry> servers = GSON.fromJson(root.get("servers"),
                    new com.google.gson.reflect.TypeToken<List<ServerEntry>>() { }.getType());
            return new Config(
                    stringOrNull(root, "forumBase"),
                    stringOrNull(root, "mtrBase"),
                    stringOrNull(root, "manifestBaseUrl"),
                    status == null ? List.of() : List.copyOf(status),
                    servers == null ? List.of() : List.copyOf(servers));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Cannot read the Qinglan endpoint config " + file
                    + "; the launcher stays unconfigured", e);
            return empty;
        }
    }

    private static @Nullable String stringOrNull(JsonObject root, String key) {
        if (!root.has(key) || root.get(key).isJsonNull())
            return null;
        String value = root.get(key).getAsString().trim();
        return value.isEmpty() ? null : value;
    }
}
