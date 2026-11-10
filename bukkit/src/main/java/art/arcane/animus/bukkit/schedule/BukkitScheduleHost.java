package art.arcane.animus.bukkit.schedule;

import art.arcane.animus.bukkit.BukkitWorldLookup;
import art.arcane.animus.bukkit.runtime.DefinitionItems;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.schedule.RuntimeScheduleWorld;
import art.arcane.animus.core.schedule.ScheduleWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

/** The world clock, items and console of a Bukkit server, for NPC schedules. World time is global, so any owner may read it. */
public final class BukkitScheduleHost implements RuntimeScheduleWorld.Host<ItemStack> {
    private final Server server;
    private final NpcRuntime<?, ItemStack> living;

    public BukkitScheduleHost(Server server, NpcRuntime<?, ItemStack> living) { this.server = server; this.living = living; }

    @Override public Optional<ScheduleWorld.Clock> clock(String world) {
        World loaded = BukkitWorldLookup.find(server, world);
        return loaded == null ? Optional.empty() : Optional.of(new ScheduleWorld.Clock(loaded.getTime(), loaded.hasStorm()));
    }

    @Override public ItemStack item(Optional<NpcItem> item) {
        if (item.isEmpty()) return new ItemStack(Material.AIR);
        List<String> errors = new ArrayList<>();
        ItemStack stack = DefinitionItems.stack(item.get(), "item", errors);
        if (!errors.isEmpty()) throw new IllegalArgumentException(errors.getFirst());
        return stack;
    }

    @Override public void command(String command) {
        if (!living.players().console(() -> server.dispatchCommand(server.getConsoleSender(), command)))
            throw new IllegalStateException("The console refused the command " + command);
    }
}
