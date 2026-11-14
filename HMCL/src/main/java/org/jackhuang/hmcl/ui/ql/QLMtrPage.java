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
import com.jfoenix.controls.JFXCheckBox;
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXTextField;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.jackhuang.hmcl.ql.QLConstants;
import org.jackhuang.hmcl.ql.QLMtr;
import org.jackhuang.hmcl.ql.QLMtrRouter;
import org.jackhuang.hmcl.ql.QLMtrTimetable;
import org.jackhuang.hmcl.ql.QLPinyin;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.ClassTitle;
import org.jackhuang.hmcl.ui.construct.PageAware;
import org.jackhuang.hmcl.ui.construct.RadioChoiceList;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.construct.TabControl;
import org.jackhuang.hmcl.ui.construct.TabHeader;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Qinglan MTR transit page: station timetables, route timetables and route planning.
///
/// <p>The page follows the launcher's settings layout: a native navigation sidebar on
/// the left and the active lookup in the center, each tab a scroll pane of native
/// blocks. Choices are {@link JFXComboBox} popups whose rows are
/// {@link TwoLineListItem} blocks carrying a line-color swatch, paired with a
/// {@link JFXTextField} filter, and the results are cards of blocks as well. The filter
/// matches Chinese text, romanized names, full pinyin and pinyin initials (see {@link QLPinyin}).</p>
///
/// <p>Data comes from the game server's MTR map API. Path planning runs locally over the
/// cached network snapshot, so it needs no extra server round trip, and nothing here opens
/// a WebView.</p>
@NotNullByDefault
public class QLMtrPage extends DecoratorAnimatedPage implements DecoratorPage, PageAware {

    /// Newest real-time trains shown inside one platform card.
    private static final int TRAINS_PER_PLATFORM = 3;
    /// Number of interchange paths kept inside one route card.
    private static final int KEPT_PATHS = 5;

    /// How often the visible detail page re-queries so real-time arrivals keep moving.
    private static final Duration POLL_INTERVAL = Duration.seconds(20);

    /// Which lookup the station tab performs: live arrivals of one platform or the routes of the station.
    private enum Mode { PLATFORM, ROUTE }

    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("qinglan.mtr.nav")));

    private final SpinnerPane spinnerPane = new SpinnerPane();
    private final TransitionPane contentPane = new TransitionPane();
    private final JFXButton refreshButton = FXUtils.newRaisedButton(i18n("qinglan.status.refresh"));
    private final Label statusLabel = new Label();

    /// The by-platform / by-route switch of the station tab, carrying the hint text of both modes.
    private final RadioChoiceList<Mode> modeChoiceList = new RadioChoiceList<>();

    private final Picker<QLMtr.MtrStation> stationPicker;
    private final Picker<QLMtr.MtrRoute> routePicker;
    private final Picker<QLMtr.MtrStation> fromPicker;
    private final Picker<QLMtr.MtrStation> toPicker;

    private final VBox stationResults = new VBox(8);
    private final VBox routeResults = new VBox(8);
    private final VBox planResults = new VBox(8);

    private final TabHeader tabHeader;
    private final TabControl.Tab<ScrollPane> stationTab = new TabControl.Tab<>("station");
    private final TabControl.Tab<ScrollPane> routeTab = new TabControl.Tab<>("route");
    private final TabControl.Tab<ScrollPane> planTab = new TabControl.Tab<>("plan");
    private final ScrollPane stationPage;
    private final ScrollPane planPage;
    private final ScrollPane routePage;

    private boolean loading;
    private @Nullable QLMtr.SarData network;
    private @Nullable QLMtr.DeparturesData departures;

    /// Monotonic stamps, one per async-backed view: every render bumps its
    /// stamp, and a completed fetch whose stamp no longer matches means the
    /// user moved on while the request was in flight, so its answer is dropped.
    ///
    /// <p>One stamp per view keeps unrelated tabs (and rapid re-renders of one
    /// view) from vetoing each other's answers.</p>
    private int stationArrivalsGeneration;
    private int routeTimetableGeneration;
    private int planGeneration;

    /// True while a plan query is running; a second click must not stack web
    /// requests and Dijkstra loops on a slow network.
    private boolean planning;

    /// Re-renders the visible detail page; {@code null} while nothing should refresh.
    private @Nullable Runnable activePoll;

    /// True while a station/route arrivals fetch is running; the poll timer
    /// skips its tick instead of stacking another request, while interactive
    /// renders (picker changes) stay free to replace the in-flight one.
    private boolean arrivalsInFlight;

    /// The clock behind {@link #activePoll}, played only while this page is on screen.
    private final Timeline autoRefresh = new Timeline(new KeyFrame(POLL_INTERVAL, event -> {
        Runnable poll = activePoll;
        // A slow network (retries plus timeouts) can hold one arrivals batch
        // longer than the poll period; skipping the tick avoids stacking.
        if (poll != null && !loading && !arrivalsInFlight)
            poll.run();
    }));

    /// One load result: the snapshot plus the departure packs, which may be missing when
    /// only the static half of the map API answers.
    private record Bundle(QLMtr.SarData snapshot, @Nullable QLMtr.DeparturesData packs) {
    }

    public QLMtrPage() {
        autoRefresh.setCycleCount(Animation.INDEFINITE);

        stationPicker = new Picker<>(i18n("qinglan.mtr.search.station"),
                station -> QLMtr.primaryName(station.name()),
                station -> QLMtr.secondaryName(station.name()),
                QLMtr.MtrStation::color, ignored -> renderStationPage());
        routePicker = new Picker<>(i18n("qinglan.mtr.search.route"),
                route -> QLMtr.primaryName(route.name()),
                QLMtrPage::routeSecondaryOf,
                QLMtr.MtrRoute::color, ignored -> renderRoutePage());
        fromPicker = new Picker<>(i18n("qinglan.mtr.search.station"),
                station -> QLMtr.primaryName(station.name()),
                station -> QLMtr.secondaryName(station.name()),
                QLMtr.MtrStation::color, ignored -> renderPlanPrompt());
        toPicker = new Picker<>(i18n("qinglan.mtr.search.station"),
                station -> QLMtr.primaryName(station.name()),
                station -> QLMtr.secondaryName(station.name()),
                QLMtr.MtrStation::color, ignored -> renderPlanPrompt());

        stationResults.setFillWidth(true);
        routeResults.setFillWidth(true);
        planResults.setFillWidth(true);
        modeChoiceList.setChoices(
                new RadioChoiceList.Choice<>(i18n("qinglan.mtr.mode.platform"), Mode.PLATFORM)
                        .setSubtitle(i18n("qinglan.mtr.station.platform.hint")),
                new RadioChoiceList.Choice<>(i18n("qinglan.mtr.mode.route"), Mode.ROUTE)
                        .setSubtitle(i18n("qinglan.mtr.station.route.hint")));
        modeChoiceList.setSelectedValue(Mode.PLATFORM);
        modeChoiceList.selectedValueProperty().addListener((observable, oldValue, newValue) -> renderStationPage());

        stationPage = scroll(tabBody(i18n("qinglan.mtr.station.timetable"),
                stationPicker, modeChoiceList, stationResults));
        routePage = scroll(tabBody(i18n("qinglan.mtr.route.timetable"),
                routePicker, routeResults));
        planPage = scroll(tabBody(i18n("qinglan.mtr.plan"),
                planPickers(), planActions(), planResults));

        stationTab.setNodeSupplier(() -> stationPage);
        routeTab.setNodeSupplier(() -> routePage);
        planTab.setNodeSupplier(() -> planPage);

        tabHeader = new TabHeader(contentPane, stationTab, routeTab, planTab);
        tabHeader.select(stationTab, false);
        tabHeader.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            updatePoll();
            statusLabel.setText("");
        });

        AdvancedListBox sideBar = new AdvancedListBox()
                .startCategory(i18n("qinglan.mtr.section.timetable"))
                .addNavigationDrawerTab(tabHeader, stationTab, i18n("qinglan.mtr.station.timetable"), SVG.LISTS)
                .addNavigationDrawerTab(tabHeader, routeTab, i18n("qinglan.mtr.route.timetable"), SVG.TRIP)
                .startCategory(i18n("qinglan.mtr.section.nav"))
                .addNavigationDrawerTab(tabHeader, planTab, i18n("qinglan.mtr.plan"), SVG.EXPLORE);
        FXUtils.setLimitWidth(sideBar, 200);
        setLeft(sideBar);

        HBox header = new HBox(16, refreshButton, statusLabel);
        statusLabel.getStyleClass().add("subtitle-label");
        header.setPadding(new Insets(12, 16, 0, 16));
        header.setAlignment(Pos.CENTER_LEFT);

        spinnerPane.setContent(contentPane);

        VBox root = new VBox(0, header, spinnerPane);
        // The decorator background is a user image; a fully transparent page
        // lets its bright regions swallow themed text, while a fully opaque
        // one hides the picture entirely. The 80% surface keeps both.
        root.setStyle("-fx-background-color: -monet-surface-transparent-80;");
        VBox.setVgrow(spinnerPane, Priority.ALWAYS);
        setCenter(root);

        refreshButton.setOnAction(e -> refresh(true));
        renderStationPrompt();
        renderRoutePrompt();
        renderPlanPrompt();
        updatePoll();
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    @Override
    public void onPageShown() {
        refresh(false);
        autoRefresh.playFromStart();
    }

    @Override
    public void onPageHidden() {
        autoRefresh.stop();
    }

    // ---- the sidebar tabs --------------------------------------------------------

    /// One centered tab: a section title, then the controls and result blocks.
    private VBox tabBody(String title, Node... content) {
        List<Node> nodes = new ArrayList<>();
        nodes.add(new ClassTitle(title));
        nodes.addAll(List.of(content));
        VBox body = new VBox(12, nodes.toArray(new Node[0]));
        body.setPadding(new Insets(16));
        return body;
    }

    private static ScrollPane scroll(VBox content) {
        content.setFillWidth(true);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        FXUtils.smoothScrolling(scroll);
        return scroll;
    }

    /// Points the poll timer at the tab on screen; the plan tab needs no polling.
    private void updatePoll() {
        if (tabHeader.getSelectionModel().getSelectedItem() == routeTab)
            activePoll = this::renderRoutePage;
        else if (tabHeader.getSelectionModel().getSelectedItem() == stationTab)
            activePoll = this::renderStationPage;
        else
            activePoll = null;
    }

    // ---- level 2: route planning ----------------------------------------------

    /// The origin / destination pickers of the plan tab.
    private VBox planPickers() {
        return new VBox(8, muted(i18n("qinglan.mtr.plan.from")), fromPicker,
                muted(i18n("qinglan.mtr.plan.to")), toPicker);
    }

    /// The swap button, the web toggle, and the plan button of the plan tab.
    private HBox planActions() {
        JFXButton swap = FXUtils.newRaisedButton(i18n("qinglan.mtr.plan.swap"));
        swap.setOnAction(e -> {
            QLMtr.MtrStation from = fromPicker.selected();
            QLMtr.MtrStation to = toPicker.selected();
            fromPicker.pick(to);
            toPicker.pick(from);
        });

        JFXCheckBox webToggle = new JFXCheckBox(i18n("qinglan.mtr.plan.web.toggle"));
        webToggle.setSelected(true);

        JFXButton plan = FXUtils.newRaisedButton(i18n("qinglan.mtr.plan.button"));
        plan.setOnAction(e -> runPathQuery(webToggle.isSelected()));

        HBox row = new HBox(12, swap, webToggle, plan);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // ---- data -----------------------------------------------------------------

    /// Loads the network snapshot and the departure packs into the page cache.
    ///
    /// @param force reload even when a snapshot is already cached
    private void refresh(boolean force) {
        if (loading)
            return;
        if (network != null && !force)
            return;

        loading = true;
        boolean firstLoad = network == null;
        refreshButton.setDisable(true);
        if (firstLoad)
            spinnerPane.showSpinner();

        Task.supplyAsync(Schedulers.io(), () -> {
            QLMtr.SarData snapshot = QLMtr.fetchSar();
            QLMtr.DeparturesData packs;
            try {
                packs = QLMtr.fetchDepartures();
            } catch (Exception e) {
                // Departures are optional: the stop list still renders without them.
                packs = null;
            }
            return new Bundle(snapshot, packs);
        }).whenComplete(Schedulers.javafx(), (result, exception) -> {
            loading = false;
            refreshButton.setDisable(false);
            spinnerPane.hideSpinner();
            if (exception != null || result == null || result.snapshot() == null) {
                spinnerPane.setFailedReason(i18n("qinglan.mtr.failed"));
                statusLabel.setText("");
                return;
            }
            network = result.snapshot();
            departures = result.packs();
            statusLabel.setText(i18n("qinglan.status.refresh.done", LocalDateTime.now().format(QLConstants.WALL_CLOCK)));
            populate();
        }).start();
    }

    /// Hands the freshly loaded network to the four pickers and re-renders the pages.
    private void populate() {
        QLMtr.SarData snapshot = network;
        if (snapshot == null)
            return;

        Map<String, QLMtr.MtrStation> unique = new java.util.LinkedHashMap<>();
        for (QLMtr.MtrStation station : snapshot.stations())
            unique.putIfAbsent(station.id(), station);

        List<QLMtr.MtrRoute> routes = new ArrayList<>();
        for (QLMtr.MtrRoute route : snapshot.routes())
            if (!route.hidden() && route.stops().size() >= 2)
                routes.add(route);

        stationPicker.setChoices(List.copyOf(unique.values()));
        fromPicker.setChoices(List.copyOf(unique.values()));
        toPicker.setChoices(List.copyOf(unique.values()));
        routePicker.setChoices(routes);

        renderStationPage();
        renderRoutePage();
        renderPlanPrompt();
    }

    // ---- level 2: station timetable -------------------------------------------

    private void renderStationPrompt() {
        stationResults.getChildren().setAll(notice(i18n("qinglan.mtr.pick.station")));
    }

    private void renderStationPage() {
        QLMtr.MtrStation station = stationPicker.selected();
        if (station == null) {
            stationResults.getChildren().setAll(notice(i18n("qinglan.mtr.pick.station")));
            return;
        }
        if (modeChoiceList.getSelectedValue() == Mode.PLATFORM)
            loadPlatformArrivals(stationResults, station);
        else
            loadStationRoutes(stationResults, station);
    }

    /// The platform-first view of a station: one card per platform, its newest trains inside.
    ///
    /// <p>Real-time arrivals are requested for every platform of the station at
    /// once. Each answered platform becomes a card whose body lists the next
    /// trains in order; platforms without real-time data are skipped, as a
    /// silent card would carry no usable label.</p>
    private void loadPlatformArrivals(VBox results, QLMtr.MtrStation station) {
        results.getChildren().setAll(notice(i18n("qinglan.mtr.loading")));
        QLMtr.SarData snapshot = network;
        if (snapshot == null) {
            results.getChildren().setAll(notice(i18n("qinglan.mtr.failed")));
            return;
        }
        Set<String> groupIds = QLMtr.stationGroupIds(snapshot, station.id());

        int generation = ++stationArrivalsGeneration;
        arrivalsInFlight = true;
        Task.supplyAsync(Schedulers.io(), () ->
                        QLMtr.fetchArrivals(List.copyOf(groupIds), TRAINS_PER_PLATFORM))
                .whenComplete(Schedulers.javafx(), (rows, exception) -> {
                    // Guards against a poll or a picker move while the batch
                    // was in flight: only the newest render may paint, and it
                    // must still be showing this exact station.
                    if (generation == stationArrivalsGeneration)
                        arrivalsInFlight = false;
                    if (generation != stationArrivalsGeneration)
                        return;
                    QLMtr.MtrStation selected = stationPicker.selected();
                    if (selected == null || !selected.equals(station))
                        return;
                    if (rows == null || exception != null) {
                        results.getChildren().setAll(notice(i18n("qinglan.mtr.failed")));
                        return;
                    }

                    long now = System.currentTimeMillis();
                    Map<String, String> labels = new HashMap<>();
                    Map<String, List<QLMtr.MtrArrival>> trains = new HashMap<>();
                    for (QLMtr.MtrArrival arrival : rows) {
                        if (arrival.arrival() < now - 60_000)
                            continue;
                        labels.putIfAbsent(arrival.platformId(), arrival.platformName());
                        trains.computeIfAbsent(arrival.platformId(), ignored -> new ArrayList<>()).add(arrival);
                    }

                    List<Map.Entry<String, Node>> platformCards = new ArrayList<>();
                    for (Map.Entry<String, List<QLMtr.MtrArrival>> entry : trains.entrySet()) {
                        entry.getValue().sort(java.util.Comparator.comparingLong(QLMtr.MtrArrival::arrival));
                        List<Node> inner = new ArrayList<>();
                        inner.add(block(i18n("qinglan.mtr.platform.tag", labels.get(entry.getKey())),
                                null, 0, List.of()));
                        for (QLMtr.MtrArrival arrival : entry.getValue())
                            inner.add(arrivalRow(arrival));
                        platformCards.add(Map.entry(entry.getKey(), card(new VBox(4, inner.toArray(new Node[0])))));
                    }
                    platformCards.sort(java.util.Comparator.comparingInt(entry ->
                            platformOrder(labels.get(entry.getKey()))));
                    if (platformCards.isEmpty()) {
                        results.getChildren().setAll(notice(i18n("qinglan.mtr.no.arrivals")));
                        return;
                    }
                    results.getChildren().setAll(platformCards.stream().map(Map.Entry::getValue).toList());
                }).start();
    }

    /// Sort key of a platform label: its leading digits, or always-last when it has none.
    private static int platformOrder(String label) {
        StringBuilder digits = new StringBuilder();
        for (char c : label.toCharArray())
            if (Character.isDigit(c))
                digits.append(c);
        try {
            return digits.length() == 0 ? Integer.MAX_VALUE : Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /// One real-time arrival as a native row (no own card; the platform card surrounds it).
    private Node arrivalRow(QLMtr.MtrArrival arrival) {
        String line = arrival.routeNumber().isBlank()
                ? QLMtr.primaryName(arrival.routeName()) : arrival.routeNumber();
        List<String> tags = new ArrayList<>();
        tags.add(minutesText(arrival.arrival()));
        tags.add(i18n("qinglan.mtr.platform.tag", arrival.platformName()));
        if (arrival.realtime())
            tags.add(i18n("qinglan.mtr.realtime"));
        TwoLineListItem item = block(QLMtr.primaryName(arrival.destination()), line, arrival.routeColor(), tags);
        if (arrival.terminating())
            item.addTagWarning(i18n("qinglan.mtr.terminating"));
        return item;
    }

    /// The routes-first view of a station: one card per serving line.
    ///
    /// <p>The card lists the line, its direction and the next departure; native
    /// tag pills name the platforms of this station the line stops at, since a
    /// line may reach several platforms of one station. The stop records carry
    /// the platform labels themselves, so no extra request is needed.</p>
    private void loadStationRoutes(VBox results, QLMtr.MtrStation station) {
        QLMtr.SarData snapshot = network;
        if (snapshot == null) {
            results.getChildren().setAll(notice(i18n("qinglan.mtr.failed")));
            return;
        }

        // Every platform of this station counts, not just the one that was picked.
        Set<String> groupIds = QLMtr.stationGroupIds(snapshot, station.id());

        List<Node> cards = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (QLMtr.MtrRoute route : snapshot.routes()) {
            if (route.hidden() || route.stops().size() < 2)
                continue;

            List<String> labels = new ArrayList<>();
            int stopIndex = -1;
            for (int i = 0; i < route.stops().size(); i++) {
                if (!groupIds.contains(route.stops().get(i).stationId()))
                    continue;
                if (stopIndex < 0)
                    stopIndex = i;
                String label = route.stops().get(i).platformName();
                if (!labels.contains(label))
                    labels.add(label);
            }
            if (stopIndex < 0)
                continue;

            String nextText = i18n("qinglan.mtr.no.timetable");
            QLMtr.DeparturesData packs = departures;
            if (packs != null) {
                List<QLMtrTimetable.TimedDeparture> next =
                        QLMtrTimetable.nextAt(packs, route, stopIndex, now, 1);
                if (!next.isEmpty())
                    nextText = i18n("qinglan.mtr.next.train",
                            minutesText(next.get(0).arrivalAt()));
            }

            List<String> tags = new ArrayList<>();
            tags.add(nextText);
            for (String label : labels)
                tags.add(i18n("qinglan.mtr.platform.tag", label));
            cards.add(clickable(block(QLMtr.primaryName(route.name()),
                    QLMtr.routeDirection(route.name()), route.color(), tags),
                    () -> openRoutePage(route)));
        }
        if (cards.isEmpty())
            results.getChildren().setAll(notice(i18n("qinglan.mtr.no.route")));
        else
            results.getChildren().setAll(cards);
    }

    /// Deep link from a station: switch to the line tab with this line already chosen.
    ///
    /// <p>The picker's selection callback performs the render, so this must only
    /// change the selection and the shown tab — an extra render here would race
    /// the async timetable update into duplicated station cards.</p>
    private void openRoutePage(QLMtr.MtrRoute route) {
        routePicker.pick(route);
        tabHeader.select(routeTab, false);
    }

    /// Wraps content in the launcher's native card: a rounded surface with a soft shadow.
    private static Node card(Node content) {
        StackPane card = new StackPane(content);
        card.getStyleClass().add("card");
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    /// Wraps a card in the launcher's rippling container so it looks and feels clickable.
    private static Node clickable(Node content, Runnable action) {
        RipplerContainer container = new RipplerContainer(card(content));
        container.setCursor(Cursor.HAND);
        container.setOnMouseClicked(event -> {
            if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY && event.getClickCount() == 1)
                action.run();
        });
        return container;
    }

    // ---- level 2: route timetable ---------------------------------------------

    private void renderRoutePrompt() {
        routeResults.getChildren().setAll(notice(i18n("qinglan.mtr.pick.route")));
    }

    private void renderRoutePage() {
        if (routePicker.selected() == null) {
            renderRoutePrompt();
            return;
        }
        renderRouteTimetable(routeResults, routePicker.selected());
    }

    /// Renders one line: a header card, then one card per station of the line.
    ///
    /// <p>Every station card carries the station name (resolved from the snapshot,
    /// since the stop records only hold the platform label - that label becomes a
    /// pill), and the next departure that reaches this station - first from the
    /// real-time arrivals batch over the line's stops, else from the cached
    /// timetable packs. The list follows the line's stop order, so reading
    /// top-down is the ride.</p>
    private void renderRouteTimetable(VBox results, QLMtr.MtrRoute route) {
        QLMtr.SarData snapshot = network;
        if (snapshot == null) {
            results.getChildren().setAll(notice(i18n("qinglan.mtr.failed")));
            return;
        }
        // Station name of each platform-owned stop id; route stop names are labels.
        Map<String, String> stationNames = new HashMap<>();
        for (QLMtr.MtrStation station : snapshot.stations())
            stationNames.putIfAbsent(station.id(), QLMtr.primaryName(station.name()));

        results.getChildren().setAll(card(block(QLMtr.primaryName(route.name()),
                QLMtr.routeDirection(route.name()), route.color(), List.of())));

        List<String> stopIds = new ArrayList<>();
        for (QLMtr.MtrRouteStop stop : route.stops())
            stopIds.add(stop.stationId());

        int generation = ++routeTimetableGeneration;
        arrivalsInFlight = true;
        Task.supplyAsync(Schedulers.io(), () -> QLMtr.fetchArrivals(stopIds, 1))
                .whenComplete(Schedulers.javafx(), (rows, exception) -> {
                    // Only the newest render may paint, and it must still be
                    // showing this route; records rebuilt after a refresh are
                    // equal by value, so the check is an equality one.
                    if (generation == routeTimetableGeneration)
                        arrivalsInFlight = false;
                    if (generation != routeTimetableGeneration)
                        return;
                    QLMtr.MtrRoute current = routePicker.selected();
                    if (current == null || !current.equals(route))
                        return;
                    if (rows == null || exception != null) {
                        results.getChildren().setAll(notice(i18n("qinglan.mtr.failed")));
                        return;
                    }

                    QLMtr.DeparturesData packs = departures;
                    long now = System.currentTimeMillis();

                    // First real-time row per stop id: that stop's fresh next train.
                    Map<String, QLMtr.MtrArrival> live = new HashMap<>();
                    for (QLMtr.MtrArrival arrival : rows)
                        live.putIfAbsent(arrival.platformId(), arrival);

                    List<Node> children = new ArrayList<>();
                    children.add(card(block(QLMtr.primaryName(route.name()),
                            QLMtr.routeDirection(route.name()), route.color(), List.of())));
                    for (int i = 0; i < route.stops().size(); i++) {
                        QLMtr.MtrRouteStop stop = route.stops().get(i);
                        String nextText = i18n("qinglan.mtr.no.timetable");

                        QLMtr.MtrArrival row = live.get(stop.stationId());
                        if (row != null && row.arrival() >= now - 60_000) {
                            nextText = i18n("qinglan.mtr.next.train", minutesText(row.arrival()));
                        } else if (packs != null) {
                            List<QLMtrTimetable.TimedDeparture> next =
                                    QLMtrTimetable.nextAt(packs, route, i, now, 1);
                            if (!next.isEmpty())
                                nextText = i18n("qinglan.mtr.next.train",
                                        minutesText(next.get(0).arrivalAt()));
                        }
                        List<String> tags = new ArrayList<>();
                        tags.add(nextText);
                        tags.add(i18n("qinglan.mtr.platform.tag", stop.platformName()));

                        String title = stationNames.getOrDefault(stop.stationId(),
                                QLMtr.primaryName(stop.platformName()));
                        children.add(card(block(title, null, route.color(), tags)));
                    }
                    // One atomic paint of header and rows: appends raced the
                    // poll cycle into duplicated station cards before.
                    results.getChildren().setAll(children);
                }).start();
    }

    // ---- level 2: route planning ----------------------------------------------

    /// Restores the prompt while either endpoint of a plan is still missing.
    private void renderPlanPrompt() {
        if (fromPicker.selected() == null || toPicker.selected() == null)
            planResults.getChildren().setAll(notice(i18n("qinglan.mtr.plan.pick.first")));
    }

    /// One plan query result: the web's single path plus the local cached alternatives.
    ///
    /// The web reply is optional; when the finder is unreachable the local
    /// alternatives alone fill the page, which keeps planning usable offline.
    private record PlanBundle(@Nullable QLMtr.MtrDirections web,
                              @Unmodifiable List<QLMtrRouter.MtrPath> paths) {
    }

    private void runPathQuery(boolean webQuery) {
        QLMtr.MtrStation from = fromPicker.selected();
        QLMtr.MtrStation to = toPicker.selected();
        QLMtr.SarData snapshot = network;
        if (from == null || to == null || snapshot == null) {
            planResults.getChildren().setAll(notice(i18n("qinglan.mtr.plan.pick.first")));
            return;
        }
        // A query already in flight owns the view; rapid re-clicks must not
        // stack web requests and Dijkstra loops on a slow network.
        if (planning)
            return;
        planning = true;
        int generation = ++planGeneration;
        planResults.getChildren().setAll(notice(i18n("qinglan.mtr.loading")));

        // The web finder and the local Dijkstra loop both belong off the FX thread.
        String fromId = from.id();
        String toId = to.id();
        String fromName = from.name();
        String toName = to.name();
        Task.supplyAsync(Schedulers.io(), () -> {
            QLMtr.MtrDirections web = null;
            if (webQuery) {
                try {
                    web = QLMtr.fetchDirections(fromName, toName);
                } catch (Exception e) {
                    // the web finder is optional: the cached alternatives alone still render
                }
            }
            // Dijkstra runs up to KEPT_PATHS * 3 times over the whole network, which is far
            // too much work for the FX thread on a big map.
            return new PlanBundle(web, QLMtrRouter.findPaths(snapshot, fromId, toId, KEPT_PATHS));
        }).whenComplete(Schedulers.javafx(), (bundle, exception) -> {
            // A newer query owns the view now; it also resets the planning flag.
            if (generation != planGeneration)
                return;
            planning = false;
            if (bundle == null || exception != null) {
                planResults.getChildren().setAll(notice(i18n("qinglan.mtr.plan.none")));
                return;
            }
            QLMtrRouter.MtrPath webPath = bundle.web() == null ? null
                    : QLMtrRouter.directionsToPath(snapshot, bundle.web(),
                            QLMtr.primaryName(fromName), QLMtr.primaryName(toName));

            List<Node> cards = new ArrayList<>();
            int index = 1;
            if (webPath != null)
                cards.add(planBlock(index++, webPath, true));
            for (QLMtrRouter.MtrPath path : bundle.paths())
                cards.add(planBlock(index++, path, false));

            if (cards.isEmpty()) {
                planResults.getChildren().setAll(notice(i18n("qinglan.mtr.plan.none")));
                return;
            }
            planResults.getChildren().setAll(cards);
        }).start();
    }

    /// One itinerary as a native card: a headline row, then one row per leg.
    ///
    /// @param web whether the plan came from the server's directions finder
    private Node planBlock(int index, QLMtrRouter.MtrPath path, boolean web) {
        VBox inner = new VBox(8);
        inner.setFillWidth(true);

        List<String> tags = new ArrayList<>();
        if (web)
            tags.add(i18n("qinglan.mtr.plan.web"));
        inner.getChildren().add(block(
                i18n("qinglan.mtr.plan.total", Math.round(path.total() / 60000.0), path.transfers()),
                i18n("qinglan.mtr.plan.index", index), 0, tags));

        for (QLMtrRouter.MtrLeg leg : path.legs()) {
            if (leg.walk()) {
                inner.getChildren().add(block(i18n("qinglan.mtr.plan.walk"),
                        leg.fromStationName() + " → " + leg.toStationName(), 0, List.of()));
            } else {
                inner.getChildren().add(block(leg.routeName()
                                + (leg.routeNumber().isBlank() ? "" : "  " + leg.routeNumber()),
                        leg.fromStationName() + " → " + leg.toStationName(),
                        leg.routeColor(),
                        List.of(Math.round(leg.time() / 60000.0) + " " + i18n("qinglan.mtr.min"))));
            }
        }
        return card(inner);
    }

    // ---- shared block builders --------------------------------------------------

    /// A native two-line block with an optional line swatch and native tag pills.
    private static TwoLineListItem block(String title, @Nullable String subtitle, int color, List<String> tags) {
        TwoLineListItem item = new TwoLineListItem();
        item.setTitle(title);
        if (subtitle != null && !subtitle.isBlank())
            item.setSubtitle(subtitle);
        // Reach the tags before inserting the swatch: the first getTags() call rebuilds the
        // first line, which would silently drop a swatch inserted before it.
        for (String tag : tags)
            item.addTag(tag);
        if (color != 0)
            item.getFirstLine().getChildren().add(0, swatch(color));
        return item;
    }

    /// The colored square in front of a line name, drawn with a native shape node.
    private static Region swatch(int color) {
        Region swatch = new Region();
        swatch.setMinSize(12, 12);
        swatch.setMaxSize(12, 12);
        swatch.setStyle(swatchStyle(color));
        return swatch;
    }

    /// The background style of a line swatch in the display color of a route.
    private static String swatchStyle(int color) {
        return "-fx-background-color: #" + rgbHex(liftedColor(color)) + "; -fx-background-radius: 3;";
    }

    /// Lifts near-black route colors toward white so a line swatch stays visible on dark cards.
    ///
    /// <p>{@link QLMtr.MtrRoute} colors are raw sRGB values; a black line would make the square
    /// nearly invisible next to the card background, so the channels are mixed with white below
    /// a relative-luminance cap.</p>
    private static int liftedColor(int color) {
        int red = (color >>> 16) & 0xFF;
        int green = (color >>> 8) & 0xFF;
        int blue = color & 0xFF;
        double luminance = (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0;
        if (luminance >= 0.2)
            return color;
        double mix = Math.min(0.8, (0.2 - luminance) / 0.2);
        return ((int) Math.round(red + mix * (255 - red)) << 16)
                | ((int) Math.round(green + mix * (255 - green)) << 8)
                | (int) Math.round(blue + mix * (255 - blue));
    }

    /// A muted plain label (field labels, search misses).
    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("subtitle-label");
        return label;
    }

    /// A centered muted notice (empty states, loading status, failures).
    private static Label notice(String text) {
        Label label = muted(text);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setAlignment(Pos.CENTER);
        label.setTextAlignment(TextAlignment.CENTER);
        label.setWrapText(true);
        return label;
    }

    /// Romanized name plus direction: the secondary line and the search text of a route.
    private static String routeSecondaryOf(QLMtr.MtrRoute route) {
        String secondary = QLMtr.secondaryName(route.name());
        String direction = QLMtr.routeDirection(route.name());
        if (secondary == null)
            return direction;
        return direction.isEmpty() ? secondary : secondary + " " + direction;
    }

    private static String rgbHex(int color) {
        return String.format(Locale.ROOT, "%06X", color & 0xFFFFFF);
    }

    private static String minutesText(long epochMs) {
        long minutes = Math.round((epochMs - System.currentTimeMillis()) / 60000.0);
        return Math.max(0, minutes) + " " + i18n("qinglan.mtr.min");
    }

    // ---- the searchable choice ----------------------------------------------------

    /// A searchable choice as two native controls: a {@link JFXTextField} filter above a
    /// {@link JFXComboBox} whose rows are {@link TwoLineListItem} blocks. Typing narrows
    /// the list through {@link QLPinyin} (Chinese, romanization, full pinyin or initials),
    /// Enter picks the first remaining row, and the picked name stays visible in the combo.
    private static final class Picker<T> extends VBox {
        private final ObservableList<T> backing = FXCollections.observableArrayList();
        private final FilteredList<T> visible = new FilteredList<>(backing, item -> true);
        private final Map<T, String> searchKeys = new HashMap<>();
        private final JFXTextField search = new JFXTextField();
        private final JFXComboBox<T> choice = new JFXComboBox<>();
        private final Label noMatch = muted("");
        private final Function<T, String> primary;
        private final Function<T, String> secondary;
        private final Function<T, Integer> color;
        private final Consumer<T> onPicked;
        private @Nullable T lastPicked;

        Picker(String promptText, Function<T, String> primary, Function<T, String> secondary,
               Function<T, Integer> color, Consumer<T> onPicked) {
            this.primary = primary;
            this.secondary = secondary;
            this.color = color;
            this.onPicked = onPicked;

            setFillWidth(true);
            setSpacing(4);

            search.setPromptText(promptText);
            search.setMaxWidth(Double.MAX_VALUE);

            choice.setItems(visible);
            choice.setMaxWidth(Double.MAX_VALUE);
            choice.setConverter(new StringConverter<T>() {
                @Override
                public String toString(T item) {
                    if (item == null)
                        return "";
                    String second = secondary.apply(item);
                    String first = primary.apply(item);
                    return second == null || second.isBlank() ? first : first + " · " + second;
                }

                @Override
                public T fromString(String value) {
                    // The combo is never editable; the field above is the search input.
                    return null;
                }
            });
            choice.setCellFactory(listView -> new BlockCell());
            choice.setButtonCell(new BlockCell());

            search.textProperty().addListener((observable, oldValue, text) -> applyFilter(text));
            search.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.ENTER) {
                    if (!visible.isEmpty())
                        pick(visible.get(0));
                    event.consume();
                }
            });
            choice.setOnAction(event -> {
                T value = choice.getValue();
                if (value != null && !value.equals(lastPicked)) {
                    lastPicked = value;
                    onPicked.accept(value);
                }
            });

            noMatch.setVisible(false);
            noMatch.setManaged(false);

            getChildren().setAll(search, choice, noMatch);
        }

        /// @return the current selection, or {@code null} when nothing was picked yet.
        @Nullable
        T selected() {
            return choice.getValue();
        }

        /// Replaces the choices, keeping the selection when it still exists.
        void setChoices(List<T> items) {
            T previous = choice.getValue();
            searchKeys.clear();
            for (T item : items)
                searchKeys.put(item, QLPinyin.searchKey(primary.apply(item), secondary.apply(item)));
            backing.setAll(items);
            applyFilter("");
            if (previous != null && searchKeys.containsKey(previous))
                choice.setValue(previous);
        }

        /// Selects an item programmatically and reports it exactly like a click would.
        void pick(@Nullable T item) {
            if (item == null)
                return;
            search.setText("");
            applyFilter("");
            choice.setValue(item);
            choice.getSelectionModel().select(item);
            if (!item.equals(lastPicked)) {
                lastPicked = item;
                onPicked.accept(item);
            }
        }

        private void applyFilter(@Nullable String text) {
            visible.setPredicate(item -> QLPinyin.matches(text, searchKeys.getOrDefault(item, "")));
            boolean nothing = text != null && !text.isBlank() && visible.isEmpty();
            noMatch.setText(i18n("qinglan.mtr.no.match"));
            noMatch.setVisible(nothing);
            noMatch.setManaged(nothing);
        }

        /// One dropdown row, built from the launcher's own two-line block.
        private final class BlockCell extends ListCell<T> {
            private final Region swatch = QLMtrPage.swatch(0);
            private final TwoLineListItem item = new TwoLineListItem();
            private final HBox holder = new HBox(8, swatch, item);

            BlockCell() {
                HBox.setHgrow(item, Priority.ALWAYS);
                holder.setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(T value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(null);
                item.setTitle(primary.apply(value));
                String second = secondary.apply(value);
                item.setSubtitle(second == null || second.isBlank() ? null : second);
                int colorValue = color.apply(value);
                swatch.setStyle(swatchStyle(colorValue));
                swatch.setVisible(colorValue != 0);
                setGraphic(holder);
            }
        }
    }
}
