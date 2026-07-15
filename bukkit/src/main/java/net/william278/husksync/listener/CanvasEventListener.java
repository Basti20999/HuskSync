/*
 * This file is part of HuskSync, licensed under the Apache License 2.0.
 *
 *  Copyright (c) William278 <will27528@gmail.com>
 *  Copyright (c) contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.william278.husksync.listener;

import net.william278.husksync.BukkitHuskSync;
import net.william278.husksync.data.DataSnapshot;
import net.william278.husksync.user.BukkitUser;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * Optional listener for <a href="https://docs.canvasmc.io/">CanvasMC</a>'s regionised
 * {@code io.canvasmc.canvas.event.PlayerSaveEvent}.
 * <p>
 * Canvas (a Folia fork) fires this event on the player's own region thread whenever their data is persisted - both on
 * disconnect and during the server's periodic autosave. HuskSync already saves on disconnect (via the standard quit
 * listener), so this listener only reacts to <b>autosaves</b>, treating them as the Canvas-native equivalent of the
 * {@link org.bukkit.event.world.WorldSaveEvent}. This is the region-safe moment to snapshot a player on Canvas, since
 * we are guaranteed to be on the thread that owns them.
 * <p>
 * The event class is resolved reflectively so this class links (and does nothing) on Paper, Spigot and vanilla Folia,
 * where the Canvas API is absent. It is registered via {@link org.bukkit.plugin.PluginManager#registerEvent} rather
 * than annotations for the same reason.
 */
public class CanvasEventListener implements Listener {

    private static final String SAVE_EVENT_CLASS = "io.canvasmc.canvas.event.PlayerSaveEvent";

    private final BukkitHuskSync plugin;

    public CanvasEventListener(@NotNull BukkitHuskSync plugin) {
        this.plugin = plugin;
    }

    /**
     * Attempts to register the Canvas {@code PlayerSaveEvent} listener.
     *
     * @return {@code true} if the listener was registered (i.e. the server is running Canvas), otherwise {@code false}
     */
    @SuppressWarnings("unchecked")
    public boolean register() {
        final Class<? extends Event> eventClass;
        try {
            eventClass = (Class<? extends Event>) Class.forName(SAVE_EVENT_CLASS);
        } catch (ClassNotFoundException | ClassCastException e) {
            return false; // Not running on Canvas; nothing to do
        }

        try {
            final Method isQuit = eventClass.getMethod("isQuit");
            final Method getPlayer = eventClass.getMethod("getPlayer");
            final EventExecutor executor = (ignored, event) -> handle(event, isQuit, getPlayer);
            plugin.getServer().getPluginManager().registerEvent(
                    eventClass, this, EventPriority.MONITOR, executor, plugin
            );
            plugin.log(Level.INFO, "Detected CanvasMC; hooked PlayerSaveEvent for region-safe periodic saves");
            return true;
        } catch (Throwable e) {
            plugin.log(Level.WARNING, "Failed to register the Canvas PlayerSaveEvent listener", e);
            return false;
        }
    }

    // Handles a fired PlayerSaveEvent, saving the player's current data on autosave (but not on disconnect)
    private void handle(@NotNull Event event, @NotNull Method isQuit, @NotNull Method getPlayer) {
        try {
            // Disconnect saves are already handled by the standard quit listener
            if ((boolean) isQuit.invoke(event)) {
                return;
            }
            if (plugin.isDisabling() || !plugin.getSettings().getSynchronization().isSaveOnWorldSave()) {
                return;
            }

            final BukkitUser user = BukkitUser.adapt((Player) getPlayer.invoke(event), plugin);
            if (user.isNpc() || user.hasDisconnected() || plugin.isLocked(user.getUuid())) {
                return;
            }

            // Snapshot here, on the player's region thread (where Canvas fires this event), so player state is
            // only ever read region-safely; then persist the immutable snapshot to the database/Redis off-thread
            final DataSnapshot.Packed snapshot = user.createSnapshot(DataSnapshot.SaveCause.WORLD_SAVE);
            plugin.runAsync(() -> plugin.getDataSyncer().saveData(
                    user, snapshot, (u, data) -> plugin.getRedisManager().setUserData(u, data)
            ));
        } catch (Throwable e) {
            plugin.debug("Failed to handle a Canvas PlayerSaveEvent", e);
        }
    }

}
