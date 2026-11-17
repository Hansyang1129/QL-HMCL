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
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import kala.compress.archivers.zip.ZipArchiveReader;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.modpack.Modpack;
import org.jackhuang.hmcl.modpack.modrinth.ModrinthModpackProvider;
import org.jackhuang.hmcl.ql.QLPackGuard;
import org.jackhuang.hmcl.ql.QLPackSync;
import org.jackhuang.hmcl.ql.QLRpuUpdater;
import org.jackhuang.hmcl.ql.QLSyncState;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.construct.PageAware;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.util.io.CompressingUtils;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.stream.Stream;
import java.util.Set;
import java.util.Comparator;
import java.nio.file.Files;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Qinglan sync page: update check, channel switch and optional-mod management.
@NotNullByDefault
public class QLSyncPage extends DecoratorAnimatedPage implements DecoratorPage, PageAware {

    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("qinglan.sync.title")));

    private final Label instanceLabel = new Label();
    private final JFXButton checkUpdateButton = FXUtils.newRaisedButton(i18n("qinglan.rpu.update"));
    private final Label statusLabel = new Label();
    private final Label progressLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar();
    /// Explains why the update controls are disabled; hidden for pack instances.
    private final Label gateLabel = new Label();

    private final VBox optionalBox = new VBox(4);
    private final Label seedInfoLabel = new Label();
    private final JFXButton seedChooseButton = FXUtils.newRaisedButton(i18n("qinglan.sync.seed.choose"));
    private final JFXButton seedStartButton = FXUtils.newRaisedButton(i18n("qinglan.sync.seed.apply"));

    private @Nullable Path runDirectory;
    private @Nullable QLSyncState instanceState;
    private @Nullable Path seedZipPath;
    private @Nullable Modpack seedModpack;
    private boolean busy;

    /// Whether the selected instance carries the pack's RPU mod and may be updated at all.
    private boolean syncable;

    public QLSyncPage() {
        HBox actionsRow = new HBox(16, checkUpdateButton);
        actionsRow.setAlignment(Pos.CENTER_LEFT);
        checkUpdateButton.setOnAction(e -> applyUpdate());

        seedStartButton.setDisable(true);
        seedChooseButton.setOnAction(e -> chooseSeedFile());
        seedStartButton.setOnAction(e -> applyPackUpdate());
        HBox seedRow = new HBox(8, seedChooseButton, seedStartButton);
        seedRow.setAlignment(Pos.CENTER_LEFT);
        HBox seedInfoRow = new HBox(0, seedInfoLabel);
        seedInfoRow.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(12,
                createTitleLabel(i18n("qinglan.sync.instanceTitle")), instanceLabel, gateLabel,
                createTitleLabel(i18n("qinglan.rpu.title")), actionsRow, statusLabel,
                createTitleLabel(i18n("qinglan.sync.seed.title")), new VBox(8, seedInfoRow, seedRow),
                createTitleLabel(i18n("qinglan.sync.optional")), optionalBox,
                progressLabel, progressBar);
        content.setPadding(new Insets(16));
        content.setFillWidth(true);

        progressBar.setVisible(false);
        progressBar.setManaged(false);
        progressLabel.setVisible(false);
        progressLabel.setManaged(false);

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        FXUtils.smoothScrolling(scrollPane);

        VBox outer = new VBox(scrollPane);
        outer.setFillWidth(true);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        setCenter(outer);

        refreshInstanceHint();
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /// Re-reads the selected instance whenever the page becomes visible: the
    /// user may have switched instances elsewhere since the last visit, and a
    /// stale hint would apply updates to the wrong instance.
    @Override
    public void onPageShown() {
        refreshInstanceHint();
    }

    private static Label createTitleLabel(String text) {
        return new Label(text);
    }

    private void refreshInstanceHint() {
        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        String version = repository.getSelectedInstance();
        if (version == null || !repository.hasVersion(version)) {
            runDirectory = null;
            syncable = false;
            instanceLabel.setText(i18n("qinglan.sync.instance.none"));
            applyGate();
            return;
        }
        runDirectory = repository.getRunDirectory(version).toAbsolutePath().normalize();

        // Only the pack's own instances may be updated: they are the ones carrying the RPU
        // mod. A personal instance never gets mods from the pack pushed into it.
        String rpuMod = QLPackGuard.findRpuMod(runDirectory);
        syncable = rpuMod != null;
        instanceLabel.setText(syncable
                ? i18n("qinglan.sync.instance.rpu", version, runDirectory.toString(), rpuMod)
                : i18n("qinglan.sync.instance", version, runDirectory.toString()));
        applyGate();
    }

    /// Enables the update controls only for instances the pack owns (RPU mod present);
    /// everything else gets an explanation instead of a half-working page.
    private void applyGate() {
        gateLabel.setText(i18n("qinglan.sync.notSyncable"));
        gateLabel.setVisible(!syncable);
        gateLabel.setManaged(!syncable);
        gateLabel.setWrapText(true);
        gateLabel.setStyle("-fx-text-fill: -monet-error;");

        checkUpdateButton.setDisable(!syncable);
    }

    /// Loads the per-instance state for the currently selected instance asynchronously and
    /// refreshes the instance hint first because the user may have changed it since the
    /// last visit. The loaded state is stored in {@link #instanceState} and handed to
    /// {@code consumer} on the FX thread; a missing instance yields {@code null}.
    private void loadInstanceStateAsync(Consumer<@Nullable QLSyncState> consumer) {
        refreshInstanceHint();
        Path directory = runDirectory;
        if (directory == null) {
            consumer.accept(null);
            return;
        }
        Task.supplyAsync(Schedulers.io(), () -> {
            try {
                return QLSyncState.load(directory);
            } catch (IOException e) {
                LOG.warning("Cannot load Qinglan state of " + directory, e);
                return QLSyncState.empty();
            }
        }).whenComplete(Schedulers.javafx(), (loaded, exception) -> {
            if (exception != null) {
                LOG.warning("Cannot load Qinglan state of " + directory, exception);
                loaded = QLSyncState.empty();
            }
            instanceState = loaded;
            consumer.accept(loaded);
        }).start();
    }

    /// The pack file this page works with: the picked one first, then whatever an earlier
    /// run cached inside the instance. There is no remote address baked into the build.
    private @Nullable Path selectedPack() {
        if (seedZipPath != null)
            return seedZipPath;
        Path directory = runDirectory;
        if (directory == null)
            return null;
        Path cache = directory.resolve(QLPackSync.CACHE_DIR);
        if (!Files.isDirectory(cache))
            return null;
        try (Stream<Path> stream = Files.list(cache)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".mrpack"))
                    .filter(Files::isRegularFile)
                    .max(Comparator.comparingLong(path -> path.toFile().lastModified()))
                    .orElse(null);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot list the pack cache under " + cache, e);
            return null;
        }
    }

    /// @return the selected instance's game version, or {@code null} when it cannot be resolved
    private @Nullable String instanceGameVersion() {
        String version = repositoryInstanceName();
        if (version == null)
            return null;
        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        return repository == null || !repository.isLoaded()
                ? null : repository.getGameVersion(version).orElse(null);
    }

    /// @return the selected instance name, or {@code null} when nothing is selected
    private @Nullable String repositoryInstanceName() {
        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        if (repository == null || !repository.isLoaded())
            return null;
        return repository.getSelectedInstance();
    }

    /// Runs the RPU update pipeline in the launcher: the same phases the in-game client runs
    /// (release, manifest, source, object check, tickets, encrypted objects) and the same
    /// progress semantics, with the release only advertised once everything verified.
    private void applyUpdate() {
        if (busy)
            return;

        refreshInstanceHint();
        Path directory = runDirectory;
        if (directory == null) {
            Controllers.dialog(i18n("qinglan.sync.instance.none"), i18n("message.error"),
                    MessageDialogPane.MessageType.ERROR);
            return;
        }
        if (!syncable) {
            Controllers.dialog(i18n("qinglan.sync.notSyncable"), i18n("message.error"),
                    MessageDialogPane.MessageType.ERROR);
            return;
        }
        String gameVersion = instanceGameVersion();
        if (gameVersion == null) {
            Controllers.dialog(i18n("qinglan.rpu.noVersion"), i18n("message.error"),
                    MessageDialogPane.MessageType.ERROR);
            return;
        }

        busy = true;
        checkUpdateButton.setDisable(true);
        seedChooseButton.setDisable(true);
        seedStartButton.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setManaged(true);
        progressBar.setProgress(-1);
        progressLabel.setVisible(true);
        progressLabel.setManaged(true);
        progressLabel.setText(i18n("qinglan.rpu.downloading"));

        Task.supplyAsync(Schedulers.io(), () -> QLRpuUpdater.update(directory, gameVersion,
                        (finished, total) -> runInFX(() -> {
                            if (total > 0) {
                                progressBar.setProgress((double) finished / total);
                                progressLabel.setText(i18n("qinglan.sync.progress", finished, total));
                            }
                        })))
                .whenComplete(Schedulers.javafx(), (outcome, exception) -> {
                    busy = false;
                    checkUpdateButton.setDisable(!syncable);
                    seedChooseButton.setDisable(false);
                    seedStartButton.setDisable(selectedPack() == null);
                    progressBar.setVisible(false);
                    progressBar.setManaged(false);
                    progressLabel.setVisible(false);
                    progressLabel.setManaged(false);
                    if (exception != null || outcome == null) {
                        Controllers.dialog(i18n("qinglan.rpu.error", exception == null
                                        ? i18n("message.unknown") : describe(exception)),
                                i18n("message.error"), MessageDialogPane.MessageType.ERROR);
                        return;
                    }
                    if (outcome.upToDate()) {
                        statusLabel.setText(i18n("qinglan.rpu.upToDate", outcome.latest()));
                    } else if (outcome.failed() > 0) {
                        statusLabel.setText(i18n("qinglan.rpu.versions",
                                outcome.installed() == null ? "?" : outcome.installed(), outcome.latest()));
                        Controllers.dialog(i18n("qinglan.rpu.partial",
                                        String.valueOf(outcome.downloaded()), String.valueOf(outcome.failed())),
                                i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
                    } else {
                        statusLabel.setText(i18n("qinglan.rpu.updated", outcome.latest(),
                                String.valueOf(outcome.downloaded())));
                        Controllers.showToast(i18n("qinglan.rpu.updated", outcome.latest(),
                                String.valueOf(outcome.downloaded())));
                    }
                })
                .start();
    }

    /// Reads the picked pack and reports whether the instance is behind it.
    private void checkPackUpdate() {
        if (busy)
            return;
        refreshInstanceHint();
        Path directory = runDirectory;
        if (directory == null) {
            Controllers.dialog(i18n("qinglan.sync.instance.none"), i18n("message.error"),
                    MessageDialogPane.MessageType.ERROR);
            return;
        }
        Path pack = selectedPack();
        if (pack == null) {
            chooseSeedFile();
            return;
        }

        busy = true;
        seedChooseButton.setDisable(true);
        seedStartButton.setDisable(true);
        seedInfoLabel.setText(i18n("qinglan.sync.loading"));
        showIndeterminateProgress(true);
        Task.supplyAsync(Schedulers.io(), () -> {
            String version = QLPackSync.readPackVersion(pack);
            Set<String> groups = QLPackSync.optionalGroups(pack);
            return new PackCheck(version, groups, QLSyncState.load(directory));
        }).whenComplete(Schedulers.javafx(), (check, exception) -> {
            busy = false;
            seedChooseButton.setDisable(false);
            seedStartButton.setDisable(selectedPack() == null);
            showIndeterminateProgress(false);
            if (exception != null || check == null) {
                seedInfoLabel.setText("");
                Controllers.dialog(i18n("qinglan.sync.fetch.failed",
                                exception == null ? i18n("message.unknown") : describe(exception)),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR);
                return;
            }
            instanceState = check.state();
            String current = check.state().packVersion == null || check.state().packVersion.isBlank()
                    ? "?" : check.state().packVersion;
            seedInfoLabel.setText(i18n("qinglan.pack.versions", current, check.version()));

            optionalBox.getChildren().clear();
            for (String group : check.groups()) {
                JFXCheckBox checkBox = new JFXCheckBox(group);
                checkBox.setSelected(check.state().isOptionalEnabled(group));
                checkBox.selectedProperty().addListener((observable, oldValue, newValue) ->
                        toggleOptionalGroup(group, newValue));
                optionalBox.getChildren().add(checkBox);
            }
            optionalBox.setManaged(!optionalBox.getChildren().isEmpty());
            optionalBox.setVisible(!optionalBox.getChildren().isEmpty());
            if (current.equals(check.version()))
                Controllers.showToast(i18n("qinglan.sync.upToDate", check.version()));
        }).start();
    }

    /// Persists an optional-group toggle so the next apply parks or restores the jars.
    private void toggleOptionalGroup(String group, boolean enabled) {
        QLSyncState state = instanceState;
        Path directory = runDirectory;
        if (state == null || directory == null)
            return;
        state.setOptionalEnabled(group, enabled);
        Task.runAsync(Schedulers.io(), () -> {
            try {
                state.save(directory);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Cannot save the Qinglan state of " + directory, e);
            }
        }).start();
    }

    /// Applies the picked pack onto the current instance (mods, configs, shaders, packs).
    private void applyPackUpdate() {
        if (busy)
            return;
        refreshInstanceHint();
        Path directory = runDirectory;
        if (directory == null) {
            Controllers.dialog(i18n("qinglan.sync.instance.none"), i18n("message.error"),
                    MessageDialogPane.MessageType.ERROR);
            return;
        }
        if (!syncable) {
            Controllers.dialog(i18n("qinglan.sync.notSyncable"), i18n("message.error"),
                    MessageDialogPane.MessageType.ERROR);
            return;
        }
        Path pack = selectedPack();
        if (pack == null) {
            chooseSeedFile();
            return;
        }

        busy = true;
        seedChooseButton.setDisable(true);
        seedStartButton.setDisable(true);
        checkUpdateButton.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setManaged(true);
        progressBar.setProgress(-1);
        progressLabel.setVisible(true);
        progressLabel.setManaged(true);

        Task.supplyAsync(Schedulers.io(), () -> QLPackSync.sync(directory, pack, null,
                        (finished, total) -> runInFX(() -> {
                            if (total > 0) {
                                progressBar.setProgress((double) finished / total);
                                progressLabel.setText(i18n("qinglan.sync.progress", finished, total));
                            }
                        })))
                .whenComplete(Schedulers.javafx(), (outcome, exception) -> {
                    busy = false;
                    seedChooseButton.setDisable(false);
                    seedStartButton.setDisable(selectedPack() == null);
                    checkUpdateButton.setDisable(!syncable);
                    progressBar.setVisible(false);
                    progressBar.setManaged(false);
                    progressLabel.setVisible(false);
                    progressLabel.setManaged(false);
                    if (exception != null || outcome == null) {
                        Controllers.dialog(i18n("qinglan.sync.apply.failed",
                                        exception == null ? i18n("message.unknown") : describe(exception)),
                                i18n("message.error"), MessageDialogPane.MessageType.ERROR);
                        return;
                    }
                    instanceState = null;
                    displayOutcome(outcome);
                }).start();
    }

    /// Result of reading the picked pack: version, optional groups, instance state.
    private record PackCheck(String version, Set<String> groups, QLSyncState state) {
    }

    /// Parses the seed pack the user picked: reads the embedded Modrinth manifest natively
    /// so the UI can show what is about to be applied. The pack is the update source and is
    /// applied by {@link #applyUpdate()}.
    private void chooseSeedFile() {
        if (busy)
            return;

        FileChooser chooser = new FileChooser();
        chooser.setTitle(i18n("qinglan.sync.seed.choose"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(i18n("qinglan.sync.seed.filter"), "*.mrpack"));
        Path selectedFile = FileUtils.toPath(chooser.showOpenDialog(Controllers.getStage()));
        if (selectedFile == null)
            return;

        busy = true;
        seedChooseButton.setDisable(true);
        seedInfoLabel.setText(i18n("qinglan.sync.seed.parsing"));
        Task.supplyAsync(Schedulers.io(), () -> {
            Charset encoding = CompressingUtils.findSuitableEncoding(selectedFile);
            try (ZipArchiveReader zip = CompressingUtils.openZipFile(selectedFile, encoding)) {
                return ModrinthModpackProvider.INSTANCE.readManifest(zip, selectedFile, encoding);
            }
        }).whenComplete(Schedulers.javafx(), (modpack, exception) -> {
            busy = false;
            seedChooseButton.setDisable(false);
            if (exception != null || modpack == null) {
                seedInfoLabel.setText("");
                Controllers.dialog(i18n("qinglan.sync.seed.parse.failed",
                                exception == null ? i18n("message.unknown") : describe(exception)),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR);
                return;
            }
            seedZipPath = selectedFile;
            seedModpack = modpack;
            String name = modpack.getName() == null || modpack.getName().isBlank()
                    ? modpack.getVersion() : modpack.getName();
            seedInfoLabel.setText(i18n("qinglan.sync.seed.selected",
                    name == null ? "?" : name, modpack.getGameVersion() == null ? "?" : modpack.getGameVersion()));
            seedStartButton.setDisable(false);
            checkPackUpdate();
        }).start();
    }

    /// Toggles the indeterminate progress row used while a check runs.
    private void showIndeterminateProgress(boolean visible) {
        progressBar.setVisible(visible);
        progressBar.setManaged(visible);
        progressLabel.setVisible(visible);
        progressLabel.setManaged(visible);
        if (visible) {
            progressBar.setProgress(-1);
            progressLabel.setText(i18n("qinglan.sync.checking"));
        }
    }

    /// @return the human message of an exception, falling back to its string form
    private String describe(Exception exception) {
        String message = exception.getLocalizedMessage();
        return message == null || message.isBlank() ? exception.toString() : message;
    }

    private void displayOutcome(QLPackSync.Outcome outcome) {
        String summary = i18n("qinglan.sync.apply.done",
                String.valueOf(outcome.downloaded()), String.valueOf(outcome.unchanged()),
                String.valueOf(outcome.renamed()), String.valueOf(outcome.deleted()));
        if (outcome.handedOver() > 0)
            summary = summary + "\n" + i18n("qinglan.sync.apply.handedOver",
                    String.valueOf(outcome.handedOver()));
        if (outcome.failedPaths().isEmpty()) {
            statusLabel.setText(summary);
            Controllers.showToast(summary);
        } else {
            StringBuilder failedList = new StringBuilder();
            for (String failed : outcome.failedPaths()) {
                if (!failedList.isEmpty())
                    failedList.append('\n');
                failedList.append(failed);
            }
            Controllers.dialog(summary + '\n' + i18n("qinglan.sync.apply.errors", failedList.toString()),
                    i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
        }
    }
}
