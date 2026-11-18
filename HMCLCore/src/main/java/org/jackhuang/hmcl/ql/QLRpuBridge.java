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
 * The only contact point between this launcher and the RPU resource-pack updater.
 *
 * <p>RPU is closed source and owns its own cache, protocol and verification, so the launcher
 * deliberately does not reimplement any of that. It exposes two hooks instead:</p>
 * <ul>
 *     <li>an update trigger -- the sync page shows one button and calls {@link #startUpdate};</li>
 *     <li>a progress sink -- whatever the updater hands to {@link ProgressSink} is rendered as
 *     the page's progress bar.</li>
 * </ul>
 *
 * <p>The updater registers itself once at startup, e.g. from the RPU side:
 * {@code QLRpuBridge.install((gameDir, progress) -> {...});}. Without a registered handler the
 * page reports that the interface is not loaded and keeps the button disabled.</p>
 */
@NotNullByDefault
public final class QLRpuBridge {

    /// Receives progress updates from the updater. Coordinates are free-form: a percentage
    /// ({@code finished}/{@code total}) or a byte count, the page renders the ratio.
    @FunctionalInterface
    public interface ProgressSink {
        /// @param finished units finished so far
        /// @param total    total units of the run, or {@code 0} when unknown
        void progress(long finished, long total);
    }

    /// What the closed-source updater implements to be triggerable from the launcher.
    @FunctionalInterface
    public interface UpdateHandler {
        /**
         * Performs (or starts) one update run.
         *
         * @param gameDir  the instance's run directory
         * @param progress sink the updater reports progress to; called from any thread
         * @throws Exception when the update cannot be started; the message is shown to the user
         */
        void startUpdate(Path gameDir, ProgressSink progress) throws Exception;
    }

    /// The registered updater, if any.
    private static final AtomicReference<@Nullable UpdateHandler> HANDLER = new AtomicReference<>();

    private QLRpuBridge() {
    }

    /// Registers the updater; a later call replaces the previous handler.
    ///
    /// @param handler the updater implementation
    public static void install(@Nullable UpdateHandler handler) {
        HANDLER.set(handler);
    }

    /// @return whether an updater has registered itself
    public static boolean isInstalled() {
        return HANDLER.get() != null;
    }

    /**
     * Triggers one update run through the registered updater.
     *
     * @param gameDir  the instance's run directory
     * @param progress sink the updater reports progress to
     * @throws Exception when no updater is installed or the updater itself fails
     */
    public static void startUpdate(Path gameDir, ProgressSink progress) throws Exception {
        UpdateHandler handler = HANDLER.get();
        if (handler == null)
            throw new IllegalStateException("The RPU update interface is not loaded");
        handler.startUpdate(gameDir, progress);
    }
}
