package art.arcane.animus.bukkit.integration.quests;

import art.arcane.animus.bukkit.api.AnimusNpcs;
import art.arcane.animus.bukkit.api.NpcInfo;
import art.arcane.animus.bukkit.api.NpcPosition;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.admin.Administration;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** {@link QuestNpcHost} over the running definitions and NPC runtime. Register it as a listener so hiding ends when a viewer leaves. */
public final class RuntimeQuestNpcHost implements QuestNpcHost, Listener {
    private final AnimusRuntime runtime;
    private final NpcRuntime<?, ?> living;
    private final AnimusNpcs npcs;
    private final Executor files;
    private final Consumer<String> log;
    private final Map<UUID, Set<UUID>> hiddenByViewer = new ConcurrentHashMap<>();

    public RuntimeQuestNpcHost(AnimusRuntime runtime, NpcRuntime<?, ?> living, AnimusNpcs npcs, Executor files, Consumer<String> log) {
        this.runtime = Objects.requireNonNull(runtime);
        this.living = Objects.requireNonNull(living);
        this.npcs = Objects.requireNonNull(npcs);
        this.files = Objects.requireNonNull(files);
        this.log = Objects.requireNonNull(log);
        living.previews().alsoHide((UUID viewer, String id, UUID npc) -> {
            Set<UUID> hidden = hiddenByViewer.get(viewer);
            return hidden != null && hidden.contains(npc);
        });
    }

    @Override public AnimusNpcs npcs() { return npcs; }

    @Override public Optional<Location> location(UUID npc) {
        Optional<VirtualNpcState.Snapshot> state = living.state(npc).filter(VirtualNpcState.Snapshot::active);
        if (state.isPresent()) {
            WorldPosition at = state.get().position();
            return new NpcPosition(at.world(), at.point().x(), at.point().y(), at.point().z()).toLocation();
        }
        return definition(npc).flatMap(RuntimeQuestNpcHost::saved);
    }

    @Override public Optional<Double> height(UUID npc) {
        return living.state(npc).map((VirtualNpcState.Snapshot state) -> state.bounds().max().y() - state.bounds().min().y());
    }

    @Override public void teleport(String key, Location to) {
        WorldPosition at = new WorldPosition(Objects.requireNonNull(to.getWorld()).getUID().toString(), new Vec3(to.getX(), to.getY(), to.getZ()));
        float yaw = to.getYaw();
        change(key, (Administration administration) -> administration.move(key, at, yaw));
    }

    @Override public void spawned(String key, boolean spawned) { change(key, (Administration administration) -> administration.spawned(key, spawned)); }

    @Override public void hide(UUID viewer, UUID npc, boolean hidden) {
        if (hidden) hiddenByViewer.computeIfAbsent(viewer, (UUID ignored) -> ConcurrentHashMap.newKeySet()).add(npc);
        else hiddenByViewer.computeIfPresent(viewer, (UUID ignored, Set<UUID> set) -> { set.remove(npc); return set.isEmpty() ? null : set; });
    }

    @Override public boolean hidden(UUID viewer, UUID npc) {
        Set<UUID> hidden = hiddenByViewer.get(viewer);
        return hidden != null && hidden.contains(npc);
    }

    @EventHandler public void quit(PlayerQuitEvent event) { hiddenByViewer.remove(event.getPlayer().getUniqueId()); }

    private Optional<NpcDefinition> definition(UUID npc) {
        return npcs.byUuid(npc).map(NpcInfo::key).flatMap(runtime.npcs()::find);
    }

    private static Optional<Location> saved(NpcDefinition definition) {
        JsonElement position = definition.json().get("position");
        if (position == null || !position.isJsonObject()) return Optional.empty();
        JsonObject at = position.getAsJsonObject();
        try { return new NpcPosition(at.get("world").getAsString(), at.get("x").getAsDouble(), at.get("y").getAsDouble(), at.get("z").getAsDouble()).toLocation(); }
        catch (RuntimeException incomplete) { return Optional.empty(); }
    }

    private void change(String key, Change change) {
        try {
            files.execute(() -> {
                try { change.run(new Administration(runtime)); living.refresh(); }
                catch (IOException | RuntimeException failure) { log.accept("Quest plugin change to " + key + " failed: " + failure.getMessage()); }
            });
        } catch (RejectedExecutionException full) { log.accept("Quest plugin change to " + key + " refused: the file lane is full"); }
    }

    @FunctionalInterface private interface Change { String run(Administration administration) throws IOException; }
}
