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
import com.jfoenix.controls.JFXRadioButton;
import com.jfoenix.controls.JFXTextField;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import kala.compress.archivers.zip.ZipArchiveReader;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.ModpackHelper;
import org.jackhuang.hmcl.modpack.Modpack;
import org.jackhuang.hmcl.modpack.modrinth.ModrinthModpackProvider;
import org.jackhuang.hmcl.ql.QLConstants;
import org.jackhuang.hmcl.ql.QLDirectoryLayout;
import org.jackhuang.hmcl.ql.QLManifest;
import org.jackhuang.hmcl.ql.QLManifestFetcher;
import org.jackhuang.hmcl.ql.QLSeedImporter;
import org.jackhuang.hmcl.ql.QLSyncManager;
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
import org.jackhuang.hmcl.util.TaskCancellationAction;
import org.jackhuang.hmcl.util.io.CompressingUtils;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final JFXTextField manifestUrlField = new JFXTextField();
    private final JFXButton checkUpdateButton = FXUtils.newRaisedButton(i18n("qinglan.sync.checkUpdate"));
    private final JFXButton applyButton = FXUtils.newRaisedButton(i18n("qinglan.sync.apply"));
    private final Label statusLabel = new Label();
    private final Label progressLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar();
    private final ToggleGroup channelGroup = new ToggleGroup();
    private final JFXRadioButton stdRadio = new JFXRadioButton(i18n("qinglan.sync.channel.std"));
    private final JFXRadioButton pfmRadio = new JFXRadioButton(i18n("qinglan.sync.channel.pfm"));
    private final VBox optionalBox = new VBox(4);
    private final Label seedInfoLabel = new Label();
    private final JFXTextField seedNameField = new JFXTextField();
    private final JFXButton seedChooseButton = FXUtils.newRaisedButton(i18n("qinglan.sync.seed.choose"));
    private final JFXButton seedStartButton = FXUtils.newRaisedButton(i18n("qinglan.sync.seed.start"));

    private @Nullable Path runDirectory;
    private @Nullable QLSyncState instanceState;
    private @Nullable QLManifest fetchedManifest;
    private @Nullable Path seedZipPath;
    private @Nullable Modpack seedModpack;
    private boolean busy;

    public QLSyncPage() {
        stdRadio.setToggleGroup(channelGroup);
        stdRadio.setUserData("std");
        pfmRadio.setToggleGroup(channelGroup);
        pfmRadio.setUserData("pfm");

        JFXButton saveUrlButton = new JFXButton(i18n("qinglan.sync.save"));
        saveUrlButton.setOnAction(e -> saveManifestUrl());

        HBox urlRow = new HBox(8, manifestUrlField, saveUrlButton);
        HBox.setHgrow(manifestUrlField, Priority.ALWAYS);
        urlRow.setAlignment(Pos.CENTER_LEFT);

        applyButton.setDisable(true);
        HBox actionsRow = new HBox(16, checkUpdateButton, applyButton);
        actionsRow.setAlignment(Pos.CENTER_LEFT);
        checkUpdateButton.setOnAction(e -> checkUpdate());
        applyButton.setOnAction(e -> applyUpdate());

        // Editing the manifest URL invalidates the previously fetched manifest:
        // applying must never sync the old manifest against the new URL. The field
        // listeners also fire when a state load repopulates it, which is harmless
        // because nothing has been fetched by then anyway.
        manifestUrlField.textProperty().addListener((observable, oldValue, newValue) -> {
            fetchedManifest = null;
            applyButton.setDisable(true);
        });

        HBox channelRow = new HBox(16, stdRadio, pfmRadio);
        channelRow.setAlignment(Pos.CENTER_LEFT);

        seedStartButton.setDisable(true);
        seedNameField.setPromptText(i18n("qinglan.sync.seed.instance"));
        HBox.setHgrow(seedNameField, Priority.ALWAYS);
        seedChooseButton.setOnAction(e -> chooseSeedFile());
        seedStartButton.setOnAction(e -> startSeedImport());
        HBox seedRow = new HBox(8, seedNameField, seedChooseButton, seedStartButton);
        seedRow.setAlignment(Pos.CENTER_LEFT);
        HBox seedInfoRow = new HBox(0, seedInfoLabel);
        seedInfoRow.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(12,
                createTitleLabel(i18n("qinglan.sync.instanceTitle")), instanceLabel,
                createTitleLabel(i18n("qinglan.sync.manifestUrl")), urlRow,
                actionsRow, statusLabel, progressLabel, progressBar,
                createTitleLabel(i18n("qinglan.sync.channel")), channelRow,
                createTitleLabel(i18n("qinglan.sync.optional")), optionalBox,
                createTitleLabel(i18n("qinglan.sync.seed.title")), new VBox(8, seedInfoRow, seedRow));
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
            instanceLabel.setText(i18n("qinglan.sync.instance.none"));
            return;
        }
        runDirectory = repository.getRunDirectory(version).toAbsolutePath().normalize();
        instanceLabel.setText(i18n("qinglan.sync.instance", version, runDirectory.toString()));
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
            if (loaded.manifestUrl != null && !loaded.manifestUrl.isBlank())
                manifestUrlField.setText(loaded.manifestUrl);
            else if (manifestUrlField.getText() == null || manifestUrlField.getText().isBlank())
                manifestUrlField.setText(QLConstants.DEFAULT_MANIFEST_URL);
            consumer.accept(loaded);
        }).start();
    }

    private void saveManifestUrl() {
        refreshInstanceHint();
        Path directory = runDirectory;
        if (directory == null) {
            Controllers.dialog(i18n("qinglan.sync.instance.none"), i18n("message.error"), MessageDialogPane.MessageType.ERROR);
            return;
        }
        String url = manifestUrlField.getText();
        if (url == null || url.isBlank()) {
            Controllers.dialog(i18n("qinglan.sync.urlInvalid"), i18n("message.error"), MessageDialogPane.MessageType.ERROR);
            return;
        }
        String normalized = url.trim();
        // File IO stays off the FX thread; a load failure falls back to a fresh
        // state so the URL is still persisted.
        Task.supplyAsync(Schedulers.io(), () -> {
            QLSyncState persisted;
            try {
                persisted = QLSyncState.load(directory);
            } catch (IOException e) {
                LOG.warning("Cannot load Qinglan state of " + directory, e);
                persisted = QLSyncState.empty();
            }
            persisted.manifestUrl = normalized;
            persisted.save(directory);
            return true;
        }).whenComplete(Schedulers.javafx(), (saved, exception) -> {
            if (exception != null) {
                Controllers.dialog(i18n("qinglan.sync.save.error", describe(exception)),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR);
                return;
            }
            instanceState = null; // force a state reload before the next action
            Controllers.showToast(i18n("qinglan.sync.save.done"));
        }).start();
    }

    private void checkUpdate() {
        if (busy)
            return;

        busy = true;
        checkUpdateButton.setDisable(true);
        statusLabel.setText(i18n("qinglan.sync.loading"));

        loadInstanceStateAsync(loaded -> {
            if (loaded == null) {
                busy = false;
                checkUpdateButton.setDisable(false);
                statusLabel.setText("");
                Controllers.dialog(i18n("qinglan.sync.instance.none"), i18n("message.error"), MessageDialogPane.MessageType.ERROR);
                return;
            }

            QLManifestFetcher.createFetchTask(manifestUrlField.getText())
                    .whenComplete(Schedulers.javafx(), (result, exception) -> {
                        busy = false;
                        checkUpdateButton.setDisable(false);
                        if (exception != null) {
                            statusLabel.setText("");
                            Controllers.dialog(i18n("qinglan.sync.fetch.failed", describe(exception)),
                                    i18n("message.error"), MessageDialogPane.MessageType.ERROR);
                        } else if (result != null) {
                            onManifestFetched(result, loaded);
                        }
                    })
                    .start();
        });
    }

    private void onManifestFetched(QLManifest manifest, QLSyncState stateSnapshot) {
        fetchedManifest = manifest;

        String remoteVersion = manifest.packVersionOrDefault();
        String currentVersion = stateSnapshot.packVersion == null || stateSnapshot.packVersion.isBlank()
                ? "?" : stateSnapshot.packVersion;
        statusLabel.setText(i18n("qinglan.sync.versions", currentVersion, remoteVersion));

        String activeChannel = runDirectory == null ? "std"
                : QLDirectoryLayout.detectActiveChannel(runDirectory, stateSnapshot.activeChannel);
        if ("pfm".equals(activeChannel))
            pfmRadio.setSelected(true);
        else
            stdRadio.setSelected(true);

        optionalBox.getChildren().clear();
        for (String group : manifest.optionalGroups()) {
            JFXCheckBox checkBox = new JFXCheckBox(group);
            checkBox.setSelected(stateSnapshot.isOptionalEnabled(group));
            optionalBox.getChildren().add(checkBox);
        }
        optionalBox.setManaged(!optionalBox.getChildren().isEmpty());
        optionalBox.setVisible(!optionalBox.getChildren().isEmpty());

        applyButton.setDisable(false);

        if (currentVersion.equals(remoteVersion))
            Controllers.showToast(i18n("qinglan.sync.upToDate", remoteVersion));
    }

    private void applyUpdate() {
        if (busy || fetchedManifest == null || runDirectory == null)
            return;

        Toggle toggle = channelGroup.getSelectedToggle();
        String targetChannel = toggle != null && "pfm".equals(toggle.getUserData()) ? "pfm" : "std";

        Map<String, Boolean> optionalExtras = new LinkedHashMap<>();
        for (Node node : optionalBox.getChildren()) {
            if (node instanceof JFXCheckBox checkBox) {
                String text = checkBox.getText();
                if (text != null)
                    optionalExtras.put(text, checkBox.isSelected());
            }
        }

        Path gameDirectory = runDirectory;
        QLManifest manifest = fetchedManifest;

        busy = true;
        checkUpdateButton.setDisable(true);
        applyButton.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setManaged(true);
        progressBar.setProgress(-1);
        progressLabel.setVisible(true);
        progressLabel.setManaged(true);
        progressLabel.setText(i18n("qinglan.sync.applying"));

        Task.supplyAsync(Schedulers.io(), () -> QLSyncManager.sync(gameDirectory, manifest, targetChannel,
                        optionalExtras, (finished, total) -> runInFX(() -> {
                            if (total > 0) {
                                progressBar.setProgress((double) finished / total);
                                progressLabel.setText(i18n("qinglan.sync.progress", finished, total));
                            }
                        })))
                .whenComplete(Schedulers.javafx(), (outcome, exception) -> {
                    busy = false;
                    checkUpdateButton.setDisable(false);
                    progressBar.setVisible(false);
                    progressBar.setManaged(false);
                    progressLabel.setVisible(false);
                    progressLabel.setManaged(false);
                    if (exception != null) {
                        Controllers.dialog(i18n("qinglan.sync.apply.failed", describe(exception)),
                                i18n("message.error"), MessageDialogPane.MessageType.ERROR);
                        applyButton.setDisable(false);
                        return;
                    }
                    if (outcome == null)
                        return;
                    displayOutcome(outcome);

                    // Reload the persisted state off the FX thread to refresh the version hint.
                    Task.supplyAsync(Schedulers.io(), () -> QLSyncState.load(gameDirectory))
                            .whenComplete(Schedulers.javafx(), (reloaded, reloadException) -> {
                                if (reloadException != null) {
                                    LOG.warning("Cannot reload Qinglan state of " + gameDirectory, reloadException);
                                } else if (reloaded != null) {
                                    instanceState = reloaded;
                                    String nextVersion = reloaded.packVersion == null || reloaded.packVersion.isBlank()
                                            ? "?" : reloaded.packVersion;
                                    statusLabel.setText(i18n("qinglan.sync.versions", nextVersion, manifest.packVersionOrDefault()));
                                }
                                applyButton.setDisable(false);
                            }).start();
                })
                .start();
    }

    private String describe(Exception exception) {
        String message = exception.getLocalizedMessage();
        return message == null || message.isBlank() ? exception.toString() : message;
    }

    /// Parses the seed pack the user picked: reads the embedded Modrinth manifest natively
    /// and suggests the instance name from it. The actual import happens in {@link #startSeedImport()}.
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
            seedNameField.setText(name == null ? "" : name);
            seedInfoLabel.setText(i18n("qinglan.sync.seed.selected",
                    name == null ? "?" : name, modpack.getGameVersion() == null ? "?" : modpack.getGameVersion()));
            seedStartButton.setDisable(false);
        }).start();
    }

    /// Runs the seed import: the native HMCL modpack installer unpacks and completes the pack,
    /// then the remote manifest is fetched and {@link QLSeedImporter} QLizes the resulting
    /// instance with a per-entry SHA-1 reconciliation.
    private void startSeedImport() {
        if (busy || seedZipPath == null || seedModpack == null)
            return;

        String name = seedNameField.getText();
        if (name == null || name.isBlank()) {
            Controllers.dialog(i18n("qinglan.sync.seed.nameMissing"), i18n("message.error"), MessageDialogPane.MessageType.ERROR);
            return;
        }
        String base = name.trim();
        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        if (repository == null || !repository.isLoaded()) {
            Controllers.dialog(i18n("qinglan.sync.instance.none"), i18n("message.error"), MessageDialogPane.MessageType.ERROR);
            return;
        }
        // Uniquify the instance name in the background of the user's input.
        name = base;
        for (int i = 1; repository.hasVersion(name); i++)
            name = base + " (" + i + ")";
        final String instanceName = name;
        seedNameField.setText(instanceName);

        Path zipPath = seedZipPath;
        Modpack modpack = seedModpack;
        String manifestUrl = manifestUrlField.getText();

        busy = true;
        seedChooseButton.setDisable(true);
        seedStartButton.setDisable(true);
        checkUpdateButton.setDisable(true);
        applyButton.setDisable(true);
        seedInfoLabel.setText(i18n("qinglan.sync.seed.importing"));
        progressBar.setVisible(true);
        progressBar.setManaged(true);
        progressBar.setProgress(-1);
        progressLabel.setVisible(true);
        progressLabel.setManaged(true);

        Task<Object> chain = ModpackHelper.getInstallTask(repository, zipPath, instanceName, modpack, null)
                .thenComposeAsync(QLManifestFetcher.createFetchTask(manifestUrl))
                .thenComposeAsync(Schedulers.defaultScheduler(), (QLManifest manifest) -> Task.supplyAsync(Schedulers.io(), () -> {
                    runInFX(() -> seedInfoLabel.setText(i18n("qinglan.sync.seed.seeding")));
                    QLSeedImporter.SeedOutcome outcome = QLSeedImporter.seedize(
                            repository.getRunDirectory(instanceName), manifest, manifestUrl, "std", null,
                            (finished, total) -> runInFX(() -> {
                                if (total > 0) {
                                    progressBar.setProgress((double) finished / total);
                                    progressLabel.setText(i18n("qinglan.sync.progress", finished, total));
                                }
                                seedInfoLabel.setText(i18n("qinglan.sync.seed.seeding"));
                            }));
                    repository.setSelectedInstance(instanceName);
                    return outcome;
                }));

        Controllers.taskDialog(chain, i18n("qinglan.sync.seed.title"), TaskCancellationAction.NORMAL);
        chain.whenComplete(Schedulers.javafx(), (outcome, exception) -> {
            busy = false;
            seedChooseButton.setDisable(false);
            seedStartButton.setDisable(false);
            checkUpdateButton.setDisable(false);
            applyButton.setDisable(false);
            progressBar.setVisible(false);
            progressBar.setManaged(false);
            progressLabel.setVisible(false);
            progressLabel.setManaged(false);
            if (exception != null) {
                seedInfoLabel.setText("");
                Controllers.dialog(i18n("qinglan.sync.seed.failed", describe(exception)),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR);
                return;
            }
            QLSeedImporter.SeedOutcome seedOutcome = (QLSeedImporter.SeedOutcome) outcome;
            if (seedOutcome == null)
                return;
            refreshInstanceHint();
            instanceState = null; // force a state reload for the freshly selected instance
            seedInfoLabel.setText(i18n("qinglan.sync.seed.done",
                    String.valueOf(seedOutcome.seeded), String.valueOf(seedOutcome.missing),
                    String.valueOf(seedOutcome.mismatch)));
            Controllers.showToast(i18n("qinglan.sync.seed.doneToast", instanceName));
        });
    }

    private void displayOutcome(QLSyncManager.SyncOutcome outcome) {
        if (outcome.cancelled) {
            Controllers.showToast(i18n("qinglan.sync.apply.cancelled"));
            return;
        }
        String summary = i18n("qinglan.sync.apply.done",
                String.valueOf(outcome.downloaded), String.valueOf(outcome.unchanged),
                String.valueOf(outcome.renamed), String.valueOf(outcome.deleted));
        if (outcome.failedFiles.isEmpty()) {
            Controllers.showToast(summary);
        } else {
            StringBuilder failedList = new StringBuilder();
            for (String failed : outcome.failedFiles) {
                if (!failedList.isEmpty())
                    failedList.append('\n');
                failedList.append(failed);
            }
            Controllers.dialog(summary + '\n' + i18n("qinglan.sync.apply.errors", failedList.toString()),
                    i18n("message.success"), MessageDialogPane.MessageType.WARNING);
        }
    }
}
