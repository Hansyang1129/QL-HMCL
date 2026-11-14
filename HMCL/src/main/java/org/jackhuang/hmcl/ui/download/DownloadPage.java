/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.ui.download;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.Node;
import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.download.*;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.ql.QLInstances;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.WeakListenerHolder;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.construct.TabHeader;
import org.jackhuang.hmcl.ui.construct.Validator;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.versions.DownloadListPage;
import org.jackhuang.hmcl.ui.versions.HMCLLocalizedDownloadListPage;
import org.jackhuang.hmcl.ui.versions.VersionPage;
import org.jackhuang.hmcl.util.TaskCancellationAction;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

public class DownloadPage extends DecoratorAnimatedPage implements DecoratorPage {
    public static final org.jackhuang.hmcl.ui.versions.DownloadPage.DownloadCallback FOR_MOD =
            (downloadProvider, repository, version, mod, file) -> download(downloadProvider, repository, version, file, "mods");
    public static final org.jackhuang.hmcl.ui.versions.DownloadPage.DownloadCallback FOR_RESOURCE_PACK =
            (downloadProvider, repository, version, pack, file) -> download(downloadProvider, repository, version, file, "resourcepacks");
    public static final org.jackhuang.hmcl.ui.versions.DownloadPage.DownloadCallback FOR_SHADER =
            (downloadProvider, repository, version, shader, file) -> download(downloadProvider, repository, version, file, "shaderpacks");

    private final ReadOnlyObjectWrapper<DecoratorPage.State> state = new ReadOnlyObjectWrapper<>(DecoratorPage.State.fromTitle(i18n("download"), -1));
    private final TabHeader tab;
    private final TabHeader.Tab<DownloadListPage> modTab = new TabHeader.Tab<>("modTab");
    private final TabHeader.Tab<DownloadListPage> shaderTab = new TabHeader.Tab<>("shaderTab");
    private final TransitionPane transitionPane = new TransitionPane();

    private WeakListenerHolder listenerHolder;

    public DownloadPage() {
        modTab.setNodeSupplier(loadVersionFor(() -> HMCLLocalizedDownloadListPage.ofMod(FOR_MOD, true)));
        shaderTab.setNodeSupplier(loadVersionFor(() -> HMCLLocalizedDownloadListPage.ofShaderPack(FOR_SHADER, true)));
        tab = new TabHeader(transitionPane, modTab, shaderTab);

        GameDirectoryManager.registerVersionsListener(this::loadVersions);

        tab.select(modTab);

        AdvancedListBox sideBar = new AdvancedListBox()
                .startCategory(i18n("download.content").toUpperCase(Locale.ROOT))
                .addNavigationDrawerTab(tab, modTab, i18n("mods"), SVG.EXTENSION, SVG.EXTENSION_FILL)
                .addNavigationDrawerTab(tab, shaderTab, i18n("download.shader"), SVG.WB_SUNNY, SVG.WB_SUNNY_FILL);
        FXUtils.setLimitWidth(sideBar, 200);
        setLeft(sideBar);

        setCenter(transitionPane);
    }

    private static <T extends Node> Supplier<T> loadVersionFor(Supplier<T> nodeSupplier) {
        return () -> {
            T node = nodeSupplier.get();
            HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
            // Pin the current Qinglan instance so initial searches filter by its game version.
            String instanceId = QLInstances.findQinglanInstance(repository);
            if (instanceId == null)
                instanceId = repository.getSelectedInstance();
            if (node instanceof VersionPage.GameInstanceLoadable loadable) {
                loadable.loadInstance(repository, instanceId);
            }
            return node;
        };
    }

    public static void download(DownloadProvider downloadProvider, HMCLGameRepository repository, @Nullable String version, RemoteAddon.Version file, String subdirectoryName) {
        if (version == null) version = repository.getSelectedInstance();

        Path runDirectory = repository.hasVersion(version) ? repository.getRunDirectory(version) : repository.getBaseDirectory();

        Set<String> existingFiles;

        try (var list = Files.list(runDirectory.resolve(subdirectoryName))) {
            existingFiles = list.map(Path::getFileName)
                    .map(Path::toString)
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            LOG.warning("Failed to list files in " + runDirectory.resolve(subdirectoryName), e);
            existingFiles = Set.of();
        }

        Set<String> finalExistingFiles = existingFiles;

        Controllers.prompt(i18n("archive.file.name"), (result, handler) -> {
            Path dest = runDirectory.resolve(subdirectoryName).resolve(result);

            Controllers.taskDialog(Task.composeAsync(() -> {
                var task = new FileDownloadTask(downloadProvider.injectURLWithCandidates(file.file().url()), dest);
                task.setName(file.name());
                return task;
            }).whenComplete(Schedulers.javafx(), exception -> {
                if (exception != null) {
                    if (exception instanceof CancellationException) {
                        Controllers.showToast(i18n("message.cancelled"));
                    } else {
                        Controllers.dialog(DownloadProviders.localizeErrorMessage(exception), i18n("install.failed.downloading"), MessageDialogPane.MessageType.ERROR);
                    }
                } else {
                    Controllers.showToast(i18n("install.success"));
                }
            }), i18n("message.downloading"), TaskCancellationAction.NORMAL);
            handler.resolve();
        }, file.file().filename(), new Validator(i18n("install.new_game.malformed"), FileUtils::isNameValid), new Validator(i18n("game_directory.already_exists"), (it) -> !finalExistingFiles.contains(it)));

    }

    private void loadVersions(HMCLGameRepository repository) {
        listenerHolder = new WeakListenerHolder();
        runInFX(() -> {
            if (repository.getGameDirectory() == GameDirectoryManager.getSelectedGameDirectory()) {
                listenerHolder.add(FXUtils.onWeakChangeAndOperate(GameDirectoryManager.selectedInstanceProperty(), version -> {
                    // Pin the download lists to the Qinglan instance so every search only
                    // returns content matching the instance's game version.
                    String instanceId = QLInstances.findQinglanInstance(repository);
                    if (instanceId == null)
                        instanceId = repository.getSelectedInstance();

                    if (modTab.isInitialized()) {
                        modTab.getNode().loadInstance(repository, instanceId);
                    }
                    if (shaderTab.isInitialized()) {
                        shaderTab.getNode().loadInstance(repository, instanceId);
                    }
                }));
            }
        });
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    public DownloadListPage showModDownloads() {
        tab.select(modTab, false);
        return modTab.getNode();
    }
}
