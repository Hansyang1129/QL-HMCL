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
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.ql.QLInstances;
import org.jackhuang.hmcl.setting.GameDirectory;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.WeakListenerHolder;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.construct.PageAware;
import org.jackhuang.hmcl.ui.versions.Versions;
import org.jackhuang.hmcl.util.PortablePath;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Qinglan find-instance page shown when no managed instance exists yet.
///
/// Offers two ways to bring up the Qinglan instance: importing the modpack
/// downloaded from the group files (via the sync page), or picking the
/// directory that already contains it. Other instances inside the chosen
/// directory are ignored. As soon as the Qinglan instance is detected and
/// selected, the launcher lands directly on its settings page.
@NotNullByDefault
public class QLFindInstancePage extends DecoratorAnimatedPage implements DecoratorPage, PageAware {

    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("qinglan.find.nav")));

    private final WeakListenerHolder holder = new WeakListenerHolder();

    public QLFindInstancePage() {
        Label title = new Label(i18n("qinglan.guide.title"));
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
        title.setWrapText(true);

        Label hint = new Label(i18n("qinglan.guide.hint"));
        hint.setWrapText(true);

        JFXButton seedButton = FXUtils.newRaisedButton(i18n("qinglan.guide.seed"));
        seedButton.setOnAction(e -> Controllers.navigate(Controllers.getQinglanSyncPage()));

        JFXButton chooseDirButton = FXUtils.newRaisedButton(i18n("qinglan.guide.chooseDir"));
        chooseDirButton.setOnAction(e -> chooseDirectory());

        VBox card = new VBox(16, title, hint, seedButton, chooseDirButton);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(24));

        VBox content = new VBox(card);
        content.setAlignment(Pos.CENTER);
        content.setFillWidth(true);
        setCenter(content);

        // whenever the launcher settles on a Qinglan instance, jump to its settings
        holder.add(FXUtils.onWeakChangeAndOperate(GameDirectoryManager.selectedInstanceProperty(), value ->
                arriveIfFound()));
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /// Re-checks the pinned instance when the page becomes visible again.
    @Override
    public void onPageShown() {
        arriveIfFound();
    }

    /// Navigates to the Qinglan instance settings as soon as it exists and is selected.
    private void arriveIfFound() {
        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        if (repository == null || !repository.isLoaded())
            return;
        String qinglanId = QLInstances.findQinglanInstance(repository);
        if (qinglanId != null && qinglanId.equals(repository.getSelectedInstance())) {
            Versions.modifyGameSettings(repository, qinglanId);
        }
    }

    /// Registers the picked directory as a user game directory and makes it current.
    ///
    /// The picked folder is often one level (or more) inside the actual game root;
    /// `locateGameRoot` walks back to the `.minecraft` root first. A directory with
    /// the same path is reused instead of being registered again. Selecting the
    /// directory starts an asynchronous version load (`refreshVersionsAsync`), which
    /// fires `versionsListeners` when finished; the one-shot listener below adopts
    /// the Qinglan instance then — selecting it navigates straight to its settings
    /// page — or shows a toast when the directory holds none.
    ///
    /// Nothing selects an instance automatically otherwise: the game list page's
    /// adopt logic only runs when its page is open, so the find-instance page
    /// must carry it itself.
    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(i18n("qinglan.guide.chooseDir"));
        File file = chooser.showDialog(Controllers.getStage());
        if (file == null) {
            return;
        }

        // Re-picking an already registered directory must not pile up duplicate
        // entries; reuse it and just make it current again.
        PortablePath gameRoot = PortablePath.fromPath(locateGameRoot(file.toPath()));
        GameDirectory chosen = findDirectoryByPath(gameRoot);
        if (chosen == null) {
            chosen = new GameDirectory(GameDirectoryManager.newGameDirectoryId(), null, gameRoot);
            GameDirectoryManager.addUserGameDirectory(chosen);
        }
        GameDirectoryManager.setSelectedGameDirectory(chosen);

        // The version event fires for the selected repository only, so pinning
        // the expected repository plus one consumption guard keeps re-picks from
        // letting stale registrations toast or adopt for other directories.
        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        AtomicBoolean consumed = new AtomicBoolean();
        GameDirectoryManager.registerVersionsListener(loaded -> {
            if (loaded != repository || !consumed.compareAndSet(false, true))
                return;
            String qinglanId = QLInstances.findQinglanInstance(loaded);
            if (qinglanId == null) {
                Controllers.showToast(i18n("qinglan.guide.notFound"));
            } else if (!qinglanId.equals(loaded.getSelectedInstance())) {
                loaded.setSelectedInstance(qinglanId);
            }
        });
    }

    /// Returns the registered game directory with the same path, or {@code null}.
    private static @Nullable GameDirectory findDirectoryByPath(PortablePath path) {
        for (GameDirectory gameDirectory : GameDirectoryManager.getGameDirectories())
            if (gameDirectory.getPath().isAbsolute() == path.isAbsolute()
                    && gameDirectory.getPath().getPath().equals(path.getPath()))
                return gameDirectory;
        return null;
    }

    /// Maps a user-picked folder to the game root containing `versions`.
    ///
    /// Handles the common mistakes of picking `versions` itself, the modpack's
    /// version folder (`\<game root>\versions\Qinglan-2nd`), or a folder that
    /// already is a game root.
    private static Path locateGameRoot(Path picked) {
        Path current = picked;
        if (fileNameEquals(current, "versions") && current.getParent() != null)
            return current.getParent();
        if (Files.isRegularFile(current.resolve(current.getFileName() + ".jar"))
                && Files.isRegularFile(current.resolve(current.getFileName() + ".json"))) {
            if (current.getParent() != null && fileNameEquals(current.getParent(), "versions")
                    && current.getParent().getParent() != null)
                return current.getParent().getParent();
            if (current.getParent() != null)
                return current.getParent();
        }
        if (Files.isDirectory(current.resolve("versions")))
            return current;
        return current;
    }

    private static boolean fileNameEquals(Path path, String expected) {
        return path.getFileName() != null && path.getFileName().toString().equalsIgnoreCase(expected);
    }
}
