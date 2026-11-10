package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.npc.BossBarSettings;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.presentation.NpcBossBars;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** NPC boss bars drawn as Bukkit boss bars, on the runtime's owner tick. */
public final class BukkitBossBars implements NpcBossBars.Host<Player> {
    private final Supplier<NpcRepository> npcs;
    private final NpcRuntime<Player, ItemStack> living;
    private final NpcBossBars<Player> bars = new NpcBossBars<>(this);
    private long ticks;

    public BukkitBossBars(Supplier<NpcRepository> npcs, NpcRuntime<Player, ItemStack> living) { this.npcs = npcs; this.living = living; }

    /** Owner tick: updates every {@link NpcBossBars#INTERVAL} ticks. */
    public void tick() {
        if (ticks++ % NpcBossBars.INTERVAL != 0) return;
        bars.update(npcs.get().snapshot().values(), (UUID npc) -> living.active(npc).map(NpcRuntime.ActiveNpc::state).filter(VirtualNpcState.Snapshot::active)
                .map((VirtualNpcState.Snapshot state) -> new NpcBossBars.Active(state.position().world(), state.position().point(), state.health())));
    }

    public void close() { bars.close(); }

    @Override public NpcBossBars.Bar<Player> create(BossBarSettings settings) {
        BarFlag[] flags = settings.flags().stream().map((String flag) -> BarFlag.valueOf(flag.toUpperCase(Locale.ROOT))).toArray(BarFlag[]::new);
        BossBar bar = Bukkit.createBossBar("", BarColor.valueOf(settings.color().toUpperCase(Locale.ROOT)), BarStyle.valueOf(settings.style().toUpperCase(Locale.ROOT)), flags);
        return new NpcBossBars.Bar<>() {
            @Override public void title(String text) { if (!text.equals(bar.getTitle())) bar.setTitle(text); }
            @Override public void progress(double fill) { if (bar.getProgress() != fill) bar.setProgress(fill); }
            @Override public void show(Player player) { bar.addPlayer(player); }
            @Override public void hide(Player player) { bar.removePlayer(player); }
            @Override public Set<Player> viewers() { return Set.copyOf(bar.getPlayers()); }
            @Override public void close() { bar.removeAll(); }
        };
    }

    @Override public List<Player> players(String world) {
        World found;
        try { found = Bukkit.getWorld(UUID.fromString(world)); }
        catch (IllegalArgumentException named) { found = Bukkit.getWorld(world); }
        return found == null ? List.of() : found.getPlayers();
    }

    @Override public UUID id(Player player) { return player.getUniqueId(); }

    @Override public Vec3 position(Player player) {
        Location at = player.getLocation();
        return new Vec3(at.getX(), at.getY(), at.getZ());
    }

    @Override public boolean permitted(Player player, String node) { return player.hasPermission(node); }
    @Override public boolean granted(Player player, String node) { return BukkitRuntimeHost.setAndHeld(player, node); }
}
