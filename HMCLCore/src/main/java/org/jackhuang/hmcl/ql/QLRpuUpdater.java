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
import org.jackhuang.hmcl.util.DigestUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Read-only view of the RPU client's cache, used to tell whether the instance's resource
 * packs are current.
 *
 * <p>The update itself is deliberately not performed here: it belongs to the in-game RPU
 * client, which has its own download flow, its own progress screen and its own signature
 * policy. The launcher only compares what is installed with what the backend publishes and
 * then lets the mod do the work on the next launch.</p>
 */
@NotNullByDefault
public final class QLRpuUpdater {

    /// Logger for cache inspection problems.
    private static final Logger LOG = Logger.getLogger(QLRpuUpdater.class.getName());

    /// Manifest envelope stored by the RPU client, inside its cache folder.
    private static final String CURRENT = "current.json";

    /// Cache folder the RPU client owns, relative to the instance directory.
    private static final String CACHE = "qlrpu/cache";

    /// Objects per {@code /check} request; the backend caps the array at 100 entries.
    private static final int CHECK_BATCH = 100;

    private static final Gson GSON = new Gson();

    private QLRpuUpdater() {
    }

    /// What the cache currently holds compared with the published release.
    ///
    /// @param installed the release recorded in {@code qlrpu/cache/current.json}, or {@code null}
    /// @param latest    the release the backend publishes
    /// @param upToDate  whether the release matches and every cached object verifies
    /// @param objects   objects the release lists
    /// @param pending   objects still missing or damaged in the cache
    public record Outcome(@Nullable String installed, String latest, boolean upToDate, int objects,
                          int pending) {
    }

    /// @return the release id recorded in {@code qlrpu/cache/current.json}, or {@code null}
    ///         when the RPU client has not stored one yet
    public static @Nullable String installedReleaseId(Path gameDir) {
        Path current = gameDir.resolve(CACHE).resolve(CURRENT);
        if (!Files.isRegularFile(current))
            return null;
        try {
            JsonObject envelope = GSON.fromJson(Files.readString(current, StandardCharsets.UTF_8),
                    JsonObject.class);
            if (envelope == null || !envelope.has("payload"))
                return null;
            JsonObject payload = GSON.fromJson(new String(Base64.getDecoder().decode(
                    envelope.get("payload").getAsString()), StandardCharsets.UTF_8), JsonObject.class);
            return payload != null && payload.has("releaseId") ? payload.get("releaseId").getAsString() : null;
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Cannot read the RPU release from " + current, e);
            return null;
        }
    }

    /// @return the version of the installed RPU client mod, or {@code null} when absent
    public static @Nullable String installedModVersion(Path gameDir) {
        String name = QLPackGuard.findRpuMod(gameDir);
        if (name == null)
            return null;
        Path jar = gameDir.resolve("mods").resolve(name);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null)
                return null;
            try (InputStream in = zip.getInputStream(entry)) {
                JsonObject object = GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                        JsonObject.class);
                return object != null && object.has("version") ? object.get("version").getAsString() : null;
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Cannot read the RPU mod version from " + jar, e);
            return null;
        }
    }

    /// Compares the cache with the backend. When both release ids match the cached objects are
    /// verified as well, because an id alone says nothing about missing or corrupted files.
    ///
    /// @param gameDir          the instance's run directory
    /// @param minecraftVersion the instance's game version
    public static Outcome check(Path gameDir, String minecraftVersion) throws IOException {
        String modVersion = installedModVersion(gameDir);
        if (modVersion == null)
            throw new IOException("The RPU client mod is not installed in this instance");
        QLRpuClient client = QLRpuClient.open(minecraftVersion, modVersion);
        String latest = client.latestReleaseId();
        String installed = installedReleaseId(gameDir);
        if (!Objects.equals(installed, latest))
            return new Outcome(installed, latest, false, 0, 0);

        QLRpuClient.Manifest manifest = client.manifest(latest);
        List<QLRpuClient.Entry> entries = manifest.objects();
        Path objects = gameDir.resolve(CACHE).resolve("objects");
        Set<String> validIds = new HashSet<>();
        for (QLRpuClient.Entry entry : entries) {
            if (matches(objects.resolve(entry.objectId() + ".obj"), entry.cipherSha256()))
                validIds.add(entry.objectId());
        }
        int pending = 0;
        for (int from = 0; from < entries.size(); from += CHECK_BATCH) {
            int to = Math.min(from + CHECK_BATCH, entries.size());
            pending += client.requiredObjects(latest, entries.subList(from, to), validIds).size();
        }
        return new Outcome(installed, latest, pending == 0, entries.size(), pending);
    }

    /// @return whether the file exists and its SHA-256 hex digest matches
    private static boolean matches(Path file, String sha256Hex) throws IOException {
        return Files.isRegularFile(file)
                && DigestUtils.digestToString("SHA-256", file).equalsIgnoreCase(sha256Hex);
    }
}
