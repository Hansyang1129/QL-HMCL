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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The three interface points the launcher keeps for the closed-source RPU client.
 *
 * <p>The RPU client owns {@code resourcepacks/**}, its {@code qlrpu/**} store and
 * {@code config/qlrpu-client.json}; it reads, writes and updates them itself. The launcher
 * therefore implements none of its protocol -- it only offers:</p>
 * <ol>
 *     <li>an update trigger: the page's button calls {@link #startUpdate};</li>
 *     <li>a progress projection: {@link Sink#status} paints status text and a percentage;</li>
 *     <li>a log projection: {@link Sink#log} appends one line to the on-page log box.</li>
 * </ol>
 *
 * <p>Registration mirrors the native Qinglan launcher's hook: once at startup,
 * {@code QLRpuBridge.install((gameDir, sink) -> {...});}. Without a registered handler the
 * card stays a placeholder and the button explains that the interface is reserved.</p>
 */
@NotNullByDefault
public final class QLRpuBridge {

    /// The launcher-side projections the RPU client reports into.
    public interface Sink {
        /**
         * Paints the RPU status line and the progress bar.
         *
         * @param text    status text; {@code null} keeps the placeholder
         * @param percent progress in {@code 0..100}; {@code null} hides the progress bar
         */
        void status(@Nullable String text, @Nullable Double percent);

        /// Appends one log line to the RPU log box.
        ///
        /// @param line the line to show; the page keeps the last few
        void log(String line);
    }

    /// What the closed-source RPU client implements to be triggerable from the launcher.
    @FunctionalInterface
    public interface UpdateHandler {
        /**
         * Runs (or starts) one resource-pack update.
         *
         * @param gameDir the instance's run directory
         * @param sink    the projections the updater reports to; called from any thread
         * @throws Exception when the update cannot be started; the message is shown to the user
         */
        void startUpdate(Path gameDir, Sink sink) throws Exception;
    }

    /// The registered updater, if any.
    private static final AtomicReference<@Nullable UpdateHandler> HANDLER = new AtomicReference<>();

    private QLRpuBridge() {
    }

    /// Registers the updater; a later call replaces the previous handler and {@code null}
    /// unregisters it.
    public static void install(@Nullable UpdateHandler handler) {
        HANDLER.set(handler);
    }

    /// @return whether the RPU client has registered itself
    public static boolean isInstalled() {
        return HANDLER.get() != null;
    }

    /**
     * Triggers one update run through the registered updater.
     *
     * @param gameDir the instance's run directory
     * @param sink    the projections to report into
     * @throws Exception when no updater is installed or the updater itself fails
     */
    public static void startUpdate(Path gameDir, Sink sink) throws Exception {
        UpdateHandler handler = HANDLER.get();
        if (handler == null)
            throw new IllegalStateException("The RPU client is not connected");
        handler.startUpdate(gameDir, sink);
    }
}
