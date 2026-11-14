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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the forum's RSS fallback list and the rendered-page article reader
/// against a local fake Halo site. Both paths are the forum's only content
/// sources while the content API cluster is down, so they carry regression
/// coverage.
@NotNullByDefault
class QLForumTest {

    /// The RSS feed fills the list while the content API is down: absolute
    /// links are normalized to their path, a bare host degrades to "/" instead
    /// of underflowing, and the feed pixel is stripped from the excerpt.
    @Test
    void buildsFallbackListFromRss() throws Exception {
        HttpServer server = serve();
        try {
            String base = base(server);
            String feed = """
                    <rss version="2.0"><channel>
                      <item>
                        <title>First Post</title>
                        <link>%s/archives/first-post</link>
                        <pubDate>Wed, 21 Oct 2026 08:30:00 GMT</pubDate>
                        <dc:creator xmlns:dc="http://purl.org/dc/elements/1.1/">Admin</dc:creator>
                        <description><![CDATA[<p>Hello <a href="/archives/first-post">world</a>&nbsp;excerpt</p><img src="pixel.png" plugins/feed>]]></description>
                      </item>
                      <item>
                        <title>Bare Host</title>
                        <link>%s</link>
                        <description><![CDATA[<p>teaser</p>]]></description>
                      </item>
                      <item>
                        <title></title>
                        <link>/archives/empty-title</link>
                        <description><![CDATA[<p>ignored</p>]]></description>
                      </item>
                    </channel></rss>""".formatted(base, base);
            respond(server, "/rss.xml", feed);

            List<QLForum.Post> posts = QLForum.fetchPostsFromRss(base);

            assertEquals(2, posts.size(), "the item without a title is skipped");
            QLForum.Post first = posts.get(0);
            assertEquals("first-post", first.name());
            assertEquals("First Post", first.title());
            assertEquals("/archives/first-post", first.permalink());
            assertEquals("Admin", first.author());
            assertEquals("2026-10-21", first.publishTime(), "RFC 1123 dates become yyyy-MM-dd");
            assertTrue(first.fromRss());
            assertFalse(first.pinned());
            assertEquals(-1, first.visits(), "the feed knows no counters");
            assertEquals("Hello world excerpt", first.excerpt(), "tags, entities and the feed pixel are stripped");

            QLForum.Post bare = posts.get(1);
            assertEquals("/", bare.permalink(), "a bare host degrades to the root without underflow");
            assertEquals("/", bare.name());
        } finally {
            server.stop(0);
        }
    }

    /// An empty feed yields an empty list instead of failing the whole page.
    @Test
    void servesEmptyListForEmptyFeed() throws Exception {
        HttpServer server = serve();
        try {
            respond(server, "/rss.xml", "<rss version=\"2.0\"><channel></channel></rss>");
            assertTrue(QLForum.fetchPostsFromRss(base(server)).isEmpty());
        } finally {
            server.stop(0);
        }
    }

    /// A malformed feed surfaces as an IOException instead of half a list.
    @Test
    void rejectsMalformedRss() throws Exception {
        HttpServer server = serve();
        try {
            respond(server, "/rss.xml", "<rss><channel><item><unclosed");
            assertThrows(IOException.class, () -> QLForum.fetchPostsFromRss(base(server)));
        } finally {
            server.stop(0);
        }
    }

    /// The rendered-page reader demands /archives/ permalinks (M7), parses the
    /// content container and strips the site suffix from the page title.
    @Test
    void readsArticleFromRenderedPage() throws Exception {
        HttpServer server = serve();
        try {
            respond(server, "/archives/first-post", """
                    <html><head><title>First Post - 轻蓝轨交论坛</title></head>
                    <body><article id="content"><p>hello body</p></article></body></html>""");

            QLForum.Article article = QLForum.fetchArticleFromPage(base(server), "/archives/first-post");
            assertEquals("first-post", article.name());
            assertEquals("First Post", article.title());
            assertEquals("<p>hello body</p>", article.html());
            assertEquals("/archives/first-post", article.permalink());

            assertThrows(IOException.class, () -> QLForum.fetchArticleFromPage(base(server), "/forums/topic"),
                    "non-archives permalinks are rejected before any request");

            respond(server, "/archives/no-content", "<html><body>missing container</body></html>");
            assertThrows(IOException.class, () -> QLForum.fetchArticleFromPage(base(server), "/archives/no-content"),
                    "a page without the content container must not be swallowed");
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer serve() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
        return server;
    }

    private static String base(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /// Answers every request under {@code path} with the given UTF-8 body.
    private static void respond(HttpServer server, String path, String body) {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            } finally {
                exchange.close();
            }
        });
    }
}
