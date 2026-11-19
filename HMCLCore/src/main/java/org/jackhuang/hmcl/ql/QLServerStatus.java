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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.IOUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLConnection;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/// Probes the Qinglan server fleet.
///
/// Two endpoint kinds are supported:
/// - {@link ProbeKind#SLP}: Minecraft Java Server List Ping over raw TCP
///   (handshake + request + JSON status response, 1.7+ varint framing).
/// - {@link ProbeKind#HTTP}: plain HTTPS/HTTP GET with a timeout.
///
/// All probes are synchronous; UI code should wrap them in a task on an IO scheduler.
@NotNullByDefault
public final class QLServerStatus {

    /// Endpoint kind of a probe.
    public enum ProbeKind {
        /// A Minecraft Java server, pinged with the Server List Ping protocol.
        SLP,
        /// A plain HTTPS/HTTP URL, pinged with a GET request.
        HTTP
    }

    /// A single monitored endpoint.
    ///
    /// @param label the human-readable name shown in the UI (may be blank)
    /// @param kind  the probe kind; decides how {@code host}/{@code port}/{@code url} are used
    /// @param host  the SLP socket host (for {@link ProbeKind#SLP}), otherwise informational
    /// @param port  the SLP socket port (for {@link ProbeKind#SLP}); 0 for HTTP probes
    /// @param url   the full URL to GET (for {@link ProbeKind#HTTP}), otherwise {@code null}
    public record Probe(@Nullable String label, ProbeKind kind, String host, int port, @Nullable String url) {

        /// Builds a Minecraft SLP probe.
        public static Probe slp(@Nullable String label, String host, int port) {
            return new Probe(label, ProbeKind.SLP, host, port, null);
        }
    }

    /// Outcome of a single probe.
    ///
    /// @param online    whether the endpoint answered within the timeout
    /// @param latencyMs elapsed milliseconds from connection start to a valid response;
    ///                  -1 when offline
    /// @param players   number of online players reported by an SLP server; {@code null} otherwise
    /// @param playersMax the player slot maximum reported by an SLP server; {@code null} otherwise
    /// @param motd      the full flattened Minecraft MOTD for SLP probes; {@code null}
    ///                  otherwise. When offline, carries the failure message instead.
    /// @param sample    names of currently online players reported by an SLP server
    ///                  ({@code players.sample}); empty otherwise
    public record ProbeResult(boolean online, long latencyMs, @Nullable Integer players,
                              @Nullable Integer playersMax, @Nullable String detail,
                              @Unmodifiable List<String> sample) {
    }

    /// Result of a successful Server List Ping.
    ///
    /// @param latencyMs elapsed milliseconds from connect start to response
    /// @param playersOnline number of players currently on the server
    /// @param playersMax the player slot maximum
    /// @param motd the full flattened MOTD text (all description extras concatenated)
    /// @param sample names of the players currently online, from {@code players.sample};
    ///               empty when the server reports none
    public record SLPResult(long latencyMs, int playersOnline, int playersMax, @Nullable String motd,
                            @Unmodifiable List<String> sample) {
        /// Converts this result into a {@link ProbeResult} for UI consumption.
        public ProbeResult toProbeResult() {
            return new ProbeResult(true, latencyMs, playersOnline, playersMax, motd, sample);
        }
    }

    /// HTTP GET outcome.
    public record HTTPResult(long latencyMs, int statusCode, @Nullable String body) {
        /// True when the endpoint answered with HTTP 200.
        public boolean isUp() {
            return statusCode == 200;
        }

        /// Converts this result into a {@link ProbeResult} for UI consumption.
        public ProbeResult toProbeResult() {
            return new ProbeResult(isUp(), latencyMs, null, null, "HTTP " + statusCode, List.of());
        }
    }

    /// The default probe table for the Qinglan fleet.
    ///
    /// Kept here until the remote manifest gains a {@code servers[]} section; then this
    /// fallback is only used when the manifest carries none.
    public static @Unmodifiable List<Probe> defaultProbes() {
        List<Probe> probes = new ArrayList<>();
        for (QLEndpoints.StatusEndpoint endpoint : QLEndpoints.statusProbes()) {
            if ("slp".equalsIgnoreCase(endpoint.kind())) {
                probes.add(Probe.slp(endpoint.name(), endpoint.host(), endpoint.port()));
            } else {
                String url = endpoint.url() != null ? endpoint.url()
                        : "http://" + endpoint.host() + ":" + endpoint.port();
                probes.add(new Probe(endpoint.name(), ProbeKind.HTTP, endpoint.host(), endpoint.port(), url));
            }
        }
        return List.copyOf(probes);
    }

    /// Probes every given endpoint sequentially, swallowing individual failures
    /// into offline results so one dead server never aborts the whole table.
    public static List<ProbeResult> probeAll(List<Probe> probes, int timeoutMs) {
        return probes.stream().map(probe -> {
            try {
                return probe(probe, timeoutMs);
            } catch (Exception e) {
                String message = e.getMessage();
                return new ProbeResult(false, -1, null, null,
                        message == null || message.isBlank() ? e.toString() : message, List.of());
            }
        }).toList();
    }

    /// Probes a single endpoint with the given connect/read timeout in milliseconds.
    public static ProbeResult probe(Probe probe, int timeoutMs) throws IOException {
        return switch (probe.kind()) {
            case SLP -> pingSlp(probe.host(), probe.port(), timeoutMs).toProbeResult();
            case HTTP -> probeHttp(probe.url(), timeoutMs).toProbeResult();
        };
    }

    /// Runs the Minecraft Server List Ping protocol against {@code host:port}.
    ///
    /// The measured latency covers connection establishment through response receipt.
    public static SLPResult pingSlp(String host, int port, int timeoutMs) throws IOException {
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            // Payload: packetId(0x00) + protocol(47) + host + port + nextState(1).
            ByteArrayOutputStream payloadBuffer = new ByteArrayOutputStream();
            DataOutputStream payload = new DataOutputStream(payloadBuffer);
            writeVarInt(payload, 0);
            writeVarInt(payload, 47);
            writeString(payload, host);
            payload.writeShort(port & 0xFFFF);
            writeVarInt(payload, 1);
            byte[] handshake = payloadBuffer.toByteArray();
            byte[] statusRequest = new byte[] {0x00};

            // Frame both packets (packetLength = packetId + payload), then send.
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            writeVarInt(out, handshake.length);
            out.write(handshake);
            writeVarInt(out, statusRequest.length);
            out.write(statusRequest);
            out.flush();

            InputStream in = socket.getInputStream();
            int packetLength = readVarInt(in);
            int packetId = readVarInt(in); // 0x00 status response
            int stringLength = readVarInt(in);
            int bodyLength = packetLength - bytesOfVarInt(packetId) - bytesOfVarInt(stringLength);
            // hostile or broken peers must fail the probe instead of crashing
            // it with a negative array or an unreasonable allocation
            if (bodyLength < 0 || bodyLength > MAX_RESPONSE_BYTES || stringLength > bodyLength)
                throw new IOException("Malformed SLP response framing from " + host + ":" + port
                        + ": packetLength=" + packetLength + ", stringLength=" + stringLength);
            byte[] jsonBytes = readFully(in, bodyLength);
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;

            JsonObject status = JsonUtils.GSON.fromJson(
                    new String(jsonBytes, 0, stringLength, StandardCharsets.UTF_8), JsonObject.class);
            if (status == null)
                throw new IOException("Empty SLP status response from " + host + ":" + port);

            JsonObject players = status.getAsJsonObject("players");
            if (players == null)
                throw new IOException("SLP response from " + host + ":" + port + " has no players object");

            return new SLPResult(Math.max(0, latencyMs),
                    JsonUtils.getInt(players, "online", 0),
                    JsonUtils.getInt(players, "max", 0),
                    flattenText(status.get("description")),
                    readSample(players));
        }
    }

    /// Upper bound for an SLP status response body, generous for a status JSON:
    /// keeps a hostile peer from making the probe allocate unbounded memory.
    private static final int MAX_RESPONSE_BYTES = 1 << 20;

    /// Performs a timed GET request against {@code url}; the status code the
    /// server answered with is carried back so a maintenance or error page can
    /// never look like a healthy endpoint. A malformed URL fails the probe.
    public static HTTPResult probeHttp(String url, int timeoutMs) throws IOException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IOException("Malformed probe URL: " + url, e);
        }

        long start = System.nanoTime();
        URLConnection base = NetworkUtils.createConnection(uri);
        if (!(base instanceof HttpURLConnection http))
            throw new IOException("Only HTTP(S) probe URLs are supported: " + url);
        http.setConnectTimeout(timeoutMs);
        http.setReadTimeout(timeoutMs);
        HttpURLConnection connection = NetworkUtils.resolveConnection(http);
        int statusCode = connection.getResponseCode();
        InputStream stream = statusCode < 400 ? connection.getInputStream() : connection.getErrorStream();
        String body = stream == null ? "" : IOUtils.readFullyAsString(stream);
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        connection.disconnect();
        return new HTTPResult(Math.max(0, latencyMs), statusCode,
                body.length() <= 300 ? body : body.substring(0, 300));
    }

    // --- protocol primitives ---

    /// Flattens a Minecraft chat component (MOTD) into plain text.
    ///
    /// Handles both the plain-string form and the structured
    /// {@code {"text": "...", "extra": ["...", {...}]}} component tree,
    /// recursing into every {@code extra} entry and preserving newlines.
    ///
    /// @param element the {@code description} element of the SLP status response; may be null
    /// @return the concatenated plain text of the whole MOTD, never null
    static String flattenText(@Nullable JsonElement element) {
        if (element == null)
            return "";
        if (element.isJsonPrimitive())
            return element.getAsString();
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            StringBuilder builder = new StringBuilder();
            String text = JsonUtils.getString(object, "text");
            if (text != null)
                builder.append(text);
            JsonArray extras = object.getAsJsonArray("extra");
            if (extras != null) {
                for (JsonElement extra : extras)
                    builder.append(flattenText(extra));
            }
            return builder.toString();
        }
        return "";
    }

    /// Reads the names of currently online players from the {@code players.sample}
    /// array of the SLP status response.
    private static List<String> readSample(JsonObject players) {
        JsonArray sample = players.getAsJsonArray("sample");
        if (sample == null)
            return List.of();
        return sample.asList().stream()
                .filter(JsonElement::isJsonObject)
                .map(entry -> JsonUtils.getString(entry.getAsJsonObject(), "name"))
                .filter(name -> name != null)
                .toList();
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.read();
            if (b < 0)
                throw new IOException("Unexpected end of stream while reading a varint");
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0)
                return result;
        }
        throw new IOException("VarInt is too big");
    }

    private static byte @Unmodifiable [] readFully(InputStream in, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(result, offset, length - offset);
            if (read < 0)
                throw new IOException("Unexpected end of stream in a framed packet");
            offset += read;
        }
        return result;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static int bytesOfVarInt(int value) {
        int count = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            count++;
        }
        return count;
    }

    private QLServerStatus() {
    }
}
