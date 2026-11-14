/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.ui.versions;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXListView;
import com.jfoenix.controls.JFXTextField;
import javafx.animation.PauseTransition;
import javafx.beans.binding.Bindings;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Skin;
import javafx.scene.control.SkinBase;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.ModpackHelper;
import org.jackhuang.hmcl.ql.QLInstances;
import org.jackhuang.hmcl.setting.GameDirectory;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.*;
import org.jackhuang.hmcl.ui.animation.ContainerAnimations;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.AdvancedListItem;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.download.ModpackInstallWizardProvider;
import org.jackhuang.hmcl.ui.directory.GameDirectoryListItem;
import org.jackhuang.hmcl.ui.directory.GameDirectoryPage;
import org.jackhuang.hmcl.util.FXThread;
import org.jackhuang.hmcl.util.PortablePath;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.javafx.MappedObservableList;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.jackhuang.hmcl.ui.FXUtils.*;
import static org.jackhuang.hmcl.ui.ToolbarListPageSkin.createToolbarButton2;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

public class GameListPage extends DecoratorAnimatedPage implements DecoratorPage {
    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("version.manage")));
    /// Navigation drawer items for configured game directories.
    @SuppressWarnings("FieldCanBeLocal")
    private final ObservableList<GameDirectoryListItem> gameDirectoryListItems;

    public GameListPage() {
        gameDirectoryListItems = MappedObservableList.create(GameDirectoryManager.getGameDirectories(), gameDirectory -> {
            GameDirectoryListItem item = new GameDirectoryListItem(gameDirectory);
            FXUtils.setLimitWidth(item, 200);
            return item;
        });

        {
            ScrollPane pane = new ScrollPane();
            VBox.setVgrow(pane, Priority.ALWAYS);
            {
                AdvancedListItem addGameDirectoryItem = new AdvancedListItem();
                addGameDirectoryItem.getStyleClass().add("navigation-drawer-item");
                addGameDirectoryItem.setTitle(i18n("game_directory.new"));
                addGameDirectoryItem.setLeftIcon(SVG.ADD_CIRCLE);
                addGameDirectoryItem.setOnAction(e -> Controllers.navigate(new GameDirectoryPage(null)));

                pane.setFitToWidth(true);
                VBox wrapper = new VBox();
                wrapper.getStyleClass().add("advanced-list-box-content");
                VBox box = new VBox();
                box.setFillWidth(true);
                Bindings.bindContent(box.getChildren(), gameDirectoryListItems);
                wrapper.getChildren().setAll(box, addGameDirectoryItem);
                pane.setContent(wrapper);
            }

            AdvancedListBox bottomLeftCornerList = new AdvancedListBox()
                    .addNavigationDrawerItem(i18n("settings.type.global.manage"), SVG.SETTINGS, this::modifyGlobalGameSettings);
            FXUtils.setLimitHeight(bottomLeftCornerList, 40 + 12);
            setLeft(pane, bottomLeftCornerList);
        }

        setCenter(new GameList());

        FXUtils.applyDragListener(this, file -> ModpackHelper.isFileModpackByExtension(file) || "json".equalsIgnoreCase(FileUtils.getNameWithoutExtension(file)), files -> {
            Path file = files.get(0);

            if (ModpackHelper.isFileModpackByExtension(file)) {
                Controllers.getDecorator().startWizard(new ModpackInstallWizardProvider(GameDirectoryManager.getSelectedRepository(), file), i18n("install.modpack"));
            } else if ("json".equalsIgnoreCase(FileUtils.getExtension(file))) {
                Versions.installFromJson(GameDirectoryManager.getSelectedRepository(), file);
            }
        });
    }

    public void modifyGlobalGameSettings() {
        Versions.modifyGlobalSettings(GameDirectoryManager.getSelectedRepository());
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    private static class GameList extends ListPageBase<GameListItem> {
        private final WeakListenerHolder listenerHolder = new WeakListenerHolder();

        private final ObservableList<GameListItem> sourceList = FXCollections.observableArrayList();
        private final FilteredList<GameListItem> filteredList = new FilteredList<>(sourceList);

        public GameList() {
            setItems(filteredList);

            GameDirectoryManager.registerVersionsListener(this::loadVersions);

            setOnFailedAction(e -> Controllers.navigate(Controllers.getDownloadPage()));
        }

        @FXThread
        private void loadVersions(HMCLGameRepository repository) {
            listenerHolder.clear();
            setLoading(true);
            setFailedReason(null);

            List<GameListItem> versionItems = repository.getDisplayVersions().map(instance -> new GameListItem(repository, instance.getId())).toList();

            // Only the Qinglan instance is managed; other instances in the same directory are ignored.
            String qinglanId = QLInstances.findQinglanInstance(repository);
            if (qinglanId != null) {
                versionItems = List.of(new GameListItem(repository, qinglanId));
                if (!qinglanId.equals(repository.getSelectedInstance())) {
                    repository.setSelectedInstance(qinglanId);
                }
            }

            sourceList.setAll(versionItems);

            setLoading(false);
        }

        /// Lets the user point the launcher at a directory containing the Qinglan instance.
        ///
        /// The directory is registered as a user game directory and made current; once its
        /// repository has loaded, {@link #loadVersions(HMCLGameRepository)} finds and selects
        /// the Qinglan instance automatically.
        public void chooseQinglanDirectory() {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(i18n("qinglan.guide.chooseDir"));
            File file = chooser.showDialog(Controllers.getStage());
            if (file == null) {
                return;
            }

            GameDirectory newGameDirectory = new GameDirectory(
                    GameDirectoryManager.newGameDirectoryId(),
                    null,
                    PortablePath.fromPath(file.toPath()));
            GameDirectoryManager.addUserGameDirectory(newGameDirectory);
            GameDirectoryManager.setSelectedGameDirectory(newGameDirectory);
        }

        private Predicate<GameListItem> createPredicate(String searchText) {
            if (searchText == null || searchText.isEmpty()) {
                return item -> true;
            }

            if (searchText.startsWith("regex:")) {
                String regex = searchText.substring("regex:".length());
                try {
                    Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
                    return item -> pattern.matcher(item.id).find();
                } catch (PatternSyntaxException e) {
                    return item -> false;
                }
            } else {
                return item -> item.id.toLowerCase(Locale.ROOT).contains(searchText.toLowerCase(Locale.ROOT));
            }
        }

        public void refreshList() {
            GameDirectoryManager.getSelectedRepository().refreshVersionsAsync().start();
        }

        @Override
        protected Skin<?> createDefaultSkin() {
            return new GameListSkin(this);
        }

        private static class GameListSkin extends SkinBase<GameList> {
            private final TransitionPane toolbarPane;
            private final HBox searchBar;
            private final HBox toolbarNormal;

            private final JFXTextField searchField;

            public GameListSkin(GameList skinnable) {
                super(skinnable);

                StackPane pane = new StackPane();
                pane.setPadding(new Insets(10));
                pane.getStyleClass().addAll("notice-pane");

                ComponentList root = new ComponentList();
                root.getStyleClass().add("no-padding");
                JFXListView<GameListItem> listView = new JFXListView<>();

                {
                    toolbarPane = new TransitionPane();

                    searchBar = new HBox();
                    toolbarNormal = new HBox();

                    searchBar.setAlignment(Pos.CENTER);
                    searchBar.setPadding(new Insets(0, 5, 0, 5));
                    searchField = new JFXTextField();
                    searchField.setPromptText(i18n("search"));
                    HBox.setHgrow(searchField, Priority.ALWAYS);
                    PauseTransition pause = new PauseTransition(Duration.millis(100));
                    pause.setOnFinished(e -> skinnable.filteredList.setPredicate(skinnable.createPredicate(searchField.getText())));
                    searchField.textProperty().addListener((observable, oldValue, newValue) -> {
                        pause.setRate(1);
                        pause.playFromStart();
                    });

                    JFXButton closeSearchBar = createToolbarButton2(null, SVG.CLOSE, () -> {
                        changeToolbar(toolbarNormal);
                        searchField.clear();
                    });

                    onEscPressed(searchField, closeSearchBar::fire);

                    searchBar.getChildren().setAll(searchField, closeSearchBar);

                    toolbarNormal.getChildren().setAll(
                            createToolbarButton2(i18n("button.refresh"), SVG.REFRESH, skinnable::refreshList),
                            createToolbarButton2(i18n("search"), SVG.SEARCH, () -> changeToolbar(searchBar)),
                            createToolbarButton2(i18n("qinglan.download"), SVG.DOWNLOAD, () -> Controllers.navigate(Controllers.getDownloadPage())));

                    toolbarPane.setContent(toolbarNormal, ContainerAnimations.FADE);

                    FXUtils.setOverflowHidden(toolbarPane, 8);

                    root.getContent().add(toolbarPane);
                }

                {
                    SpinnerPane center = new SpinnerPane();
                    ComponentList.setVgrow(center, Priority.ALWAYS);
                    center.loadingProperty().bind(skinnable.loadingProperty());
                    center.failedReasonProperty().bind(skinnable.failedReasonProperty());

                    listView.setCellFactory(x -> new GameListCell());
                    listView.setItems(skinnable.getItems());

                    ignoreEvent(listView, KeyEvent.KEY_PRESSED, e -> e.getCode() == KeyCode.ESCAPE);

                    center.setContent(listView);

                    // Guidance shown instead of the empty list: import the modpack
                    // downloaded from the group files, or pick an existing Qinglan directory.
                    VBox guidance = new VBox(14);
                    guidance.setAlignment(Pos.CENTER);
                    guidance.setPadding(new Insets(24));

                    Label guideTitle = new Label(i18n("qinglan.guide.title"));
                    guideTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
                    guideTitle.setTextAlignment(TextAlignment.CENTER);
                    guideTitle.setWrapText(true);

                    Label guideHint = new Label(i18n("qinglan.guide.hint"));
                    guideHint.setTextAlignment(TextAlignment.CENTER);
                    guideHint.setWrapText(true);

                    JFXButton guideSeedButton = FXUtils.newRaisedButton(i18n("qinglan.guide.seed"));
                    guideSeedButton.setOnAction(e -> Controllers.navigate(Controllers.getQinglanSyncPage()));

                    JFXButton guideChooseDirButton = FXUtils.newRaisedButton(i18n("qinglan.guide.chooseDir"));
                    guideChooseDirButton.setOnAction(e -> skinnable.chooseQinglanDirectory());

                    guidance.getChildren().setAll(guideTitle, guideHint, guideSeedButton, guideChooseDirButton);

                    StackPane centerOverlay = new StackPane(center, guidance);
                    ComponentList.setVgrow(centerOverlay, Priority.ALWAYS);
                    centerOverlay.managedProperty().bind(center.managedProperty());
                    javafx.beans.binding.BooleanBinding listEmpty = Bindings.isEmpty(skinnable.getItems());
                    guidance.managedProperty().bind(listEmpty);
                    guidance.visibleProperty().bind(listEmpty);

                    root.getContent().add(centerOverlay);
                }

                pane.getChildren().setAll(root);
                getChildren().setAll(pane);
            }

            private void changeToolbar(HBox newToolbar) {
                Node oldToolbar = toolbarPane.getCurrentNode();
                if (newToolbar != oldToolbar) {
                    toolbarPane.setContent(newToolbar, ContainerAnimations.FADE);
                    if (newToolbar == searchBar) {
                        runInFX(searchField::requestFocus);
                    }
                }
            }
        }
    }
}
