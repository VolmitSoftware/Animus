package art.arcane.animus.bukkit.toolkit;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.core.toolkit.Toolkit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * The toolkit on Paper, Spigot and Folia: ticks watched players on their owners and forgets them on quit. NPCs move to
 * the player at once through the main-thread runtime; on Folia, where that runtime does not exist, the saved place
 * moves them when the definition reloads.
 */
public final class BukkitToolkit implements Listener, AutoCloseable {
    private final Toolkit<Player, ItemStack> toolkit;
    private final AutoCloseable ticks;

    /** {@code mainThread} is null on Folia; {@code ownerTicks} runs a task every tick on the global owner. */
    public BukkitToolkit(Plugin plugin, AnimusRuntime runtime, ScreenManager<Player, ItemStack> screens, NpcRuntime<Player, ItemStack> living, Executor files,
                         Function<Player, Optional<BodySkin>> skins, PersistedNpcService<Player, ItemStack> mainThread, Function<Runnable, AutoCloseable> ownerTicks) {
        Optional<Toolkit.Relocator> relocator = Optional.ofNullable(mainThread).map((PersistedNpcService<Player, ItemStack> owner) -> owner::relocate);
        toolkit = Toolkit.install(new Toolkit.Parts<>(runtime::npcs, runtime::documents, screens, living, files, skins, relocator, System::nanoTime));
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        ticks = ownerTicks.apply(() -> { for (UUID player : toolkit.watching()) living.players().deliver(player, toolkit::tick); });
    }

    /** First in the interaction chain. */
    public boolean interacted(Player player, UUID npc, boolean attack, boolean sneaking) { return toolkit.interacted(player, npc, attack, sneaking); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { toolkit.quit(event.getPlayer().getUniqueId()); }

    @Override public void close() {
        try { ticks.close(); }
        catch (Exception ignored) { /* the scheduler is already gone at shutdown */ }
        HandlerList.unregisterAll(this);
        toolkit.close();
    }
}
