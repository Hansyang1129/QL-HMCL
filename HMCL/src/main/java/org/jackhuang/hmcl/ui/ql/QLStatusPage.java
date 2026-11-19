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
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.jackhuang.hmcl.ql.QLConstants;
import org.jackhuang.hmcl.ql.QLServerStatus;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.construct.PageAware;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.LocalDateTime;
import java.util.List;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Qinglan server status page: pings the fleet and shows latencies/players.
///
/// The fleet is probed automatically every [#AUTO_REFRESH_INTERVAL] while the
/// page is visible, and immediately whenever the page appears or the user
/// presses the refresh button.
@NotNullByDefault
public class QLStatusPage extends DecoratorAnimatedPage implements DecoratorPage, PageAware {

    /// Interval between automatic fleet probes.
    private static final Duration AUTO_REFRESH_INTERVAL = Duration.seconds(30);

    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("qinglan.status.title")));

    private final VBox serverBox = new VBox(8);
    private final JFXButton refreshButton = FXUtils.newRaisedButton(i18n("qinglan.status.refresh"));
    private final Label refreshStatusLabel = new Label();

    private boolean busy;
    /// whether the page is currently shown; the probe callback must not
    /// resurrect the auto-refresh timer once {@link #onPageHidden()} stopped it
    private volatile boolean visible;
    private final Timeline autoRefresh = new Timeline(new KeyFrame(AUTO_REFRESH_INTERVAL, e -> {
        if (!busy)
            refresh();
    }));

    public QLStatusPage() {
        autoRefresh.setCycleCount(Animation.INDEFINITE);
        refreshButton.setOnAction(e -> refresh());

        VBox content = new VBox(12,
                new HBox(16, refreshButton, refreshStatusLabel),
                serverBox);
        content.setPadding(new Insets(16));
        content.setFillWidth(true);

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        FXUtils.smoothScrolling(scrollPane);

        VBox outer = new VBox(scrollPane);
        outer.setFillWidth(true);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        setCenter(outer);
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /// Starts auto-refreshing and probes the fleet once right away.
    @Override
    public void onPageShown() {
        visible = true;
        refresh();
        autoRefresh.playFromStart();
    }

    /// Stops probing while the page is not visible.
    @Override
    public void onPageHidden() {
        visible = false;
        autoRefresh.stop();
    }

    /// Pings every endpoint of {@link QLServerStatus#defaultProbes()} off the FX thread.
    public void refresh() {
        if (busy)
            return;
        busy = true;
        refreshButton.setDisable(true);
        refreshStatusLabel.setText(i18n("qinglan.status.refreshing"));
        Task.supplyAsync(Schedulers.io(), () -> QLServerStatus.probeAll(QLServerStatus.defaultProbes(), 5000))
                .whenComplete(Schedulers.javafx(), (results, exception) -> {
                    busy = false;
                    refreshButton.setDisable(false);
                    boolean show = visible; // a probe racing with page exit must not revive the timer
                    if (exception != null || results == null) {
                        refreshStatusLabel.setText(i18n("qinglan.status.fetch.failed"));
                        if (show)
                            autoRefresh.playFromStart();
                        return;
                    }
                    refreshStatusLabel.setText(i18n("qinglan.status.refresh.done",
                            LocalDateTime.now().format(QLConstants.WALL_CLOCK)));
                    renderResults(QLServerStatus.defaultProbes(), results);
                    // both manual and completed auto probes restart the countdown
                    if (show)
                        autoRefresh.playFromStart();
                }).start();
    }

    private void renderResults(List<QLServerStatus.Probe> probes, List<QLServerStatus.ProbeResult> results) {
        serverBox.getChildren().clear();
        for (int i = 0; i < probes.size(); i++) {
            QLServerStatus.Probe probe = probes.get(i);
            QLServerStatus.ProbeResult result = results.get(i);
            serverBox.getChildren().add(createServerRow(probe, result));
        }
    }

    private VBox createServerRow(QLServerStatus.Probe probe, QLServerStatus.ProbeResult result) {
        String label = probe.label() == null || probe.label().isBlank()
                ? (probe.kind() == QLServerStatus.ProbeKind.SLP
                        ? probe.host() + ":" + probe.port()
                        : probe.url())
                : probe.label();

        VBox row = new VBox(4);
        Label titleLabel = new Label(label);
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        row.getChildren().add(titleLabel);

        if (result.online() && result.players() != null && result.playersMax() != null) {
            Label motdLabel = new Label(result.detail() == null ? "" : result.detail());
            motdLabel.setWrapText(true);
            row.getChildren().add(motdLabel);

            row.getChildren().add(new Label(i18n("qinglan.status.ping", result.latencyMs())));
            row.getChildren().add(new Label(i18n("qinglan.status.players", result.players(), result.playersMax())));
            if (!result.sample().isEmpty())
                row.getChildren().add(new Label(i18n("qinglan.status.sample",
                        String.join(", ", result.sample()))));
        } else if (result.online()) {
            row.getChildren().add(new Label(result.detail() == null ? "" : result.detail()));
        } else {
            row.getChildren().add(new Label(i18n("qinglan.status.offline")));
            if (result.detail() != null && !result.detail().isBlank()) {
                Label errorLabel = new Label(result.detail());
                errorLabel.setWrapText(true);
                errorLabel.setStyle("-fx-opacity: 0.7; -fx-font-size: 12px;");
                row.getChildren().add(errorLabel);
            }
        }

        row.setPadding(new Insets(6, 0, 6, 0));
        return row;
    }
}
