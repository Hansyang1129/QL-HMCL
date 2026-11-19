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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/// Client for the Qinglan transit forum, a public Halo CMS instance.
///
/// Posts are paginated without authentication through the anonymous content
/// API; a single post page additionally carries the rendered HTML body.
/// Everything runs over plain HTTP because HTTPS to this host does not answer;
/// each request is retried a few times before failing.
@NotNullByDefault
public final class QLForum {

    /// @return the configured forum base, or an error when this shell has no endpoint yet
    /// @throws IOException when {@code ql-endpoints.json} carries no {@code forumBase}
    public static String base() throws IOException {
        String configured = QLEndpoints.forumBase();
        if (configured == null)
            throw new IOException("论坛地址未配置 (ql-endpoints.json: forumBase)");
        return configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
    }

    private static String listApi() throws IOException {
        return base() + "/apis/api.content.halo.run/v1alpha1/posts";
    }

    private static String postApi() throws IOException {
        return base() + "/apis/api.content.halo.run/v1alpha1/posts";
    }

    private static final int RETRIES = 3;

    private QLForum() {
    }

    /// One forum post as shown in the list.
    ///
    /// @param name      stable post identifier used to fetch the full article
    /// @param title     post title
    /// @param author    display name of the publisher; {@code null} when absent
    /// @param publishTime publish timestamp as an ISO-8601 instant string; {@code null} when absent
    /// @param excerpt   plain-text summary; may be empty
    /// @param permalink relative permalink such as {@code /archives/<slug>}
    /// @param pinned    whether the post is pinned to the top
    /// @param visits    view counter reported by Halo; -1 when unknown (fallback list)
    /// @param upvotes   upvote counter reported by Halo; -1 when unknown (fallback list)
    /// @param fromRss   whether this post came from the RSS fallback list
    public record Post(String name, String title, @Nullable String author, @Nullable String publishTime,
                       String excerpt, String permalink, boolean pinned, int visits, int upvotes,
                       boolean fromRss) {

        /// Absolute URL of the post on the site, for "open in browser" actions.
        public String toUrl() {
            String configured = QLEndpoints.forumBase();
            return configured == null ? permalink : configured + permalink;
        }
    }

    /// Full content of a single forum post.
    ///
    /// @param name stable post identifier
    /// @param title post title
    /// @param author display name of the publisher; {@code null} when absent
    /// @param publishTime publish timestamp as an ISO-8601 instant string; {@code null} when absent
    /// @param html rendered HTML body as produced by Halo; may contain relative links
    ///             that should be turned absolute with {@link #base()}
    /// @param permalink relative permalink such as {@code /archives/<slug>}
    public record Article(String name, String title, @Nullable String author, @Nullable String publishTime,
                          String html, String permalink) {

        /// Base address used to resolve relative links inside {@code html}.
        public String base() {
            String configured = QLEndpoints.forumBase();
            return configured == null ? "" : configured;
        }
    }

    /// Fetches one page of posts, newest first.
    ///
    /// @param page 1-based page index
    /// @param size number of posts per page
    public static @Unmodifiable List<Post> fetchPosts(int page, int size) throws IOException {
        String body = doGetRetry(URI.create(listApi() + "?size=" + size + "&page=" + page + "&sort=publishTime,desc"));
        JsonObject root = parse(body, "post list");
        // error envelopes ({"detail", "status": 500}) carry no items
        if (!root.has("items"))
            throw new IOException("Forum list request failed: server answered an error envelope");
        JsonArray items = root.getAsJsonArray("items");

        List<Post> posts = new ArrayList<>();
        for (JsonElement element : items) {
            JsonObject item = element.getAsJsonObject();
            JsonObject metadata = item.getAsJsonObject("metadata");
            JsonObject spec = item.getAsJsonObject("spec");
            JsonObject status = item.getAsJsonObject("status");
            JsonObject owner = item.getAsJsonObject("owner");
            JsonObject stats = item.getAsJsonObject("stats");

            String name = JsonUtils.getString(metadata, "name");
            String title = JsonUtils.getString(spec, "title", "");
            if (name == null)
                continue;
            posts.add(new Post(
                    name,
                    title,
                    owner == null ? null : JsonUtils.getString(owner, "displayName"),
                    JsonUtils.getString(spec, "publishTime"),
                    JsonUtils.getString(status, "excerpt", ""),
                    JsonUtils.getString(status, "permalink", "/"),
                    JsonUtils.getBoolean(spec, "pinned", false),
                    JsonUtils.getInt(stats, "visit", 0),
                    JsonUtils.getInt(stats, "upvote", 0),
                    false));
        }
        return List.copyOf(posts);
    }

    /// Fallback post list built from the site's RSS feed.
    ///
    /// Halo's content API occasionally fails cluster-wide, while its RSS feed
    /// keeps serving. The feed lacks visit counters and pinned flags, and the
    /// link slug is not a valid post name for the content API, so posts opened
    /// from this list fetch their body from the rendered article page instead.
    public static @Unmodifiable List<Post> fetchPostsFromRss() throws IOException {
        return fetchPostsFromRss(base());
    }

    /// Same as {@link #fetchPostsFromRss()} but against the given base address, for tests.
    static @Unmodifiable List<Post> fetchPostsFromRss(String base) throws IOException {
        String body = doGetRetry(URI.create(base + "/rss.xml"));
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            document = factory.newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IOException("Received malformed forum RSS", e);
        }

        List<Post> posts = new ArrayList<>();
        org.w3c.dom.NodeList itemNodes = document.getElementsByTagName("item");
        for (int i = 0; i < itemNodes.getLength(); i++) {
            Element item = (Element) itemNodes.item(i);
            String title = textOf(item, "title");
            String link = textOf(item, "link");
            if (title.isEmpty() || link.isEmpty())
                continue;

            // absolute links only contribute their path part (the feed's
            // localhost links get rewritten to it naturally); a bare host
            // without any path degrades to "/" instead of a substring underflow
            String permalink;
            if (link.startsWith("http://") || link.startsWith("https://")) {
                int pathAt = link.indexOf('/', link.startsWith("https") ? 8 : 7);
                permalink = pathAt < 0 ? "/" : link.substring(pathAt);
            } else {
                permalink = link.trim();
            }

            org.w3c.dom.NodeList creators = item.getElementsByTagNameNS(
                    "http://purl.org/dc/elements/1.1/", "creator");
            String author = null;
            if (creators.getLength() > 0)
                author = creators.item(0).getTextContent();

            String publishTime = formatDate(textOf(item, "pubDate"));
            String description = textOf(item, "description");
            // drop the feed plugin's telemetry pixel and keep plain text only
            description = description.replaceAll("(?i)<img[^>]*plugins/feed[^>]*>", " ");
            String excerpt = stripExcerpt(description);

            String name = permalink.startsWith("/archives/") ? permalink.substring("/archives/".length()) : permalink;
            posts.add(new Post(name, title, author, publishTime, excerpt, permalink, false, -1, -1, true));
        }
        return List.copyOf(posts);
    }

    /// Fetches a post's body from its rendered public page.
    ///
    /// Used when the content API is down; the theme exposes the full body in an
    /// {@code <article id="content">} container.
    ///
    /// @param permalink relative permalink such as {@code /archives/<slug>}
    public static Article fetchArticleFromPage(String permalink) throws IOException {
        return fetchArticleFromPage(base(), permalink);
    }

    /// Same as {@link #fetchArticleFromPage(String)} but against the given base address, for tests.
    static Article fetchArticleFromPage(String base, String permalink) throws IOException {
        // callers hand this an RSS permalink, which must name an /archives/<slug>
        // address; anything else has no api-name equivalent to report (M7)
        if (!permalink.startsWith("/archives/"))
            throw new IOException("Forum article permalink is not an /archives/ address: " + permalink);
        String body = doGetRetry(URI.create(base + permalink));
        Matcher article = Pattern.compile(
                "(?s)<article\\b[^>]*id=\"content\"[^>]*>(.*?)</article>").matcher(body);
        String html = article.find() ? article.group(1) : "";
        if (html.isBlank())
            throw new IOException("Forum article page has no content container");

        String title = "";
        Matcher titleMatcher = Pattern.compile("(?i)<title>(.*?)</title>").matcher(body);
        String pageTitle = titleMatcher.find() ? titleMatcher.group(1) : "";
        // theme pages carry a " - 轻蓝轨交论坛" suffix
        String suffix = " - ";
        int suffixAt = pageTitle.indexOf(suffix);
        if (suffixAt > 0)
            pageTitle = pageTitle.substring(0, suffixAt);

        return new Article(permalink.substring("/archives/".length()), pageTitle, null, null, html, permalink);
    }

    /// Fetches a single post including its rendered HTML body.
    ///
    /// When the content API answers 404 for {@code name} (RSS slugs are not api
    /// names) callers should retry with {@link #fetchArticleFromPage(String)}.
    public static Article fetchArticle(String name) throws IOException {
        String body = doGetRetry(URI.create(postApi() + "/" + name));
        JsonObject root = parse(body, "article");
        if (!root.has("content"))
            throw new IOException("Forum article request failed: server answered an error envelope");
        JsonObject metadata = root.getAsJsonObject("metadata");
        JsonObject spec = root.getAsJsonObject("spec");
        JsonObject content = root.getAsJsonObject("content");
        JsonObject status = root.getAsJsonObject("status");
        JsonObject owner = root.getAsJsonObject("owner");

        return new Article(
                name,
                JsonUtils.getString(spec, "title", ""),
                owner == null ? null : JsonUtils.getString(owner, "displayName"),
                JsonUtils.getString(spec, "publishTime"),
                JsonUtils.getString(content, "content", ""),
                JsonUtils.getString(status, "permalink", "/"));
    }

    private static JsonObject parse(String body, String what) throws IOException {
        try {
            JsonObject element = JsonUtils.fromNonNullJson(JsonUtils.GSON, body, JsonObject.class);
            if (element != null)
                return element;
            throw new IOException("Forum " + what + " is not a JSON object");
        } catch (RuntimeException e) {
            throw new IOException("Received malformed forum " + what, e);
        }
    }

    /// Reads the text content of one child element of an RSS item.
    private static String textOf(Element item, String tag) {
        org.w3c.dom.NodeList nodes = item.getElementsByTagName(tag);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : "";
    }

    /// Formats an RSS pubDate (RFC 1123) into {@code yyyy-MM-dd}, or {@code null}.
    private static @Nullable String formatDate(String pubDate) {
        try {
            ZonedDateTime time = ZonedDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME);
            return time.toLocalDate().toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /// Turns an RSS description into a short plain-text excerpt.
    private static String stripExcerpt(String description) {
        String text = description
                .replaceAll("(?is)<br\\s*/?>", " ")
                .replaceAll("(?is)</(p|h[1-6]|li|blockquote)>", " ")
                .replaceAll("(?is)<[^>]*>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim();
        // markdown sources carry blank lines that would wrap into huge labels
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    /// Freshness of a cached forum listing; articles change far less often.
    private static final Duration LIST_TTL = Duration.ofMinutes(3);

    /// Freshness of a cached forum article.
    private static final Duration ARTICLE_TTL = Duration.ofHours(6);

    /// GET through the local cache: a fresh copy answers immediately, and the last copy is
    /// served when the forum is unreachable instead of failing the page.
    private static String doGetRetry(URI uri) throws IOException {
        Duration ttl = uri.getPath() != null && uri.getPath().startsWith("/api/") ? LIST_TTL : ARTICLE_TTL;
        IOException last = null;
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            try {
                return QLCache.fetch(uri, ttl).body();
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(600);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while fetching " + uri, interrupted);
                }
            }
        }
        throw new IOException("Failed to fetch " + uri + " after " + RETRIES + " attempts", last);
    }
}
