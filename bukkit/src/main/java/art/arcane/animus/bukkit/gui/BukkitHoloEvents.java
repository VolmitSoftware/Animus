package art.arcane.animus.bukkit.gui;

import art.arcane.animus.core.gui.HoloService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

/** Player events holograms and menus react to. Each runs where Bukkit fires it, which is the player's owner. */
public final class BukkitHoloEvents implements Listener {
    private final HoloService<Player, ItemStack> holograms;

    public BukkitHoloEvents(HoloService<Player, ItemStack> holograms) { this.holograms = holograms; }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { holograms.quit(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void died(PlayerDeathEvent event) { holograms.died(event.getEntity()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void respawned(PlayerRespawnEvent event) { holograms.worldChanged(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void worldChanged(PlayerChangedWorldEvent event) { holograms.worldChanged(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleported(PlayerTeleportEvent event) {
        if (event.getTo() != null && event.getFrom().getWorld() == event.getTo().getWorld()) holograms.teleported(event.getPlayer());
    }
}
