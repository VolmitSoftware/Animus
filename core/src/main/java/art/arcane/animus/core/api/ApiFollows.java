package art.arcane.animus.core.api;

import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * NPCs walking after a player, mob or NPC. Every {@link #STEP_TICKS} ticks each follow takes one step on its NPC's thread:
 * it finds the target, walks toward it when it is further than the follow distance, and stops beside it otherwise.
 */
final class ApiFollows<P, I> {
    static final int STEP_TICKS = 10, LOST_TICKS = 100;
    static final double FAR = 8, WALK = 2.5, RUN = 4, REPATH = 1.5;

    /** One follow. Fields after the constructor change only on the NPC's thread. */
    private final class Follow {
        final String owner;
        final NpcHandle npc;
        final UUID target;
        final double distance;
        final CompletableFuture<Outcome<NpcHandle>> result = new CompletableFuture<>();
        /** The target's position as its own thread last reported it, for a player in another region. */
        final AtomicReference<Located> reported = new AtomicReference<>();
        WorldPosition goal;
        int missing, walking;

        Follow(String owner, NpcHandle npc, UUID target, double distance) { this.owner = owner; this.npc = npc; this.target = target; this.distance = distance; }
    }

    private record Located(WorldPosition position, long tick) {}

    private final NpcApi<P, I> api;
    private final Map<UUID, Follow> follows = new ConcurrentHashMap<>();
    private volatile long tick;

    ApiFollows(NpcApi<P, I> api) { this.api = api; }

    CompletionStage<Outcome<NpcHandle>> start(String owner, NpcHandle npc, UUID target, double distance) {
        if (!Double.isFinite(distance) || distance < 1 || distance > 16) return api.done(NpcApi.invalid("distance", "$.distance", "The follow distance is 1 to 16 blocks"));
        if (npc.uuid().equals(target)) return api.done(NpcApi.invalid("follow-self", "$.entity", "An NPC cannot follow itself"));
        if (api.definition(npc.uuid()).isEmpty()) return api.done(NpcApi.missing());
        if (!api.live(npc.uuid())) return api.done(NpcApi.inactive());
        Follow follow = new Follow(owner, npc, target, distance);
        Follow replaced = follows.put(npc.uuid(), follow);
        if (replaced != null) end(replaced, cancelled("another follow replaced it"));
        return api.delivered(follow.result);
    }

    /** Ends the NPC's follow, if it has one, as CANCELLED. */
    void stop(UUID npc, String reason) {
        Follow follow = follows.get(npc);
        if (follow != null) end(follow, cancelled(reason));
    }

    void release(String owner) {
        for (Follow follow : List.copyOf(follows.values())) if (follow.owner.equals(owner)) end(follow, cancelled(owner + " stopped"));
    }

    void close() { for (Follow follow : List.copyOf(follows.values())) end(follow, NpcApi.stopped()); }

    /** Once per server tick, on the global owner. */
    void tick(long now) {
        tick = now;
        if (follows.isEmpty() || now % STEP_TICKS != 0) return;
        for (Follow follow : List.copyOf(follows.values())) {
            if (api.definition(follow.npc.uuid()).isEmpty()) { end(follow, NpcApi.missing()); continue; }
            if (!api.live(follow.npc.uuid())) { end(follow, NpcApi.inactive()); continue; }
            report(follow);
            boolean accepted = api.parts.living().onOwner(follow.npc.uuid(), () -> step(follow), (String reason) -> {});
            if (!accepted) end(follow, NpcApi.inactive());
        }
    }

    /** A followed player's thread tells where the player is, for an NPC whose thread may not read them. */
    private void report(Follow follow) {
        NpcRuntime.PlayerAccess<P> players = api.parts.living().players();
        if (api.definition(follow.target).isPresent() || !players.online(follow.target)) return;
        long at = tick;
        players.deliver(follow.target, (P player) -> follow.reported.set(new Located(api.parts.ports().position(player), at)));
    }

    /** On the NPC's thread. */
    private void step(Follow follow) {
        if (follows.get(follow.npc.uuid()) != follow) return;
        Optional<NpcRuntime.ActiveNpc> active = api.parts.living().active(follow.npc.uuid());
        if (active.isEmpty()) { end(follow, NpcApi.inactive()); return; }
        WorldPosition at = active.get().state().position();
        Optional<WorldPosition> found = api.control.position(follow.target);
        if (found.isEmpty()) {
            Located reported = follow.reported.get();
            if (reported != null && tick - reported.tick() <= LOST_TICKS) found = Optional.of(reported.position());
        }
        if (found.isEmpty() || !found.get().world().equals(at.world())) {
            follow.missing += STEP_TICKS;
            if (follow.missing >= LOST_TICKS) end(follow, Outcome.rejected(Outcome.Status.NOT_FOUND, "follow-lost", "The followed entity left, died or changed worlds"));
            return;
        }
        follow.missing = 0;
        WorldPosition target = found.get();
        double away = at.point().distance(target.point());
        String id = active.get().id();
        try {
            if (away <= follow.distance + 0.5) {
                if (follow.goal != null) { api.parts.living().stopNavigation(id); follow.goal = null; }
                api.parts.living().lookAt(id, new WorldPosition(target.world(), target.point().add(new Vec3(0, ApiControl.EYES, 0))));
                return;
            }
            // A route that could not be planned leaves the NPC standing; ask again every few steps.
            if (follow.goal != null && follow.goal.point().distance(target.point()) < REPATH && ++follow.walking < 4) return;
            follow.walking = 0;
            Vec3 toward = target.point().subtract(at.point());
            double keep = Math.max(0, away - follow.distance);
            Vec3 stand = at.point().add(toward.multiply(keep / Math.max(away, 0.001)));
            follow.goal = target;
            api.parts.living().navigate(id, new WorldPosition(at.world(), new Vec3(stand.x(), target.point().y(), stand.z())), MovementProfile.Mode.WALK, away > FAR ? RUN : WALK);
        } catch (IllegalArgumentException | IllegalStateException refused) {
            follow.goal = null;
        }
    }

    private void end(Follow follow, Outcome<NpcHandle> outcome) {
        if (follows.remove(follow.npc.uuid(), follow) || !follow.result.isDone()) follow.result.complete(outcome);
    }

    private static Outcome<NpcHandle> cancelled(String reason) { return Outcome.rejected(Outcome.Status.CANCELLED, "follow-cancelled", reason); }
}
