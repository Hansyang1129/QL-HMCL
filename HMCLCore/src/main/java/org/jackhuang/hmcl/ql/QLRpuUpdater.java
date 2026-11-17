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
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Refreshes the RPU client's own cache ({@code qlrpu/cache}) from the pack backend, so the
 * resource-pack release can be updated before the game starts.
 *
 * <p>The launcher only mirrors what the mod itself does: the signed manifest envelope is stored
 * verbatim as {@code current.json}, and missing objects are fetched as encrypted {@code *.obj}
 * files through the backend's download tickets, verified by their cipher SHA-256. Nothing is
 * decrypted or interpreted here -- the in-game RPU client owns that.</p>
 */
@NotNullByDefault
public final class QLRpuUpdater {

    /// Logger for recoverable per-object failures.
    private static final Logger LOG = Logger.getLogger(QLRpuUpdater.class.getName());

    /// Manifest envelope stored by the RPU client, inside its cache folder.
    private static final String CURRENT = "current.json";

    /// Cache folder the RPU client owns, relative to the instance directory.
    private static final String CACHE = "qlrpu/cache";

    private static final Gson GSON = new Gson();

    /// Objects per {@code /check} request; the release lists thousands and the backend
    /// rejects oversized bodies.
    private static final int CHECK_BATCH = 400;

    /// Objects per {@code /download-tickets} request.
    private static final int TICKET_BATCH = 200;

    private QLRpuUpdater() {
    }

    /// Result of a check or an update run.
    ///
    /// @param installed the release the cache currently holds; {@code null} when unknown
    /// @param latest    the release the backend publishes
    /// @param upToDate  whether both ids match
    /// @param objects   objects the release lists
    /// @param downloaded objects fetched in this run
    /// @param unchanged objects already cached
    /// @param failed    objects that could not be fetched
    /// @param failedIds ids of the failed objects
    public record Outcome(@Nullable String installed, String latest, boolean upToDate, int objects,
                          int downloaded, int unchanged, int failed,
                          @Unmodifiable List<String> failedIds) {
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

    /// Checks the backend for the newest resource-pack release without touching the cache.
    public static Outcome check(Path gameDir, String minecraftVersion) throws IOException {
        String modVersion = installedModVersion(gameDir);
        if (modVersion == null)
            throw new IOException("The RPU client mod is not installed in this instance");
        QLRpuClient client = QLRpuClient.open(minecraftVersion, modVersion);
        String latest = client.latestReleaseId();
        String installed = installedReleaseId(gameDir);
        return new Outcome(installed, latest, Objects.equals(installed, latest), 0, 0, 0, 0, List.of());
    }

    /// Refreshes the RPU cache to the newest release.
    ///
    /// @param gameDir          the instance's run directory
    /// @param minecraftVersion the instance's game version
    /// @param listener         optional progress consumer receiving ({@code finished}, {@code total})
    public static Outcome update(Path gameDir, String minecraftVersion,
                                 @Nullable BiConsumer<Integer, Integer> listener) throws IOException {
        String modVersion = installedModVersion(gameDir);
        if (modVersion == null)
            throw new IOException("The RPU client mod is not installed in this instance");
        QLRpuClient client = QLRpuClient.open(minecraftVersion, modVersion);
        String latest = client.latestReleaseId();
        String installed = installedReleaseId(gameDir);
        if (Objects.equals(installed, latest))
            return new Outcome(installed, latest, true, 0, 0, 0, 0, List.of());

        Path cache = gameDir.resolve(CACHE);
        Path objects = cache.resolve("objects");
        Files.createDirectories(objects);

        // The envelope is stored verbatim: the in-game client verifies its signature itself.
        String envelope = client.manifestEnvelope(latest);

        QLRpuClient.Manifest manifest = client.manifest(latest);
        List<QLRpuClient.Entry> entries = manifest.objects();
        java.util.Set<String> validIds = new java.util.HashSet<>();
        for (QLRpuClient.Entry entry : entries) {
            if (matches(objects.resolve(entry.objectId() + ".obj"), entry.cipherSha256()))
                validIds.add(entry.objectId());
        }
        // The check body carries every object of the release; a real pack has thousands of
        // them, so it is asked in batches (the backend rejects oversized bodies with HTTP 413).
        List<String> required = new ArrayList<>();
        for (int from = 0; from < entries.size(); from += CHECK_BATCH) {
            int to = Math.min(from + CHECK_BATCH, entries.size());
            required.addAll(client.requiredObjects(latest, entries.subList(from, to), validIds));
        }

        int total = required.size();
        int downloaded = 0;
        int unchanged = 0;
        int failed = 0;
        List<String> failedIds = new ArrayList<>();

        if (!required.isEmpty()) {
            String sourceId = null;
            for (Map<String, Object> source : client.sources(latest)) {
                Object id = source.get("id");
                Object available = source.get("available");
                if (id instanceof String text && !text.isBlank() && Boolean.TRUE.equals(available)) {
                    sourceId = text;
                    break;
                }
            }
            List<QLRpuClient.Ticket> tickets = new ArrayList<>();
            for (int from = 0; from < required.size(); from += TICKET_BATCH) {
                int to = Math.min(from + TICKET_BATCH, required.size());
                tickets.addAll(client.tickets(latest, sourceId, required.subList(from, to)));
            }
            int finished = 0;
            for (QLRpuClient.Ticket ticket : tickets) {
                QLRpuClient.Entry entry = entries.stream()
                        .filter(candidate -> candidate.objectId().equals(ticket.objectId()))
                        .findFirst().orElse(null);
                if (entry == null) {
                    failed++;
                    failedIds.add(ticket.objectId());
                } else {
                    Path target = objects.resolve(entry.objectId() + ".obj");
                    try {
                        if (matches(target, entry.cipherSha256())) {
                            unchanged++;
                        } else {
                            download(ticket.url(), target);
                            if (matches(target, entry.cipherSha256()))
                                downloaded++;
                            else {
                                Files.deleteIfExists(target);
                                failed++;
                                failedIds.add(ticket.objectId());
                            }
                        }
                    } catch (IOException e) {
                        failed++;
                        failedIds.add(ticket.objectId());
                        LOG.log(Level.WARNING, "Cannot fetch RPU object " + ticket.objectId(), e);
                    }
                }
                finished++;
                if (listener != null && total > 0)
                    listener.accept(finished, total);
            }
        }

        // Only advertise the new release once every object is in place: the in-game client
        // trusts current.json and would otherwise mount a half-downloaded pack.
        if (failed == 0)
            writeAtomically(cache.resolve(CURRENT), envelope.getBytes(StandardCharsets.UTF_8));

        return new Outcome(installed, latest, false, entries.size(), downloaded, unchanged, failed,
                List.copyOf(failedIds));
    }

    /// @return whether the file exists and its SHA-256 hex digest matches
    private static boolean matches(Path file, String sha256Hex) throws IOException {
        if (!Files.isRegularFile(file))
            return false;
        return DigestUtils.digestToString("SHA-256", file).equalsIgnoreCase(sha256Hex);
    }

    /// Fetches one ticket URL into the given object file, streaming through a temp file.
    private static void download(String url, Path target) throws IOException {
        URI uri = org.jackhuang.hmcl.util.io.NetworkUtils.toURIOrNull(url);
        if (uri == null)
            throw new IOException("Bad ticket URL: " + url);
        URLConnection connection = org.jackhuang.hmcl.util.io.NetworkUtils.createConnection(uri);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(120_000);
        connection.setRequestProperty("User-Agent", "QingLanRPU/1.0 protocol/1");
        if (connection instanceof HttpURLConnection http)
            connection = org.jackhuang.hmcl.util.io.NetworkUtils.resolveConnection(http);
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream in = connection.getInputStream()) {
            Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
        }
        moveReplacing(part, target);
    }

    /// Writes through a sibling temporary file, atomically when supported.
    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, content);
        moveReplacing(tmp, target);
    }

    /// Moves a finished file into place.
    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
