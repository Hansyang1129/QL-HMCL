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
package org.jackhuang.hmcl.ui.ql;

import com.jfoenix.controls.JFXButton;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.jackhuang.hmcl.ql.QLConstants;
import org.jackhuang.hmcl.ql.QLForum;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.animation.ContainerAnimations;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.JFXHyperlink;
import org.jackhuang.hmcl.ui.construct.PageAware;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.construct.TabControl;
import org.jackhuang.hmcl.ui.construct.TabHeader;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Qinglan transit forum page: a post list that opens into a native article view.
///
/// The page wears the launcher's settings layout: a navigation sidebar picks
/// the post list, and an article replaces the center pane while the tab
/// selection stays. The list comes from Halo's anonymous
/// content API and article bodies are re-rendered into plain native controls
/// (headings, paragraphs, quotes, monotext and hyperlinks) instead of a
/// WebView, as mandated for this launcher.
@NotNullByDefault
public class QLForumPage extends DecoratorAnimatedPage implements DecoratorPage, PageAware {

    /// Number of posts one list page asks the forum API (and RSS page) for.
    private static final int PAGE_SIZE = 20;

    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("qinglan.forum.nav")));

    private final TransitionPane contentPane = new TransitionPane();
    private final TabControl.Tab<SpinnerPane> listTab = new TabControl.Tab<>("posts");
    private final TabHeader tabHeader;
    private final SpinnerPane listPane = new SpinnerPane();
    private final VBox postsBox = new VBox(10);
    private final Label statusLabel = new Label();

    private boolean loading;
    private int nextPage;
    /// Whether the list API has exhausted its pages; the RSS fallback always delivers everything.
    private boolean finished;
    /// The "load more" row currently appended below the post cards, or null when the list is complete.
    private @Nullable HBox moreRow;

    /// Whether the post list is currently visible; derived from the content pane so
    /// returning through the sidebar tab (which never calls showList) still counts.
    private boolean listShown() {
        return contentPane.getCurrentNode() == listPane;
    }

    public QLForumPage() {
        postsBox.setFillWidth(true);

        VBox listInner = new VBox(12, statusLabel, postsBox);
        listInner.setPadding(new Insets(16));
        listInner.setFillWidth(true);

        ScrollPane listScroll = new ScrollPane(listInner);
        listScroll.setFitToWidth(true);
        FXUtils.smoothScrolling(listScroll);

        listPane.setContent(listScroll);

        listTab.setNodeSupplier(() -> listPane);
        tabHeader = new TabHeader(contentPane, listTab);
        tabHeader.select(listTab, false);

        AdvancedListBox sideBar = new AdvancedListBox()
                .startCategory(i18n("qinglan.forum.nav"))
                .addNavigationDrawerTab(tabHeader, listTab, i18n("qinglan.forum.posts"), SVG.FORMAT_LIST_BULLETED);
        FXUtils.setLimitWidth(sideBar, 200);

        AdvancedListBox toolBar = new AdvancedListBox()
                .addNavigationDrawerItem(i18n("qinglan.status.refresh"), SVG.REFRESH, this::refresh);
        FXUtils.setLimitHeight(toolBar, 40 + 12 * 2);

        setLeft(sideBar, toolBar);
        VBox.setVgrow(contentPane, Priority.ALWAYS);
        setCenter(contentPane);
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /// Reloads the first page every time the page becomes visible.
    @Override
    public void onPageShown() {
        refresh();
    }

    /// Reloads the post list back to its first page.
    ///
    /// When Halo's content API fails (it has failed cluster-wide before), the
    /// list is rebuilt from the site's RSS feed instead, which keeps serving.
    public void refresh() {
        if (loading)
            return;
        loading = true;
        nextPage = 1;
        listPane.showSpinner();
        Task.supplyAsync(Schedulers.io(), () -> {
            try {
                return QLForum.fetchPosts(1, PAGE_SIZE);
            } catch (IOException e) {
                return QLForum.fetchPostsFromRss();
            }
        })
                .whenComplete(Schedulers.javafx(), (posts, exception) -> {
                loading = false;
                listPane.hideSpinner();
                if (!listShown())
                    return;
                if (posts == null || posts.isEmpty() || exception != null) {
                    listPane.setFailedReason(i18n("qinglan.forum.failed"));
                    statusLabel.setText("");
                    return;
                }
                if (posts.get(0).fromRss()) {
                    finished = true; // rss fallback lists everything at once
                    statusLabel.setText(i18n("qinglan.forum.rss"));
                } else {
                    finished = posts.size() < PAGE_SIZE;
                    statusLabel.setText(i18n("qinglan.status.refresh.done",
                            LocalDateTime.now().format(QLConstants.WALL_CLOCK)));
                }
                rebuildList(posts);
            }).start();
    }

    /// Appends the next page of posts below the current list.
    private void fetchMore() {
        if (loading || finished || nextPage < 1)
            return;
        loading = true;
        int myPage = nextPage + 1;
        Task.supplyAsync(Schedulers.io(), () -> QLForum.fetchPosts(myPage, PAGE_SIZE))
                .whenComplete(Schedulers.javafx(), (posts, exception) -> {
                    loading = false;
                    if (!listShown())
                        return;
                    if (posts == null || exception != null) {
                        statusLabel.setText(i18n("qinglan.forum.failed"));
                        return;
                    }
                    nextPage = myPage;
                    finished = posts.size() < PAGE_SIZE;
                    statusLabel.setText("");
                    List<Node> rows = new ArrayList<>();
                    for (QLForum.Post post : posts)
                        rows.add(new ForumRow(post));
                    HBox more = moreRow;
                    // insert before the "load more" row; when it is gone, append at the end
                    int insertAt = more != null ? postsBox.getChildren().indexOf(more) : postsBox.getChildren().size();
                    postsBox.getChildren().addAll(insertAt, rows);
                    if (finished && more != null)
                        postsBox.getChildren().remove(more);
                }).start();
    }

    private void rebuildList(List<QLForum.Post> posts) {
        postsBox.getChildren().clear();
        for (QLForum.Post post : posts)
            postsBox.getChildren().add(new ForumRow(post));
        if (!finished) {
            JFXButton moreButton = FXUtils.newRaisedButton(i18n("qinglan.forum.more"));
            moreButton.setOnAction(e -> fetchMore());
            moreRow = new HBox(moreButton);
            moreRow.setAlignment(Pos.CENTER);
            postsBox.getChildren().add(moreRow);
        } else {
            moreRow = null;
        }
    }

    /// Opens a single post; back always returns to the list view.
    ///
    /// RSS-fallback posts cannot reach the content API (their slug is not an
    /// api name), so their body is scraped from the rendered article page. For
    /// API-list posts the api is tried first and the page scrape follows when
    /// the api fails, which also self-heals into the api once Halo recovers.
    private void openPost(QLForum.Post post) {
        if (loading)
            return;
        loading = true;
        listPane.showSpinner();
        Task.supplyAsync(Schedulers.io(), () -> {
            if (!post.fromRss()) {
                try {
                    return QLForum.fetchArticle(post.name());
                } catch (IOException e) {
                    // fall through to the rendered page
                }
            }
            return QLForum.fetchArticleFromPage(post.permalink());
        })
                .whenComplete(Schedulers.javafx(), (article, exception) -> {
                    loading = false;
                    listPane.hideSpinner();
                    if (article == null || exception != null) {
                        listPane.setFailedReason(i18n("qinglan.forum.failed"));
                        return;
                    }
                    showDetail(article);
                }).start();
    }

    private void showDetail(QLForum.Article article) {
        JFXButton backButton = FXUtils.newRaisedButton(i18n("qinglan.forum.back"));
        backButton.setOnAction(e -> showList());

        JFXHyperlink openInBrowser = new JFXHyperlink(i18n("qinglan.forum.open"));
        openInBrowser.setOnAction(e -> FXUtils.openLink(article.base() + article.permalink()));

        Label titleLabel = new Label(article.title());
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 18px;");
        titleLabel.setWrapText(true);

        Label metaLabel = new Label(articleMetaText(article));
        metaLabel.setStyle("-fx-opacity: 0.55; -fx-font-size: 12px;");

        VBox body = new VBox(10);
        body.setFillWidth(true);
        body.getChildren().setAll(renderHtml(article));

        VBox content = new VBox(12, new HBox(10, backButton, openInBrowser), titleLabel, metaLabel, body);
        content.setPadding(new Insets(16));
        content.setFillWidth(true);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        FXUtils.smoothScrolling(scroll);

        contentPane.setContent(scroll, ContainerAnimations.FADE);
    }

    private void showList() {
        contentPane.setContent(listPane, ContainerAnimations.NONE);
    }

    private static String articleMetaText(QLForum.Article article) {
        StringBuilder sb = new StringBuilder();
        if (article.author() != null)
            sb.append(article.author()).append(" · ");
        String time = article.publishTime();
        if (time != null && time.length() >= 10)
            sb.append(time, 0, 10);
        return sb.toString();
    }

    private static final Pattern BLOCK = Pattern.compile(
            "(?is)<(h[1-6]|p|li|blockquote|pre|table)\\b[^>]*>(.*?)</\\1>");

    /// Turns the article HTML into native nodes: headings, paragraphs, quotes,
    /// bullet items, monotext, tables, images and hyperlinks. Unmatched
    /// fragments become paragraphs.
    private static List<Node> renderHtml(QLForum.Article article) {
        List<Node> nodes = new ArrayList<>();
        String html = article.html();
        Matcher matcher = BLOCK.matcher(html);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last)
                appendBlock(nodes, "p", html.substring(last, matcher.start()));
            appendBlock(nodes, matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2));
            last = matcher.end();
        }
        if (last < html.length())
            appendBlock(nodes, "p", html.substring(last));
        if (nodes.isEmpty())
            nodes.add(new Label(i18n("qinglan.forum.empty")));
        return nodes;
    }

    private static void appendBlock(List<Node> nodes, String tag, String inner) {
        // markdown bodies come from the rendered Halo page; blank segments left
        // over from markdown paragraph separators must not become empty rows
        // (standalone img blocks belong here too but carry a picture to show)
        if (inner.matches("(?is)^\\s*<(hr|br)[^>]*/?>\\s*$"))
            return;

        switch (tag) {
            case "pre" -> {
                Label code = new Label(stripTags(inner));
                code.setStyle("-fx-font-family: Consolas; -fx-font-size: 12px;");
                code.setWrapText(true);
                nodes.add(code);
            }
            case "table" -> nodes.add(tableOf(inner));
            case "li" -> {
                TextFlow flow = flowOf(inner);
                flow.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(flow, Priority.ALWAYS);
                nodes.add(new HBox(4, new Label("• "), flow));
            }
            case "blockquote" -> {
                TextFlow flow = flowOf(inner);
                flow.setStyle("-fx-padding: 2 0 2 12; -fx-opacity: 0.75;");
                nodes.add(flow);
            }
            case "h1", "h2", "h3" -> {
                int size = switch (tag) {
                    case "h1" -> 17;
                    case "h2" -> 15;
                    default -> 14;
                };
                Label heading = new Label(stripTags(inner));
                heading.setWrapText(true);
                heading.setStyle("-fx-font-weight: bold; -fx-font-size: " + size + "px;");
                nodes.add(heading);
            }
            default -> {
                TextFlow flow = flowOf(inner);
                if (flow.getChildren().isEmpty())
                    return;
                flow.setMaxWidth(Double.MAX_VALUE);
                nodes.add(flow);
            }
        }
    }

    /// Upper bound for article images so they stay inside the scroll pane.
    private static final double IMAGE_MAX_WIDTH = 560;

    /// In-memory cache of article images, keyed by the resolved URL, holding
    /// the downscaled bitmaps so re-renders never re-download.
    private static final Map<String, Image> IMAGE_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
                    return size() > 40;
                }
            });

    /// Shows one article image: a browser link as placeholder while it loads,
    /// the fetched picture afterwards; failed loads keep the link as fallback
    /// and drop the cache entry so a later render would retry.
    private static Node imageNode(String src) {
        String url = absoluteUrl(src);
        Image cached = IMAGE_CACHE.get(url);
        Image image = cached != null ? cached
                // demanded width downscales while decoding, so giant photos
                // stay cheap in memory
                : new Image(url, IMAGE_MAX_WIDTH, 0, true, false, true);
        if (cached == null)
            IMAGE_CACHE.put(url, image);

        Node placeholder = linkNode(url, i18n("qinglan.forum.image"));
        StackPane box = new StackPane(placeholder);
        box.setAlignment(Pos.CENTER_LEFT);

        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);

        if (imageSettled(box, view, image, url))
            return box;
        if (cached != null)
            return box;

        // only an in-flight, uncached image needs a progress listener; it tears
        // itself down with the load so the cache entry can never pin a dead UI
        // subtree (M17)
        image.progressProperty().addListener(new ChangeListener<>() {
            @Override
            public void changed(ObservableValue<? extends Number> observable, Number oldV, Number newV) {
                if (imageSettled(box, view, image, url))
                    image.progressProperty().removeListener(this);
            }
        });
        return box;
    }

    /// Swaps the placeholder for the picture once the load has finished, drops
    /// a failed image from the cache, and reports whether the image is settled
    /// (complete or errored) so the caller can remove its listener.
    private static boolean imageSettled(StackPane box, ImageView view, Image image, String url) {
        if (image.isError()) {
            IMAGE_CACHE.remove(url);
            return true;
        }
        if (image.getProgress() >= 1.0 && image.getWidth() > 0) {
            view.setFitWidth(image.getWidth());
            box.getChildren().setAll(view);
            return true;
        }
        return false;
    }

    /// One parsed row of a table: the cells as HTML fragments, whether the row
    /// carries header cells, and per-cell colwidth hints (empty when absent).
    private record TableRow(List<String> cells, List<Double> hints, boolean header) {
    }

    private static final Pattern TABLE_ROW = Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr>");
    private static final Pattern TABLE_CELL = Pattern.compile("(?is)<t([dh])\\b([^>]*)>(.*?)</t\\1>");
    private static final Pattern TABLE_COL = Pattern.compile("(?is)<col\\b[^>]*width:\\s*(\\d+)px");

    /// Renders a table as a native grid of rich-text cells inside a card.
    ///
    /// <p>Column widths follow the editor's colwidth hints first, then their
    /// cell text weight; rows close with the native divider line, the header
    /// is bold and its divider is one step stronger.</p>
    private static Node tableOf(String html) {
        List<TableRow> rows = new ArrayList<>();
        Matcher rowMatcher = TABLE_ROW.matcher(html);
        while (rowMatcher.find())
            rows.add(parseRow(rowMatcher.group(1)));

        // a thead-less table still makes its first row the header
        boolean anyHeader = false;
        for (TableRow row : rows)
            anyHeader |= row.header();
        if (!rows.isEmpty() && !anyHeader)
            rows.set(0, new TableRow(rows.get(0).cells(), rows.get(0).hints(), true));

        int columns = 1;
        for (TableRow row : rows)
            columns = Math.max(columns, row.cells().size());

        // column groups of the editor usually cover every column
        List<Double> colGroup = new ArrayList<>();
        Matcher colMatcher = TABLE_COL.matcher(html);
        while (colMatcher.find())
            colGroup.add(Double.parseDouble(colMatcher.group(1)));

        double[] weights = new double[columns];
        boolean[] hinted = new boolean[columns];
        for (int i = 0; i < columns; i++) {
            if (i < colGroup.size() && colGroup.get(i) > 0) {
                weights[i] = colGroup.get(i);
                hinted[i] = true;
                continue;
            }
            // explicit colwidth hints from the first keeper of each column
            for (TableRow row : rows)
                if (i < row.hints().size() && row.hints().get(i) > 0) {
                    weights[i] = Math.max(weights[i], row.hints().get(i));
                    hinted[i] = true;
                }
        }
        for (int c = 0; c < columns; c++) {
            if (hinted[c])
                continue;
            // estimate from the longest cell payload (CJK characters count double)
            double weight = 2;
            for (TableRow row : rows)
                if (c < row.cells().size())
                    weight = Math.max(weight, textWeight(row.cells().get(c)));
            weights[c] = weight;
        }
        double total = 0;
        for (double w : weights)
            total += w;
        if (total <= 0)
            total = columns;

        GridPane grid = new GridPane();
        grid.setMaxWidth(Double.MAX_VALUE);
        for (int c = 0; c < columns; c++) {
            ColumnConstraints constraint = new ColumnConstraints();
            constraint.setPercentWidth(100 * weights[c] / total);
            grid.getColumnConstraints().add(constraint);
        }

        int size = rows.size();
        for (int r = 0; r < size; r++) {
            TableRow row = rows.get(r);
            boolean last = r == size - 1;
            for (int c = 0; c < columns; c++) {
                String cellHtml = c < row.cells().size() ? row.cells().get(c) : "";
                TextFlow flow = cellHtml.isBlank() ? new TextFlow() : flowOf(cellHtml);
                if (row.header())
                    boldify(flow);
                flow.setMaxWidth(Double.MAX_VALUE);

                HBox cell = new HBox(flow);
                HBox.setHgrow(flow, Priority.ALWAYS);
                cell.setAlignment(Pos.CENTER_LEFT);
                cell.setPadding(new Insets(8, 10, 8, 10));
                if (!last) {
                    String line = row.header() ? "-monet-outline" : "-monet-outline-variant";
                    cell.setStyle("-fx-border-color: transparent transparent " + line
                            + " transparent; -fx-border-width: 0 0 " + (row.header() ? 2 : 1) + " 0;");
                }
                grid.add(cell, c, r);
            }
        }

        return card(grid);
    }

    /// Parses one {@code <tr>} payload into its cells and width hints.
    private static TableRow parseRow(String rowHtml) {
        List<String> cells = new ArrayList<>();
        List<Double> hints = new ArrayList<>();
        boolean header = false;

        Matcher cellMatcher = TABLE_CELL.matcher(rowHtml);
        while (cellMatcher.find()) {
            if ("h".equals(cellMatcher.group(1)))
                header = true;
            String attrs = cellMatcher.group(2) == null ? "" : cellMatcher.group(2);
            String width = attrOf(attrs, "colwidth");
            hints.add(width.isBlank() ? 0 : parseDoubleSafe(width));
            cells.add(cellMatcher.group(3));
        }
        return new TableRow(cells, hints, header);
    }

    private static double parseDoubleSafe(String raw) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /// Approximate display weight of a cell's text (CJK characters count double).
    private static double textWeight(String cellHtml) {
        String text = stripTags(cellHtml);
        double weight = 2;
        for (int i = 0; i < text.length(); i++)
            weight += text.charAt(i) > 0x2E80 ? 2 : 1;
        return Math.min(weight, 60);
    }

    /// Makes every plain text run inside a flow bold for header cells.
    private static void boldify(TextFlow flow) {
        for (Node part : flow.getChildren())
            if (part instanceof Text text)
                text.setStyle(text.getStyle() + " -fx-font-weight: bold;");
    }

    /// Wraps content in the launcher's native card: a rounded surface with a soft shadow.
    private static Node card(Node content) {
        StackPane card = new StackPane(content);
        card.getStyleClass().add("card");
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    /// Inline tag scan order matters: links and images are self-contained,
    /// emphasis tags wrap richer inline content. Void elements ({@code img},
    /// {@code br}) never carry closing tags, so the alternatives fall back to
    /// a bare {@code >} once self-closing and paired forms are ruled out.
    private static final Pattern INLINE_TAG = Pattern.compile(
            "(?is)<(a|img|strong|b|em|i|code|del|s|br)\\b[^>]*?(?:/>|>(.*?)</\\1\\s*>|>)");

    /// Renders one HTML fragment as a rich text flow: markdown-style bold,
    /// italic, strikethrough, inline code, links, images and line breaks.
    /// Plain text pieces keep the theme text color; links open in the browser.
    private static TextFlow flowOf(String inner) {
        TextFlow flow = new TextFlow();
        Matcher matcher = INLINE_TAG.matcher(inner);
        int last = 0;
        while (matcher.find()) {
            appendPlain(flow, inner.substring(last, matcher.start()));
            String tag = matcher.group(1).toLowerCase(Locale.ROOT);
            String middle = matcher.group(2);
            switch (tag) {
                case "a" -> {
                    String href = attrOf(matcher.group(), "href");
                    String text = stripTags(middle == null ? "" : middle).replaceAll("\\s+", " ");
                    flow.getChildren().add(linkNode(href, text.isEmpty() ? href : text));
                }
                case "img" -> {
                    String src = attrOf(matcher.group(), "src");
                    if (!src.isBlank())
                        flow.getChildren().add(imageNode(src));
                }
                case "br" -> {
                    Text lineBreak = new Text("\n");
                    lineBreak.setStyle("-fx-fill: -monet-on-surface;");
                    flow.getChildren().add(lineBreak);
                }
                case "code" -> addStyled(flow, middle, false, false, false, true);
                case "strong", "b" -> addStyled(flow, middle, true, false, false, false);
                case "em", "i" -> addStyled(flow, middle, false, true, false, false);
                case "del", "s" -> addStyled(flow, middle, false, false, true, false);
            }
            last = matcher.end();
        }
        appendPlain(flow, inner.substring(last));
        return flow;
    }

    /// Adds one styled text run; emphasis tags may contain further inline tags,
    /// so the text carries only the plain payload.
    private static void addStyled(TextFlow flow, String raw, boolean bold, boolean italic,
                                  boolean strike, boolean mono) {
        String text = stripTags(raw == null ? "" : raw).replaceAll("\\s+", " ");
        if (text.isBlank())
            return;
        Text run = new Text(text);
        run.setStyle("-fx-fill: -monet-on-surface;"
                + (bold ? " -fx-font-weight: bold;" : "")
                + (italic ? " -fx-font-style: italic;" : "")
                + (strike ? " -fx-strikethrough: true;" : "")
                + (mono ? " -fx-font-family: Consolas;" : ""));
        flow.getChildren().add(run);
    }

    private static void appendPlain(TextFlow flow, String raw) {
        String text = stripTags(raw).replaceAll("\\s+", " ");
        if (!text.isBlank()) {
            Text run = new Text(text);
            run.setStyle("-fx-fill: -monet-on-surface;");
            flow.getChildren().add(run);
        }
    }

    private static Node linkNode(String href, String text) {
        Text link = new Text(text);
        link.setStyle("-fx-fill: -monet-primary; -fx-underline: true;");
        String url = absoluteUrl(href);
        // article phrasing is admin-owned, but still only hand scheme-whitelisted
        // links to the browser (M19); anything else renders as plain text
        if (url.startsWith("http://") || url.startsWith("https://")) {
            link.setCursor(Cursor.HAND);
            link.setOnMouseClicked(e -> FXUtils.openLink(url));
        }
        return link;
    }

    private static String attrOf(String tag, String name) {
        Pattern pattern = ATTRIBUTE_PATTERNS.computeIfAbsent(name,
                n -> Pattern.compile("(?i)" + n + "\\s*=\\s*\"([^\"]*)\""));
        Matcher matcher = pattern.matcher(tag);
        return matcher.find() ? decodeEntities(matcher.group(1)) : "";
    }

    /// Patterns for {@link #attrOf}, cached per attribute name.
    private static final ConcurrentHashMap<String, Pattern> ATTRIBUTE_PATTERNS = new ConcurrentHashMap<>();

    /// Numeric HTML entities in decimal ({@code &#39;}) or hex ({@code &#x27;}) form.
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    /// Decodes the handful of HTML entities that attribute values carry:
    /// numeric entities first, then the named ones; {@code &amp;} goes last
    /// so escaped sequences stay single-decoded.
    private static String decodeEntities(String raw) {
        String decoded = NUMERIC_ENTITY.matcher(raw).replaceAll(match -> {
            int code = Integer.parseInt(match.group(2), match.group(1) != null ? 16 : 10);
            return Character.isValidCodePoint(code) ? String.valueOf(Character.toChars(code)) : "";
        });
        return decoded.replace("&apos;", "'")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
    }

    private static String absoluteUrl(String url) {
        if (url.startsWith("http://localhost:8090"))
            return QLForum.BASE + url.substring("http://localhost:8090".length());
        if (url.startsWith("https://localhost:8090"))
            return QLForum.BASE + url.substring("https://localhost:8090".length());
        if (url.startsWith("/"))
            return QLForum.BASE + url;
        return url;
    }

    private static String stripTags(String html) {
        String text = html.replaceAll("(?is)<br\\s*/?>", " ").replaceAll("(?is)</(p|h[1-6]|li|blockquote)>", " ");
        text = text.replaceAll("(?is)<[^>]*>", "");
        return text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim();
    }

    private final class ForumRow extends VBox {
        private final QLForum.Post post;

        ForumRow(QLForum.Post post) {
            this.post = post;

            VBox inner = new VBox(4);
            inner.setFillWidth(true);

            HBox titleRow = new HBox(8);
            titleRow.setAlignment(Pos.CENTER_LEFT);
            Label titleLabel = new Label(post.title());
            titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
            titleLabel.setWrapText(true);
            HBox.setHgrow(titleLabel, Priority.ALWAYS);
            titleRow.getChildren().add(titleLabel);
            if (post.pinned()) {
                Label pinned = new Label(i18n("qinglan.forum.pinned"));
                pinned.setStyle("-fx-font-size: 12px; -fx-text-fill: -monet-primary;");
                titleRow.getChildren().add(pinned);
            }
            inner.getChildren().add(titleRow);

            String excerpt = post.excerpt();
            if (excerpt != null && !excerpt.isBlank()) {
                Label excerptLabel = new Label(excerpt);
                excerptLabel.setWrapText(true);
                excerptLabel.setStyle("-fx-opacity: 0.75;");
                inner.getChildren().add(excerptLabel);
            }

            Label metaLabel = new Label(metaText());
            metaLabel.setStyle("-fx-opacity: 0.55; -fx-font-size: 12px;");
            inner.getChildren().add(metaLabel);

            // JFXButton mis-sizes wrap-text graphics (prefHeight explodes into
            // hundreds of px of dead space); layout the card manually instead
            StackPane card = new StackPane();
            card.getStyleClass().add("card");
            card.setMaxWidth(Double.MAX_VALUE);
            FXUtils.onClicked(card, () -> openPost(post));
            card.getChildren().add(inner);

            setSpacing(4);
            setFillWidth(true);
            getChildren().setAll(card);
        }

        private String metaText() {
            StringBuilder sb = new StringBuilder();
            if (post.author() != null)
                sb.append(post.author()).append(" · ");
            String time = post.publishTime();
            if (time != null && time.length() >= 10)
                sb.append(time, 0, 10);
            if (post.visits() >= 0) {
                if (sb.length() > 0)
                    sb.append(" · ");
                sb.append(i18n("qinglan.forum.views", post.visits()));
            }
            return sb.toString();
        }
    }
}
