package art.arcane.animus.mod;

import art.arcane.animus.core.npc.BossBarSettings;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.presentation.NpcBossBars;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.ItemStack;

/** NPC boss bars drawn as vanilla boss events on mod servers, on the server tick. */
final class ModBossBars implements NpcBossBars.Host<ServerPlayer> {
    private final MinecraftServer server;
    private final Supplier<NpcRepository> npcs;
    private final NpcRuntime<ServerPlayer, ItemStack> living;
    private final NpcBossBars<ServerPlayer> bars = new NpcBossBars<>(this);
    private long ticks;

    ModBossBars(MinecraftServer server, Supplier<NpcRepository> npcs, NpcRuntime<ServerPlayer, ItemStack> living) {
        this.server = server; this.npcs = npcs; this.living = living;
    }

    /** Server tick: updates every {@link NpcBossBars#INTERVAL} ticks. */
    void tick() {
        if (ticks++ % NpcBossBars.INTERVAL != 0) return;
        bars.update(npcs.get().snapshot().values(), (UUID npc) -> living.active(npc).map(NpcRuntime.ActiveNpc::state).filter(VirtualNpcState.Snapshot::active)
                .map((VirtualNpcState.Snapshot state) -> new NpcBossBars.Active(state.position().world(), state.position().point(), state.health())));
    }

    void close() { bars.close(); }

    @Override public NpcBossBars.Bar<ServerPlayer> create(BossBarSettings settings) {
        BossEvent.BossBarColor color = BossEvent.BossBarColor.PURPLE;
        for (BossEvent.BossBarColor candidate : BossEvent.BossBarColor.values()) if (candidate.name().equalsIgnoreCase(settings.color())) color = candidate;
        BossEvent.BossBarOverlay overlay = switch (settings.style().toLowerCase(Locale.ROOT)) {
            case "segmented_6" -> BossEvent.BossBarOverlay.NOTCHED_6;
            case "segmented_10" -> BossEvent.BossBarOverlay.NOTCHED_10;
            case "segmented_12" -> BossEvent.BossBarOverlay.NOTCHED_12;
            case "segmented_20" -> BossEvent.BossBarOverlay.NOTCHED_20;
            default -> BossEvent.BossBarOverlay.PROGRESS;
        };
        ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), VanillaDisplayTransport.component(""), color, overlay);
        bar.setDarkenScreen(settings.flags().contains("darken_sky"));
        bar.setPlayBossMusic(settings.flags().contains("play_boss_music"));
        bar.setCreateWorldFog(settings.flags().contains("create_fog"));
        return new NpcBossBars.Bar<>() {
            private String title = "";
            @Override public void title(String text) {
                if (text.equals(title)) return;
                title = text;
                bar.setName(VanillaDisplayTransport.component(text));
            }
            @Override public void progress(double fill) { bar.setProgress((float) Math.clamp(fill, 0, 1)); }
            @Override public void show(ServerPlayer player) { bar.addPlayer(player); }
            @Override public void hide(ServerPlayer player) { bar.removePlayer(player); }
            @Override public Set<ServerPlayer> viewers() { return new HashSet<>(bar.getPlayers()); }
            @Override public void close() { bar.removeAllPlayers(); }
        };
    }

    @Override public List<ServerPlayer> players(String world) {
        return server.getPlayerList().getPlayers().stream().filter((ServerPlayer player) -> ModWorldKeys.key(player.level()).equals(world)).toList();
    }

    @Override public UUID id(ServerPlayer player) { return player.getUUID(); }

    @Override public Vec3 position(ServerPlayer player) { return new Vec3(player.getX(), player.getY(), player.getZ()); }

    @Override public boolean permitted(ServerPlayer player, String node) { return VanillaRuntimeHost.permitted(player.permissions(), node); }
    @Override public boolean granted(ServerPlayer player, String node) { return VanillaRuntimeHost.granted(player.permissions(), node); }
}
