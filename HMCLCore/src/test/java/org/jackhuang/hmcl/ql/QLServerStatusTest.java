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

import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for the server status probes: SLP framing against a fake in-process
/// Minecraft server, malformed and truncated packet handling, and the HTTP
/// probe's status verdicts against a local embedded web server.
@NotNullByDefault
class QLServerStatusTest {

    @Test
    void testVarIntRoundTrip() throws Exception {
        for (int value : new int[] {0, 1, 127, 128, 47, 32650, Integer.MAX_VALUE}) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            write(out, value);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(buffer.toByteArray()));
            assertEquals(value, read(in), "varint round-trip for " + value);
        }
    }

    @Test
    void testSlpFramingAgainstFakeServer() throws Exception {
        // A minimal in-process Minecraft server answering a Server List Ping.
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int fakeServerPort = fakeServer.getLocalPort();
            CompletableFuture<Integer> seenProtocol = new CompletableFuture<>();
            CompletableFuture<Void> responded = CompletableFuture.runAsync(() -> {
                try (Socket client = fakeServer.accept()) {
                    InputStream in = client.getInputStream();
                    int handshakeLength = read(in);
                    byte[] handshake = in.readNBytes(handshakeLength);
                    DataInputStream payload = new DataInputStream(new ByteArrayInputStream(handshake));
                    assertEquals(0, read(payload)); // packetId handshake
                    seenProtocol.complete(read(payload));
                    // host string: varint byte length + bytes, skip it
                    int hostLength = read(payload);
                    payload.skipBytes(hostLength);
                    int port = payload.readUnsignedShort();
                    assertEquals(fakeServerPort, port);
                    assertEquals(1, read(payload)); // nextState

                    // status request packet: length varint + packetId
                    assertEquals(1, read(in));
                    assertEquals(0, in.read());

                    String status = "{\"description\":{\"text\":\"轻蓝服务器\\n欢迎\"},"
                            + "\"players\":{\"max\":20,\"online\":2,"
                            + "\"sample\":[{\"name\":\"Alice\",\"id\":\"0-0\"},{\"name\":\"Bob\",\"id\":\"0-1\"}]},"
                            + "\"version\":{\"name\":\"1.20.1\",\"protocol\":763}}";
                    byte[] statusBytes = status.getBytes(StandardCharsets.UTF_8);
                    ByteArrayOutputStream frame = new ByteArrayOutputStream();
                    DataOutputStream frameOut = new DataOutputStream(frame);
                    // packet body: packetId(0x00) varint + string(varint length + utf8 bytes)
                    write(frameOut, 0);
                    write(frameOut, statusBytes.length);
                    frame.write(statusBytes);
                    DataOutputStream rawOut = new DataOutputStream(client.getOutputStream());
                    write(rawOut, frame.size());
                    rawOut.write(frame.toByteArray());
                    rawOut.flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            QLServerStatus.SLPResult result =
                    QLServerStatus.pingSlp("127.0.0.1", fakeServer.getLocalPort(), 5000);
            responded.get(2, TimeUnit.SECONDS);
            assertEquals(2, result.playersOnline());
            assertEquals(20, result.playersMax());
            assertEquals("轻蓝服务器\n欢迎", result.motd());
            assertEquals(List.of("Alice", "Bob"), result.sample());
            assertTrue(result.latencyMs() >= 0);
            assertEquals(47, seenProtocol.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void testProbeOfflineEndpoint() {
        QLServerStatus.ProbeResult result =
                QLServerStatus.probeAll(List.of(QLServerStatus.Probe.slp("x", "127.0.0.1", findClosedPort())), 300)
                        .get(0);
        assertFalse(result.online());
        assertEquals(-1, result.latencyMs());
    }

    /// A 200 page reports up; a 404/500 page and a malformed URL must never
    /// look like a healthy endpoint.
    @Test
    void testHttpProbeReportsServerStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/missing", exchange -> exchange.sendResponseHeaders(404, -1));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();

            QLServerStatus.HTTPResult ok = QLServerStatus.probeHttp(base + "/ok", 5000);
            assertTrue(ok.isUp(), "HTTP 200 is up");
            assertEquals(200, ok.statusCode());
            assertEquals("hello", ok.body());
            assertTrue(ok.toProbeResult().online());

            QLServerStatus.HTTPResult missing = QLServerStatus.probeHttp(base + "/missing", 5000);
            assertFalse(missing.isUp(), "HTTP 404 is an unhealthy endpoint");
            assertEquals(404, missing.statusCode());
            assertFalse(missing.toProbeResult().online());

            assertThrows(IOException.class, () -> QLServerStatus.probeHttp("ht tp://bad uri", 1000),
                    "a malformed probe URL fails the probe instead of crashing");
            assertThrows(IOException.class, () -> QLServerStatus.probeHttp("file:///etc/passwd", 1000),
                    "only HTTP(S) URLs are probeable");
        } finally {
            server.stop(0);
        }
    }

    /// A packet length just holding the packet id underflows the body length;
    /// the probe must fail instead of allocating a negative array.
    @Test
    void testSlpRejectsUnderflowedFraming() throws Exception {
        CompletableFuture<Void> handled = new CompletableFuture<>();
        int port = fakeSlpServer(reply -> {
            DataOutputStream out = new DataOutputStream(reply);
            write(out, 1); // packetLength: packet id only → bodyLength = 1 - 1 - 1 < 0
            out.write(0);  // packetId
            write(out, 0); // stringLength
        }, handled);

        IOException exception = assertThrows(IOException.class,
                () -> QLServerStatus.pingSlp("127.0.0.1", port, 5000));
        assertTrue(exception.getMessage().contains("Malformed SLP response framing"), exception.toString());
        handled.get(2, TimeUnit.SECONDS);
    }

    /// A giant packet length must fail the size check instead of allocating.
    @Test
    void testSlpRejectsOversizeFraming() throws Exception {
        CompletableFuture<Void> handled = new CompletableFuture<>();
        int port = fakeSlpServer(reply -> {
            DataOutputStream out = new DataOutputStream(reply);
            write(out, Integer.MAX_VALUE); // packetLength
            out.write(0);                  // packetId
            write(out, 0);                 // stringLength
        }, handled);

        IOException exception = assertThrows(IOException.class,
                () -> QLServerStatus.pingSlp("127.0.0.1", port, 5000));
        assertTrue(exception.getMessage().contains("Malformed SLP response framing"), exception.toString());
        handled.get(2, TimeUnit.SECONDS);
    }

    /// A truncated body (the connection closes early) fails the probe cleanly.
    @Test
    void testSlpRejectsTruncatedBody() throws Exception {
        CompletableFuture<Void> handled = new CompletableFuture<>();
        int port = fakeSlpServer(reply -> {
            DataOutputStream out = new DataOutputStream(reply);
            write(out, 22); // packetLength: body of 20 bytes
            out.write(0);   // packetId
            write(out, 20); // stringLength == bodyLength, so the frame checks pass
            reply.write(new byte[] {1, 2, 3}); // but only 3 arrive
        }, handled);

        IOException exception = assertThrows(IOException.class,
                () -> QLServerStatus.pingSlp("127.0.0.1", port, 5000));
        assertTrue(exception.getMessage().contains("Unexpected end of stream"), exception.toString());
        handled.get(2, TimeUnit.SECONDS);
    }

    @Test
    void testDefaultProbesShape() {
        assertEquals(3, QLServerStatus.DEFAULT_PROBES.size());
        assertEquals("play.simpfun.cn", QLServerStatus.DEFAULT_PROBES.get(0).host());
    }

    /// Functional sink a fake server reply builder writes its raw bytes into.
    @FunctionalInterface
    private interface ReplyWriter {
        void write(ByteArrayOutputStream reply) throws Exception;
    }

    /// Starts an in-process fake SLP server that answers the handshake with the
    /// raw bytes built by {@code builder} and then closes. Returns the bound
    /// port; {@code handled} completes once the reply went out.
    private static int fakeSlpServer(ReplyWriter builder, CompletableFuture<Void> handled) throws Exception {
        ServerSocket fakeServer = new ServerSocket(0);
        CompletableFuture.runAsync(() -> {
            try (Socket client = fakeServer.accept(); OutputStream rawOut = client.getOutputStream()) {
                InputStream in = client.getInputStream();
                int handshakeLength = read(in);
                in.readNBytes(handshakeLength);
                // the two-byte status request: length varint + packetId
                assertEquals(1, read(in));
                assertEquals(0, in.read());

                ByteArrayOutputStream reply = new ByteArrayOutputStream();
                builder.write(reply);
                rawOut.write(reply.toByteArray());
                rawOut.flush();
                handled.complete(null);
            } catch (Exception e) {
                handled.completeExceptionally(e);
            } finally {
                try {
                    fakeServer.close();
                } catch (Exception ignored) {
                }
            }
        });
        return fakeServer.getLocalPort();
    }

    private static void write(DataOutputStream out, int value) throws Exception {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static int read(InputStream in) throws Exception {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.read();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0)
                return result;
        }
        return result;
    }

    private static int findClosedPort() {
        for (int port = 49152; port < 49272; port++) {
            try (ServerSocket ignored = new ServerSocket(port)) {
                return port; // free now; nothing else is likely bound to it
            } catch (Exception ignored) {
                // try next
            }
        }
        return 0; // 0 would be treated as ephemeral; use an invalid port as last resort
    }
}
