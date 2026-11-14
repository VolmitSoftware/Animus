package art.arcane.animus.bukkit.toolkit;

import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.toolkit.Toolkit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * The Animus menu on Paper, Spigot and Folia: ticks watched players on their owners, hands the wand's clicks on NPCs, air
 * and blocks to the menu, reads each player's menu mode on join and forgets them on quit. NPCs the menu moves go there at once
 * through {@code relocator}, which runs on each NPC's owner.
 */
public final class BukkitToolkit implements Listener, AutoCloseable {
    private final Toolkit<Player, ItemStack> toolkit;
    private final AutoCloseable ticks;

    /** {@code ownerTicks} runs a task every tick on the global owner. */
    public BukkitToolkit(Plugin plugin, AnimusRuntime runtime, ScreenManager<Player, ItemStack> screens, NpcRuntime<Player, ItemStack> living, Executor files,
                         Function<Player, Optional<BodySkin>> skins, Toolkit.Relocator relocator, Function<Runnable, AutoCloseable> ownerTicks) {
        toolkit = Toolkit.install(new Toolkit.Parts<>(runtime::npcs, runtime::documents, screens, living, files, skins, Optional.of(relocator), System::nanoTime));
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Player online : plugin.getServer().getOnlinePlayers()) toolkit.joined(online.getUniqueId());
        ticks = ownerTicks.apply(() -> { for (UUID player : toolkit.watching()) living.players().deliver(player, toolkit::tick); });
    }

    /** First in the interaction chain. */
    public boolean interacted(Player player, UUID npc, boolean attack, boolean sneaking) { return toolkit.interacted(player, npc, attack, sneaking); }

    /**
     * A wand click on an NPC is for editing only. The interaction event fires before the NPC's behaviors, modules, quests,
     * shop, GUI, riding and the hit, so cancelling it here, first, keeps every one of them from running.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void npcClicked(VirtualNpcInteractionEvent event) {
        if (event.isPreview()) return;
        Player player = event.getViewer();
        if (toolkit.interacted(player, event.getNpcId(), event.getAction() == VirtualNpcInteractionEvent.Action.ATTACK, player.isSneaking())) event.setCancelled(true);
    }

    /** The wand's right-click on air or a block opens the menu instead of using the block; a swing at air reports the miss. */
    @EventHandler(priority = EventPriority.LOW)
    public void interact(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        switch (event.getAction()) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> {
                if (!toolkit.used(player, player.isSneaking())) return;
                event.setUseInteractedBlock(Event.Result.DENY);
                event.setUseItemInHand(Event.Result.DENY);
            }
            case LEFT_CLICK_AIR -> toolkit.swung(player);
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) { toolkit.joined(event.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { toolkit.quit(event.getPlayer().getUniqueId()); }

    @Override public void close() {
        try { ticks.close(); }
        catch (Exception ignored) { /* the scheduler is already gone at shutdown */ }
        HandlerList.unregisterAll(this);
        toolkit.close();
    }
}
