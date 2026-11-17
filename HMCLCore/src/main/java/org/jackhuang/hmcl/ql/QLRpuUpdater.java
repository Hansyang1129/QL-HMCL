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
import org.jackhuang.hmcl.util.io.NetworkUtils;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The RPU update pipeline, ported into the launcher from the in-game client so both follow the
 * exact same flow: session, release lookup, signed manifest, source selection, object check,
 * download tickets, encrypted object download, and only then the new {@code current.json}.
 *
 * <p>The launcher mirrors the mod's behaviour rather than inventing its own: the signed
 * envelope is stored verbatim, objects are cached as {@code objects/<objectId>.obj} (temp
 * {@code .part} while downloading), every object is verified against its cipher SHA-256, and
 * the release is only advertised once everything is present. Decryption stays with the mod.</p>
 */
@NotNullByDefault
public final class QLRpuUpdater {

    /// Logger for recoverable per-object failures.
    private static final Logger LOG = Logger.getLogger(QLRpuUpdater.class.getName());

    /// Manifest envelope stored by the RPU client, inside its cache folder.
    private static final String CURRENT = "current.json";

    /// Cache folder the RPU client owns, relative to the instance directory.
    private static final String CACHE = "qlrpu/cache";

    /// Objects per {@code /check} request; the backend caps the array at 100 entries.
    private static final int CHECK_BATCH = 100;

    /// Objects per {@code /download-tickets} request.
    private static final int TICKET_BATCH = 100;

    private static final Gson GSON = new Gson();

    private QLRpuUpdater() {
    }

    /// State of the resource-pack release relative to the cache.
    ///
    /// @param installed   the release recorded in {@code qlrpu/cache/current.json}
    /// @param latest      the release the backend publishes
    /// @param upToDate    whether the release matches and every cached object verifies
    /// @param objects     objects the release lists
    /// @param pending     objects still missing or damaged in the cache
    /// @param downloaded  objects fetched by this run
    /// @param unchanged   objects already cached and valid
    /// @param failed      objects that could not be fetched
    /// @param failedIds   ids of the failed objects
    public record Outcome(@Nullable String installed, String latest, boolean upToDate, int objects,
                          int pending, int downloaded, int unchanged, int failed,
                          @Unmodifiable List<String> failedIds) {
    }

    /// @return the release id recorded in {@code qlrpu/cache/current.json}, or {@code null}
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

    /// Compares the cache with the backend, verifying cached objects when the release matches.
    public static Outcome check(Path gameDir, String minecraftVersion) throws IOException {
        QLRpuClient client = openClient(gameDir, minecraftVersion);
        String latest = client.latestReleaseId();
        String installed = installedReleaseId(gameDir);
        if (!Objects.equals(installed, latest))
            return new Outcome(installed, latest, false, 0, 0, 0, 0, 0, List.of());

        QLRpuClient.Manifest manifest = client.manifest(latest);
        List<QLRpuClient.Entry> entries = manifest.objects();
        Set<String> validIds = validObjects(gameDir, entries);
        int pending = requiredObjects(client, latest, entries, validIds).size();
        return new Outcome(installed, latest, pending == 0, entries.size(), pending, 0, 0, 0, List.of());
    }

    /// Runs the full update flow, mirroring the in-game client's phases and progress.
    ///
    /// @param gameDir          the instance's run directory
    /// @param minecraftVersion the instance's game version
    /// @param listener         optional progress consumer receiving ({@code finished}, {@code total})
    public static Outcome update(Path gameDir, String minecraftVersion,
                                 @Nullable BiConsumer<Integer, Integer> listener) throws IOException {
        QLRpuClient client = openClient(gameDir, minecraftVersion);
        String latest = client.latestReleaseId();
        String installed = installedReleaseId(gameDir);

        String envelope = client.manifestEnvelope(latest);
        QLRpuClient.Manifest manifest = client.manifest(latest);
        List<QLRpuClient.Entry> entries = manifest.objects();
        Path cache = gameDir.resolve(CACHE);
        Path objects = cache.resolve("objects");
        Files.createDirectories(objects);

        Set<String> validIds = validObjects(gameDir, entries);
        List<String> required = requiredObjects(client, latest, entries, validIds);
        if (required.isEmpty()) {
            // Nothing missing: the release is already complete, just advertise it.
            if (!Objects.equals(installed, latest))
                writeAtomically(cache.resolve(CURRENT), envelope.getBytes(StandardCharsets.UTF_8));
            return new Outcome(installed, latest, true, entries.size(), 0, 0, entries.size(), 0, List.of());
        }

        int total = required.size();
        int finished = 0;
        int downloaded = 0;
        int failed = 0;
        List<String> failedIds = new ArrayList<>();

        String sourceId = pickSource(client, latest);
        List<QLRpuClient.Ticket> tickets = new ArrayList<>();
        for (int from = 0; from < required.size(); from += TICKET_BATCH) {
            int to = Math.min(from + TICKET_BATCH, required.size());
            tickets.addAll(client.tickets(latest, sourceId, required.subList(from, to)));
        }

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
                    download(ticket.url(), target);
                    if (matches(target, entry.cipherSha256())) {
                        downloaded++;
                    } else {
                        Files.deleteIfExists(target);
                        failed++;
                        failedIds.add(ticket.objectId());
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

        // Advertise the release only when every object verified: the in-game client trusts this
        // file and would otherwise mount a half-downloaded pack.
        if (failed == 0)
            writeAtomically(cache.resolve(CURRENT), envelope.getBytes(StandardCharsets.UTF_8));

        return new Outcome(installed, latest, false, entries.size(), failed,
                downloaded, entries.size() - required.size(), failed, List.copyOf(failedIds));
    }

    private static QLRpuClient openClient(Path gameDir, String minecraftVersion) throws IOException {
        String modVersion = installedModVersion(gameDir);
        if (modVersion == null)
            throw new IOException("The RPU client mod is not installed in this instance");
        return QLRpuClient.open(minecraftVersion, modVersion);
    }

    /// @return ids of the cached objects whose cipher SHA-256 still matches
    private static Set<String> validObjects(Path gameDir, List<QLRpuClient.Entry> entries) throws IOException {
        Path objects = gameDir.resolve(CACHE).resolve("objects");
        Set<String> validIds = new HashSet<>();
        for (QLRpuClient.Entry entry : entries) {
            if (matches(objects.resolve(entry.objectId() + ".obj"), entry.cipherSha256()))
                validIds.add(entry.objectId());
        }
        return validIds;
    }

    /// Asks the backend which objects are still missing, in batches of {@link #CHECK_BATCH}.
    private static List<String> requiredObjects(QLRpuClient client, String releaseId,
                                                List<QLRpuClient.Entry> entries,
                                                Set<String> validIds) throws IOException {
        List<String> required = new ArrayList<>();
        for (int from = 0; from < entries.size(); from += CHECK_BATCH) {
            int to = Math.min(from + CHECK_BATCH, entries.size());
            required.addAll(client.requiredObjects(releaseId, entries.subList(from, to), validIds));
        }
        return required;
    }

    /// @return the id of the first available CDN source, or {@code null} when none is listed
    private static @Nullable String pickSource(QLRpuClient client, String releaseId) throws IOException {
        for (Map<String, Object> source : client.sources(releaseId)) {
            Object id = source.get("id");
            Object available = source.get("available");
            if (id instanceof String text && !text.isBlank() && Boolean.TRUE.equals(available))
                return text;
        }
        return null;
    }

    /// @return whether the file exists and its SHA-256 hex digest matches
    private static boolean matches(Path file, String sha256Hex) throws IOException {
        return Files.isRegularFile(file)
                && DigestUtils.digestToString("SHA-256", file).equalsIgnoreCase(sha256Hex);
    }

    /// Fetches one ticket URL into the given object file, streaming through a temp file.
    private static void download(String url, Path target) throws IOException {
        URI uri = NetworkUtils.toURIOrNull(url);
        if (uri == null)
            throw new IOException("Bad ticket URL: " + url);
        URLConnection connection = NetworkUtils.createConnection(uri);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(120_000);
        connection.setRequestProperty("User-Agent", "QingLanRPU/1.0 protocol/1");
        if (connection instanceof HttpURLConnection http)
            connection = NetworkUtils.resolveConnection(http);
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
