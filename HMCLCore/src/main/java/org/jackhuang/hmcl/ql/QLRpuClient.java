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
import com.google.gson.reflect.TypeToken;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Client of the QingLan RPU backend, the distribution API the in-game RPU client uses. The
 * launcher speaks it to check and refresh the resource-pack release exactly like the mod does.
 *
 * <p>Wire protocol (base {@code https://rpu.hcmtr.cn/api/v1/client}): a bearer token from
 * {@code POST /sessions}, then {@code GET /releases/latest}, {@code GET /releases/{id}/manifest}
 * (signed envelope whose {@code payload} is base64 JSON), {@code GET /sources?releaseId=...},
 * {@code POST /releases/{id}/check} and {@code POST /download-tickets}.</p>
 */
@NotNullByDefault
public final class QLRpuClient {

    /// Default service base; protocol versioning happens through the session handshake.
    public static final String DEFAULT_BASE = "https://rpu.hcmtr.cn/api/v1/client";

    /// User agent the backend expects from the in-game client.
    private static final String PROTOCOL = "QingLanRPU/1.0 protocol/1";

    /// Gson instance for the small wire documents.
    private static final Gson GSON = new Gson();

    /// One file of a published pack.
    ///
    /// @param path          target path inside the pack, e.g. {@code assets/x.png}
    /// @param objectId      id of the stored object
    /// @param cipherSha256  SHA-256 of the stored (encrypted) bytes
    /// @param cipherSize    stored size
    /// @param plainSha256   SHA-256 of the decrypted file
    /// @param plainSize     decrypted size
    public record Entry(String path, String objectId, String cipherSha256, long cipherSize,
                        String plainSha256, long plainSize) {
    }

    /// One published pack.
    ///
    /// @param id          stable pack id, e.g. {@code qjr-train-rp}
    /// @param name        display name
    /// @param description human description
    /// @param packFormat  resource pack format, when known
    /// @param files       the pack's files
    public record Pack(String id, String name, String description, Integer packFormat,
                       List<Entry> files) {
    }

    /// The decoded manifest payload.
    public record Manifest(int protocolVersion, String releaseId, List<Pack> packs) {
        /// @return every file of every pack, flattened
        public @Unmodifiable List<Entry> objects() {
            return packs.stream().flatMap(pack -> pack.files.stream()).toList();
        }
    }

    /// A time-limited download ticket.
    public record Ticket(String objectId, String url, String expiresAt) {
    }

    private final String token;

    private QLRpuClient(String token) {
        this.token = token;
    }

    /// Opens a client session; the returned object carries the bearer token.
    ///
    /// @param minecraftVersion the instance's game version, e.g. {@code 1.20.1}
    /// @param modVersion       the installed RPU client version, e.g. {@code 1.0.0}
    public static QLRpuClient open(String minecraftVersion, String modVersion) throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("protocolVersion", 1);
        request.addProperty("modVersion", modVersion);
        request.addProperty("minecraftVersion", minecraftVersion);
        JsonObject response = GSON.fromJson(
                execute("POST", DEFAULT_BASE + "/sessions", request.toString(), null), JsonObject.class);
        String token = response != null && response.has("token") ? response.get("token").getAsString() : null;
        if (token == null || token.isBlank())
            throw new IOException("RPU session rejected by the backend");
        return new QLRpuClient(token);
    }

    /// @return the newest published release id
    public String latestReleaseId() throws IOException {
        JsonObject response = GSON.fromJson(
                execute("GET", DEFAULT_BASE + "/releases/latest", null, token), JsonObject.class);
        if (response == null || !response.has("releaseId"))
            throw new IOException("RPU backend returned no release id");
        return response.get("releaseId").getAsString();
    }

    /// @return the signed envelope JSON exactly as served; this is what RPU stores in
    ///         {@code qlrpu/cache/current.json}
    public String manifestEnvelope(String releaseId) throws IOException {
        return execute("GET", DEFAULT_BASE + "/releases/" + releaseId + "/manifest", null, token);
    }

    /// @return the decoded manifest payload of a release
    public Manifest manifest(String releaseId) throws IOException {
        JsonObject envelope = GSON.fromJson(manifestEnvelope(releaseId), JsonObject.class);
        if (envelope == null || !envelope.has("payload"))
            throw new IOException("RPU manifest envelope is malformed");
        byte[] payload = Base64.getDecoder().decode(envelope.get("payload").getAsString());
        Manifest manifest = GSON.fromJson(new String(payload, StandardCharsets.UTF_8), Manifest.class);
        if (manifest == null)
            throw new IOException("RPU manifest payload is malformed");
        return manifest;
    }

    /// @return ids of the objects the backend still wants this client to fetch
    public @Unmodifiable List<String> requiredObjects(String releaseId, List<String> objectIds) throws IOException {
        JsonObject request = new JsonObject();
        request.add("objects", GSON.toJsonTree(objectIds));
        JsonObject response = GSON.fromJson(
                execute("POST", DEFAULT_BASE + "/releases/" + releaseId + "/check", request.toString(), token),
                JsonObject.class);
        if (response == null || !response.has("requiredObjectIds"))
            return objectIds;
        List<String> required = GSON.fromJson(response.get("requiredObjectIds"),
                new TypeToken<List<String>>() { }.getType());
        return required == null ? objectIds : List.copyOf(required);
    }

    /// @return download tickets (object id to time-limited URL) for the requested objects
    public @Unmodifiable List<Ticket> tickets(String releaseId, @Nullable String sourceId,
                                              List<String> objectIds) throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("releaseId", releaseId);
        if (sourceId != null)
            request.addProperty("sourceId", sourceId);
        request.add("objectIds", GSON.toJsonTree(objectIds));
        JsonObject response = GSON.fromJson(
                execute("POST", DEFAULT_BASE + "/download-tickets", request.toString(), token),
                JsonObject.class);
        if (response == null || !response.has("tickets"))
            throw new IOException("RPU download tickets are missing");
        List<Ticket> tickets = GSON.fromJson(response.get("tickets"),
                new TypeToken<List<Ticket>>() { }.getType());
        return tickets == null ? List.of() : List.copyOf(tickets);
    }

    /// @return the CDN sources the backend offers for this release
    public List<Map<String, Object>> sources(String releaseId) throws IOException {
        List<Map<String, Object>> sources = GSON.fromJson(
                execute("GET", DEFAULT_BASE + "/sources?releaseId=" + releaseId, null, token),
                new TypeToken<List<Map<String, Object>>>() { }.getType());
        return sources == null ? List.of() : sources;
    }

    /// Performs one HTTP call with the protocol headers and returns the response body.
    static String execute(String method, String url, @Nullable String body, @Nullable String token) throws IOException {
        URI uri = NetworkUtils.toURIOrNull(url);
        if (uri == null)
            throw new IOException("Bad RPU URL: " + url);
        URLConnection connection = NetworkUtils.createConnection(uri);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", PROTOCOL);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-store");
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (token != null)
            connection.setRequestProperty("Authorization", "Bearer " + token);
        if (!(connection instanceof HttpURLConnection http))
            throw new IOException("RPU requires HTTP: " + url);
        http = NetworkUtils.resolveConnection(http);
        http.setRequestMethod(method);
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            http.setDoOutput(true);
            http.setRequestProperty("Content-Type", "application/json");
            http.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = http.getOutputStream()) {
                out.write(bytes);
            }
        }
        int status = http.getResponseCode();
        InputStream stream = status >= 400 ? http.getErrorStream() : http.getInputStream();
        String text = stream == null ? "" : readFully(stream);
        if (status >= 400)
            throw new IOException("RPU backend answered HTTP " + status + ": " + text);
        return text;
    }

    private static String readFully(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }
}
