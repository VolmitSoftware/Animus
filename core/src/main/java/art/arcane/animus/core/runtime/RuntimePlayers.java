package art.arcane.animus.core.runtime;

import art.arcane.animus.core.virtual.WorldPosition;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The runtime's online players as behaviors and the editor reach them: owner work runs at once, work from any other
 * thread is scheduled on the owner. Off the owner, presence comes from a set the owner keeps on join and quit.
 */
final class RuntimePlayers<P, I> implements NpcRuntime.PlayerAccess<P> {
    private final PersistedNpcService<P, I> service;
    private final RuntimeHost<P, I> host;
    /** Online player ids for callers off the owner thread (the editor's timers and listener); the owner keeps it on join and quit. */
    private final Set<UUID> onlineIds = ConcurrentHashMap.newKeySet();
    /** Online players by chunk, rebuilt on the owner at most once per runtime tick, before the first lookup that needs it. */
    private final NearbyPlayers<P> nearby;
    private long nearbyTick = Long.MIN_VALUE;

    RuntimePlayers(PersistedNpcService<P, I> service, RuntimeHost<P, I> host) {
        this.service = service; this.host = host;
        nearby = new NearbyPlayers<>((P player) -> host.online(player) ? host.position(player) : null);
    }

    void joined(UUID player) { onlineIds.add(player); }
    void left(UUID player) { onlineIds.remove(player); }
    /** Owner only. A runtime that stopped ticking rebuilds for every lookup instead of answering from a stale tick. */
    List<P> near(WorldPosition at, double radius, int limit) {
        if (nearbyTick != service.tickNumber || !service.available()) { nearby.rebuild(host.onlinePlayers()); nearbyTick = service.tickNumber; }
        return nearby.near(at, radius, limit);
    }

    @Override public boolean online(UUID player) { return host.ownerThread() ? host.player(player).isPresent() : onlineIds.contains(player); }
    @Override public Optional<P> owned(UUID player) { service.owner(); return host.player(player); }
    @Override public List<P> ownedNear(WorldPosition at, double radius, int limit) { service.owner(); return near(at, radius, limit); }
    @Override public boolean deliver(UUID player, Consumer<P> action) {
        if (!host.ownerThread()) return onlineIds.contains(player) && later(() -> host.player(player).ifPresent(action));
        Optional<P> online = host.player(player);
        if (online.isEmpty()) return false;
        action.accept(online.get()); return true;
    }
    @Override public boolean console(Runnable action) {
        if (!host.ownerThread()) return later(action);
        action.run(); return true;
    }
    private boolean later(Runnable work) {
        try { return host.execute(work); }
        catch (RuntimeException refused) { return false; }
    }
}
