package art.arcane.animus.core.gui;

import art.arcane.animus.core.behavior.trigger.TriggerDispatcher;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/** NPC runtime for screen tests: chosen NPCs are active, and player work runs at once. */
final class FakeNpcRuntime<P> implements NpcRuntime<P, Object> {
    final Map<UUID, ActiveNpc> active = new HashMap<>();
    private final Function<UUID, Optional<P>> players;

    FakeNpcRuntime(Function<UUID, Optional<P>> players) { this.players = players; }

    @Override public boolean available() { return true; }
    @Override public void interactions(InteractionListener<P> listener) {}
    @Override public String status() { return "fake"; }
    @Override public void bindTriggers(TriggerDispatcher dispatcher) {}
    @Override public void refresh() {}
    @Override public List<String> validateActivation(NpcDefinition definition) { return List.of(); }
    @Override public String inspect(String id) { return ""; }
    @Override public String rideStatus() { return ""; }
    @Override public boolean stopRider(String playerName) { return false; }
    @Override public Optional<String> selection(UUID viewer) { return Optional.empty(); }
    @Override public void select(UUID viewer, String id) {}
    @Override public Optional<ActiveNpc> active(UUID npc) { return Optional.ofNullable(active.get(npc)); }
    @Override public String navigationStatus(String id) { return ""; }
    @Override public PlayerAccess<P> players() {
        return new PlayerAccess<>() {
            @Override public boolean online(UUID player) { return players.apply(player).isPresent(); }
            @Override public Optional<P> owned(UUID player) { return players.apply(player); }
            @Override public List<P> ownedNear(WorldPosition at, double radius, int limit) { return List.of(); }
            @Override public boolean deliver(UUID player, Consumer<P> action) {
                Optional<P> online = players.apply(player);
                online.ifPresent(action);
                return online.isPresent();
            }
            @Override public boolean console(Runnable action) { action.run(); return true; }
        };
    }
    @Override public art.arcane.animus.core.runtime.ViewerPreviews previews() { throw new UnsupportedOperationException(); }
    @Override public boolean onOwner(UUID npc, Runnable work, Consumer<String> refused) { work.run(); return true; }
    @Override public boolean onOwner(String id, Runnable work, Consumer<String> refused) { work.run(); return true; }
    @Override public WorldPosition navigationOrigin(String id) { throw new UnsupportedOperationException(); }
    @Override public UUID navigate(String id, WorldPosition destination, MovementProfile.Mode mode, double blocksPerSecond) { throw new UnsupportedOperationException(); }
    @Override public boolean stopNavigation(String id) { return false; }
    @Override public void lookAt(String id, WorldPosition target) {}
    @Override public void followRoute(String id, String route) {}
    @Override public CompletableFuture<Void> awaitRoute(String id) { return CompletableFuture.completedFuture(null); }
    @Override public void setAppearance(String id, Optional<String> name, Optional<String> skin, boolean reset) {}
    @Override public void setEquipment(String id, EquipmentSlot slot, Object item) {}
    @Override public void close() {}
}
