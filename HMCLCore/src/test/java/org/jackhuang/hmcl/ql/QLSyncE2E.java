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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.modpack.ModpackInstallTask;
import org.jackhuang.hmcl.task.AsyncTaskExecutor;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.CacheRepository;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// End-to-end driver of {@link QLSyncManager} and {@link QLSeedImporter} against a real
/// Qinglan modpack.
///
/// <p>It serves the payload of a FULL {@code .mrpack} over a local HTTP server, rewrites
/// the manifest produced by {@link QLManifestTool} (or any equivalent) onto that server,
/// and then drives several sync scenarios against a scratch instance:</p>
///
/// <ol>
///     <li>fresh install with the {@code dh} optional group enabled;</li>
///     <li>idempotent re-run (every file must hash-match, zero downloads);</li>
///     <li>disable the {@code dh} group (reversethe {@code *.jar.disabled} rename);</li>
///     <li>switch to the {@code pfm} channel (folder renames only);</li>
///     <li>switch back to {@code std} and re-enable {@code dh};</li>
///     <li>optional: seed-import the FULL pack natively, then zero-download sync;</li>
///     <li>optional: seed-import the LITE pack, then let sync fetch the missing CDN mods.</li>
/// </ol>
///
/// <p>Run it via Gradle (arguments are split on whitespace, so avoid spaces in paths):</p>
///
/// <pre>{@code
/// gradlew :HMCLCore:runQlSyncE2E -PsyncE2EArgs="C:/temp/pack-full.mrpack C:/temp/manifest-full.json C:/temp/e2e [C:/temp/pack-lite.mrpack C:/temp/manifest-lite.json]"
/// }</pre>
///
/// <p>Sync scenarios A–E use the FULL pack payload exclusively. The optional seed scenarios
/// unpack a pack natively with HMCL's installer, {@link QLSeedImporter#seedize} it and let a
/// regular sync fill the gap; both packs carry the same mod files, so the LITE seed scenario
/// can fetch its missing mods from the FULL zip payload.</p>
///
/// <p>The same scenarios also run as a JUnit test ({@link #runsScenariosWhenFixturesAreSupplied()})
/// that skips itself when no fixtures are supplied, so the suite executes this entry point
/// in CI without making the fixtures mandatory.</p>
@NotNullByDefault
public final class QLSyncE2E {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private QLSyncE2E() {
    }

    /// Runs the E2E scenarios from JUnit when fixture paths are supplied through the
    /// {@code ql.syncE2EArgs} system property (same three-or-five whitespace-separated
    /// arguments as the Gradle driver); without fixtures the test skips itself.
    @Test
    public void runsScenariosWhenFixturesAreSupplied() throws Exception {
        String property = System.getProperty("ql.syncE2EArgs", "");
        assumeTrue(!property.isBlank(),
                "set ql.syncE2EArgs to <full.mrpack> <full.json> <workRoot> [lite.mrpack lite.json]");
        main(property.strip().split("\\s+"));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 && args.length != 5) {
            System.out.println("""
                    Usage:
                      gradlew :HMCLCore:runQlSyncE2E -PsyncE2EArgs="<full.mrpack> <manifest-full.json> <workRoot> [lite.mrpack manifest-lite.json]\"""");
            System.exit(2);
        }

        Path zipPath = Path.of(args[0]);
        Path manifestFile = Path.of(args[1]);
        Path workRoot = Path.of(args[2]);
        Path liteZipPath = args.length == 5 ? Path.of(args[3]) : null;
        Files.createDirectories(workRoot);

        if (!Files.isRegularFile(manifestFile)) {
            System.err.println("Manifest not found: " + manifestFile + " — generate it first via :HMCLCore:runQlManifestTool");
            System.exit(2);
        }

        // Rewrites every manifest URL onto a local server that streams files out of the mrpack payload.
        ExecutorService pool = Executors.newFixedThreadPool(16, task -> {
            Thread thread = new Thread(task, "ql-e2e-payload");
            thread.setDaemon(true);
            return thread;
        });
        HttpServer server = startPayloadServer(zipPath, pool);
        int port = server.getAddress().getPort();
        System.out.println("Payload server on http://127.0.0.1:" + port);
        try {
            // Fresh scratch directory each run so counters are deterministic.
            Path gameDir = Files.createDirectories(workRoot.resolve("run-" + System.currentTimeMillis()));
            runScenarios(manifestFile, gameDir, port);
            if (liteZipPath != null)
                runSeedScenarios(zipPath, liteZipPath, manifestFile, gameDir.getParent(), port);
        } finally {
            server.stop(0);
            pool.shutdownNow();
            System.out.println("Payload server stopped");
        }
    }

    private static void runScenarios(Path manifestFile, Path gameDir, int port) throws Exception {
        String manifestText = rewriteManifest(manifestFile, port);
        QLManifest manifest = QLManifest.fromJson(manifestText);
        System.out.println("Manifest validated: std=" + manifest.channelOrThrow("std").mods.size()
                + " common=" + manifest.channelOrThrow(QLManifest.CHANNEL_COMMON).files.size()
                + " pfm=" + manifest.channelOrThrow("pfm").mods.size()
                + ", pack " + manifest.packVersionOrDefault());

        CacheRepository.getInstance().changeDirectory(gameDir.getParent().resolve(".hmcl"));

        scenario("A. fresh install (std, dh on)", manifest, gameDir, "std", Map.of("dh", true),
                outcome -> check(outcome, 406, 0, 0, 0, false));
        reportLayout(gameDir);
        scenario("B. idempotent re-run (std)", manifest, gameDir, "std", null,
                outcome -> check(outcome, 0, 406, 0, 0, false));
        scenario("C. disable dh", manifest, gameDir, "std", Map.of("dh", false),
                outcome -> check(outcome, 0, 405, 0, 1, false));
        scenario("D. switch to pfm", manifest, gameDir, "pfm", null,
                outcome -> check(outcome, 0, 406, 0, 0, true));
        reportLayout(gameDir);
        scenario("E. switch back (std, dh on)", manifest, gameDir, "std", Map.of("dh", true),
                outcome -> check(outcome, 0, 405, 0, 1, true));
        reportLayout(gameDir);

        verifyFinalState(gameDir);
        System.out.println("\nAll scenarios passed. Scratch instance kept at " + gameDir.toAbsolutePath());
    }

    /// Starts the local server serving {@code /files/<path>} out of the mrpack {@code overrides/} payload.
    private static HttpServer startPayloadServer(Path zipPath, ExecutorService pool) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(pool);
        server.createContext("/files", exchange -> servePayload(zipPath, exchange));
        server.start();
        return server;
    }

    private static void servePayload(Path zipPath, HttpExchange exchange) throws IOException {
        try {
            String encoded = exchange.getRequestURI().getRawPath().substring("/files".length());
            if (encoded.startsWith("/"))
                encoded = encoded.substring(1);
            String relative = URLDecoder.decode(encoded, StandardCharsets.UTF_8);

            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }

            try (ZipFile zip = new ZipFile(zipPath.toFile())) {
                ZipEntry entry = zip.getEntry("overrides/" + relative);
                if (entry == null) {
                    respond(exchange, 404, ("not in payload: " + relative).getBytes(StandardCharsets.UTF_8));
                    return;
                }
                // Stream without buffering: payload entries can be hundreds of MB.
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, entry.getSize());
                try (InputStream in = zip.getInputStream(entry); OutputStream out = exchange.getResponseBody()) {
                    in.transferTo(out);
                }
            }
            exchange.close();
        } catch (Exception e) {
            respond(exchange, 500, ("server error: " + e).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    /// Replaces every manifest URL with the local payload server, then returns the rewritten text.
    private static String rewriteManifest(Path manifestFile, int port) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(manifestFile, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject channels = root.getAsJsonObject("channels");
        int rewritten = 0;
        for (Map.Entry<String, JsonElement> channelEntry : channels.entrySet()) {
            JsonObject channel = channelEntry.getValue().getAsJsonObject();
            for (String listName : List.of("mods", "files")) {
                JsonArray list = channel.getAsJsonArray(listName);
                for (JsonElement element : list) {
                    JsonObject entry = element.getAsJsonObject();
                    String path = entry.getAsJsonPrimitive("path").getAsString();
                    JsonArray urls = new JsonArray();
                    urls.add("http://127.0.0.1:" + port + "/files/" + encodeUrlPath(path));
                    entry.add("urls", urls);
                    rewritten++;
                }
            }
        }
        System.out.println("Rewrote " + rewritten + " URLs to the local payload server");
        return GSON.toJson(root);
    }

    private static String encodeUrlPath(String path) {
        return Arrays.stream(path.split("/"))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }

    private interface ScenarioCheck {
        void check(QLSyncManager.SyncOutcome outcome);
    }

    /// Runs the two seed-import scenarios against freshly unpacked packs inside
    /// {@code seed-full}/ {@code seed-lite} directories next to the sync instance.
    ///
    /// <p>Both packs are unpacked by HMCL's native installer task exactly like the modpack
    /// wizard would, then QLized by {@code QLSeedImporter#seedize} and completed by a
    /// regular sync run. Because both packs ship the same mod jars, every file missing
    /// from the LITE seed can be served from the FULL zip payload.</p>
    private static void runSeedScenarios(Path fullZip, Path liteZip, Path manifestFile, Path runRoot, int port)
            throws Exception {
        QLManifest manifest = QLManifest.fromJson(rewriteManifest(manifestFile, port));

        // Fresh dirs: leftover files from a previous run would leak into the seed counters.
        Path seedDir = freshDir(runRoot.resolve("seed-full"));
        unpackNatively(fullZip, seedDir);
        QLSeedImporter.SeedOutcome seeded = QLSeedImporter.seedize(seedDir, manifest, "http://seed/", "std", null, null);
        System.out.println("F.7 FULL seeded=" + seeded.seeded + " missing=" + seeded.missing
                + " mismatch=" + seeded.mismatch);
        assertEquals(405, seeded.seeded, "FULL seed must hash-match all manifest files but the parked dh mod");
        assertEquals(1, seeded.missing, "the dh mod target is a *.disabled name that the pack does not use");
        assertEquals(0, seeded.mismatch, "FULL overrides must hash-match the manifest");
        QLSyncManager.SyncOutcome seededSync = QLSyncManager.sync(seedDir, manifest, "std", null, null);
        System.out.printf(Locale.ROOT, "F.8 FULL seed completion  downloaded=%d unchanged=%d renamed=%d failed=%s pack=%s%n",
                seededSync.downloaded, seededSync.unchanged, seededSync.renamed,
                seededSync.failedFiles.isEmpty() ? "none" : seededSync.failedFiles, seededSync.packVersion);
        check(seededSync, 0, 405, 0, 1, false);
        // The completion sync re-tracks the parked dh mod, so the final registry is full-sized.
        verifySeedState(seedDir, 136, 270);
        verifyDblpackMods(seedDir, manifest, 136);

        Path liteDir = freshDir(runRoot.resolve("seed-lite"));
        unpackNatively(liteZip, liteDir);
        QLSeedImporter.SeedOutcome lite = QLSeedImporter.seedize(liteDir, manifest, "http://seed/", "std",
                Map.of("dh", true), null);
        System.out.println("G.9 LITE seeded=" + lite.seeded + " missing=" + lite.missing
                + " mismatch=" + lite.mismatch);
        assertEquals(0, lite.mismatch, "LITE overrides must hash-match the manifest files they carry");
        QLSyncManager.SyncOutcome liteSync = QLSyncManager.sync(liteDir, manifest, "std", null, null);
        System.out.printf(Locale.ROOT, "G.10 LITE seed completion downloaded=%d unchanged=%d renamed=%d failed=%s pack=%s%n",
                liteSync.downloaded, liteSync.unchanged, liteSync.renamed,
                liteSync.failedFiles.isEmpty() ? "none" : liteSync.failedFiles, liteSync.packVersion);
        check(liteSync, lite.missing, lite.seeded, 0, 0, false);
        verifySeedState(liteDir, 136, 270);
        verifyDblpackMods(liteDir, manifest, 136);
    }

    /// Verifies the tracked bookkeeping of a completed seed scenario.
    private static void verifySeedState(Path gameDir, int expectedStd, int expectedCommon) {
        try {
            QLSyncState state = QLSyncState.load(gameDir);
            if (!"26.8.1".equals(state.packVersion))
                throw new AssertionError("state packVersion " + state.packVersion);
            if (state.trackedOf("std").size() != expectedStd)
                throw new AssertionError("tracked std " + state.trackedOf("std").size() + " != " + expectedStd);
            if (state.trackedOf(QLManifest.CHANNEL_COMMON).size() != expectedCommon)
                throw new AssertionError("tracked common " + state.trackedOf(QLManifest.CHANNEL_COMMON).size()
                        + " != " + expectedCommon);
        } catch (IOException e) {
            throw new AssertionError("Cannot load seed state", e);
        }
    }

    /// Verifies the dblpack mods inventory and reports the pack/shader breakdown.
    private static void verifyDblpackMods(Path gameDir, QLManifest manifest, int expectedMods) {
        try {
            QLDblPack dbl = QLDblPack.load(gameDir);
            if (dbl == null)
                throw new AssertionError("seed import did not write dblpack.json");
            if (dbl.mods.size() != expectedMods) {
                java.util.Set<String> recorded = new HashSet<>();
                for (QLDblPack.ModEntry mod : dbl.mods)
                    recorded.add(mod.path);
                for (String channel : QLManifest.MODS_CHANNELS)
                    for (QLManifest.FileEntry entry : manifest.channelOrThrow(channel).mods)
                        if (!recorded.contains(entry.path))
                            System.err.println("  dblpack missing entry: " + entry.path);
                throw new AssertionError("dblpack mods " + dbl.mods.size() + " != " + expectedMods);
            }
            System.out.println("  dblpack: mods=" + dbl.mods.size()
                    + " resourcepacks=" + dbl.resourcepacks.size()
                    + " shaders=" + dbl.shaders.size());
        } catch (IOException e) {
            throw new AssertionError("Cannot load dblpack", e);
        }
    }

    /// Unpacks a modpack exactly like the modpack wizard does, without expecting CDN files.
    private static void unpackNatively(Path zip, Path dest) throws Exception {
        ModpackInstallTask<Object> task = new ModpackInstallTask<>(zip, dest, StandardCharsets.UTF_8,
                List.of("/client-overrides", "/overrides"), any -> true, null);
        if (!new AsyncTaskExecutor(task).test())
            throw new AssertionError("Unpacking " + zip + " failed");
        System.out.println("Unpacked " + zip.getFileName() + " into " + dest.getFileName());
    }

    /// Creates an empty scratch directory, wiping any leftovers from a previous run.
    private static Path freshDir(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
            }
        }
        return Files.createDirectories(dir);
    }

    private static void scenario(String label, QLManifest manifest, Path gameDir, String channel,
                                 Map<String, Boolean> extras, ScenarioCheck checker) throws IOException {
        long start = System.nanoTime();
        QLSyncManager.SyncOutcome outcome = QLSyncManager.sync(gameDir, manifest, channel, extras, null);
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.printf(Locale.ROOT, "%-42s %6d ms  downloaded=%d unchanged=%d deleted=%d renamed=%d switched=%s failed=%s pack=%s%n",
                label, ms, outcome.downloaded, outcome.unchanged, outcome.deleted, outcome.renamed,
                outcome.channelSwitched, outcome.failedFiles.isEmpty() ? "none" : outcome.failedFiles,
                outcome.packVersion);
        checker.check(outcome);
    }

    private static void check(QLSyncManager.SyncOutcome outcome, int downloaded, int unchanged,
                              int deleted, int renamed, boolean channelSwitched) {
        List<String> problems = new java.util.ArrayList<>();
        if (outcome.downloaded != downloaded)
            problems.add("downloaded " + outcome.downloaded + " != " + downloaded);
        if (outcome.unchanged != unchanged)
            problems.add("unchanged " + outcome.unchanged + " != " + unchanged);
        if (outcome.deleted != deleted)
            problems.add("deleted " + outcome.deleted + " != " + deleted);
        if (outcome.renamed != renamed)
            problems.add("renamed " + outcome.renamed + " != " + renamed);
        if (outcome.channelSwitched != channelSwitched)
            problems.add("channelSwitched " + outcome.channelSwitched + " != " + channelSwitched);
        if (!outcome.failedFiles.isEmpty())
            problems.add("failed " + outcome.failedFiles);
        if (outcome.cancelled)
            problems.add("cancelled");
        if (outcome.packVersion == null)
            problems.add("packVersion missing (run aborted before bookkeeping)");
        if (!problems.isEmpty())
            throw new AssertionError("Scenario check failed: " + problems);
    }

    private static void reportLayout(Path gameDir) throws IOException {
        for (String folder : List.of("mods", "mods-std", "mods-pfm")) {
            Path path = gameDir.resolve(folder);
            if (!Files.isDirectory(path)) {
                System.out.println("  layout " + folder + ": missing");
                continue;
            }
            try (Stream<Path> stream = Files.list(path)) {
                List<Path> files = stream.filter(Files::isRegularFile).toList();
                long jars = files.stream().filter(file -> file.getFileName().toString().endsWith(".jar")).count();
                System.out.println("  layout " + folder + ": " + jars + " jars, " + (files.size() - jars) + " other files");
            }
        }
    }

    private static void verifyFinalState(Path gameDir) throws IOException {
        List<String> problems = new java.util.ArrayList<>();

        // State bookkeeping.
        QLSyncState state = QLSyncState.load(gameDir);
        if (!"26.8.1".equals(state.packVersion))
            problems.add("state packVersion " + state.packVersion);
        if (!"std".equals(state.activeChannel))
            problems.add("state activeChannel " + state.activeChannel);
        if (state.trackedOf("std").size() != 136)
            problems.add("tracked std " + state.trackedOf("std").size() + " != 136");
        if (state.trackedOf(QLManifest.CHANNEL_COMMON).size() != 270)
            problems.add("tracked common " + state.trackedOf(QLManifest.CHANNEL_COMMON).size() + " != 270");
        if (state.trackedOf("pfm").size() != 0)
            problems.add("tracked pfm " + state.trackedOf("pfm").size());
        if (!Set.of("dh").equals(new HashSet<>(state.enabledOptional)))
            problems.add("enabledOptional " + state.enabledOptional);

        // The manifest JSON must persist inside the game directory (used for later update checks).
        if (!Files.isRegularFile(gameDir.resolve(QLSyncState.STATE_FILE)))
            problems.add("missing " + QLSyncState.STATE_FILE);

        // Spot-check two files: a payload mod of an optional group and a bare config root.
        String dhjar = null;
        for (String path : state.trackedOf("std").keySet())
            if (path.contains("DistantHorizons"))
                dhjar = path;
        String dhToml = state.trackedOf(QLManifest.CHANNEL_COMMON).keySet().stream()
                .filter(path -> path.contains("DistantHorizons.toml"))
                .findFirst().orElse(null);
        if (dhjar == null)
            problems.add("DistantHorizons mod not tracked");
        if (dhToml == null)
            problems.add("DistantHorizons.toml not tracked");

        if (dhjar != null) {
            // Mods-channel logical paths keep the "mods/" prefix; strip it before resolving.
            String rawName = dhjar.substring(dhjar.indexOf('/') + 1);
            Path jar = gameDir.resolve("mods").resolve(shard(rawName));
            if (!Files.isRegularFile(jar)) {
                problems.add("missing " + jar);
            } else if (!DigestUtils.digestToString("SHA-1", jar).equals(state.trackedOf("std").get(dhjar))) {
                problems.add("sha1 mismatch for " + dhjar);
            }
        }
        if (dhToml != null && !Files.isRegularFile(gameDir.resolve(shard(dhToml))))
            problems.add("missing config " + dhToml);

        // The marker must sit inside the active mods folder.
        if (!Files.isRegularFile(gameDir.resolve("mods").resolve(QLDirectoryLayout.CHANNEL_MARKER)))
            problems.add("channel marker missing in mods/");

        if (!problems.isEmpty())
            throw new AssertionError("Final state check failed: " + problems);
        System.out.println("Final state verified: state file, tracked counts, optional groups, marker");
    }

    private static String shard(String path) {
        return path.replace("/", java.nio.file.FileSystems.getDefault().getSeparator());
    }
}
