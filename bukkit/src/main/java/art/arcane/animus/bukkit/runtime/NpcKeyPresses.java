package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.behavior.trigger.TriggerEvent;
import art.arcane.animus.core.runtime.NpcRuntime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

/**
 * The drop and swap-hands keys as NPC interaction triggers, on the player's own thread on Paper, Spigot and Folia. A
 * consumed press cancels the vanilla drop or swap. Bukkit reports a drop only when an item leaves the hand, so the drop
 * key with an empty hand reaches no trigger here.
 */
public final class NpcKeyPresses implements Listener {
    /** A drop this soon after an inventory click or close came from that window, not from the drop key. */
    private static final long WINDOW_NANOS = 50_000_000L;
    private final NpcRuntime<Player, ItemStack> runtime;
    private final Map<UUID, Long> windowed = new ConcurrentHashMap<>();

    public NpcKeyPresses(NpcRuntime<Player, ItemStack> runtime) { this.runtime = Objects.requireNonNull(runtime); }

    @EventHandler(priority = EventPriority.LOWEST) public void clicked(InventoryClickEvent event) { windowed.put(event.getWhoClicked().getUniqueId(), System.nanoTime()); }
    @EventHandler(priority = EventPriority.LOWEST) public void closed(InventoryCloseEvent event) { windowed.put(event.getPlayer().getUniqueId(), System.nanoTime()); }
    @EventHandler public void quit(PlayerQuitEvent event) { windowed.remove(event.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true) public void dropped(PlayerDropItemEvent event) {
        Long window = windowed.get(event.getPlayer().getUniqueId());
        if (window != null && System.nanoTime() - window < WINDOW_NANOS) return;
        if (runtime.pressed(event.getPlayer(), TriggerEvent.Action.DROP)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true) public void swapped(PlayerSwapHandItemsEvent event) {
        if (runtime.pressed(event.getPlayer(), TriggerEvent.Action.SWAP_HANDS)) event.setCancelled(true);
    }
}
