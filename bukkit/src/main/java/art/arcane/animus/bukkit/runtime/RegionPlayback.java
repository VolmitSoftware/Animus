package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.recording.Playback;
import art.arcane.animus.core.recording.RecordingEvent;
import art.arcane.animus.core.recording.RecordingPlayback;
import art.arcane.animus.core.recording.RecordingPose;
import art.arcane.animus.core.recording.RecordingRun;
import art.arcane.animus.core.runtime.RecordingSteps;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;

/**
 * Recording playback of one region actor: the run playing now, the run kept after a stop for resuming, and the turn
 * rules the actor's route slot drives on its owner. The same rules as the single-owner runtime; the actor supplies the
 * region's terrain, motion cells, walks and body, so nothing here touches the server.
 */
final class RegionPlayback {
    static final double WALK_SPEED = 4, ARRIVED = 0.25;

    /** What the playback needs of its actor. Every call runs on the actor's owner. */
    interface Actor {
        WorldPosition position();
        boolean active();
        long turns();
        double hullWidth();
        double hullHeight();
        /** The walk speed: the NPC's movement speed, or {@link #WALK_SPEED}. */
        double walkSpeed();
        /** The chunk is loaded and this owner holds it. */
        boolean loaded(String world, int chunkX, int chunkZ);
        /** Takes up to {@code wanted} of the region's motion cells this tick. */
        int takeCells(int wanted);
        void refundCells(int unused);
        int cellLimit();
        /** Terrain this owner holds; anything else reads as unavailable. */
        MotionValidator.Sampler terrain(String world);
        /** Replaces the route with a planned walk to {@code goal} that fails after {@code deadline}. Refuses an invalid goal. */
        void walk(RecordingRun run, WorldPosition goal, double speed, long deadline);
        /** Replaces the route with the run's playing turn, from {@code delay} turns on. */
        void play(RecordingRun run, int delay);
        /** The body stands on the pose: moved there when {@code moved}, and shows its rotation, flags and look. */
        void step(WorldPosition at, RecordingPose pose, boolean moved);
        void event(String world, RecordingEvent event);
        boolean teleportsWhenStuck();
        /** {@code movement.stuck} places the body on footing at the pose. */
        void teleport(RecordingPose pose);
        void status(String phase, String detail);
        /** The current playing turn comes back after {@code turns}. */
        void retryIn(int turns);
        /** A behavior waits for this route. */
        boolean watched();
        /** The playback is over: release its route, doors and look; a failure fails a waiting behavior. */
        void ended(String status, boolean failed);
        /** Stops the route, which keeps the run's cursor for resuming. */
        void stop(String reason);
    }

    private final Actor actor;
    /** The run playing or walking to its pose now, and the run kept after a stop or finish. */
    RecordingRun run, held;
    /** For status from any thread. */
    volatile boolean playing;

    RegionPlayback(Actor actor) { this.actor = actor; }

    /** Plays {@code next}: walks to the pose at its cursor first. */
    void start(RecordingRun next) {
        run = next; held = null; playing = true;
        walkIn(next);
    }

    /** The route stopped: the cursor stays for resuming. */
    void stopped() {
        if (run == null) return;
        held = run; run = null; playing = false;
    }

    /** A kept run that is not finished continues from its cursor; false when there is none. */
    boolean resume() {
        RecordingRun kept = held;
        if (kept == null || kept.playback().finished()) return false;
        start(handedOver(kept));
        return true;
    }

    /**
     * The NPC came to a new owner, or its anchor moved: a walk is planned again from where the body stands to the same
     * goal, a blocked run waits again, and a playing run walks to the pose at its cursor, which it plays at once when the
     * body already stands there.
     */
    void adopted() {
        RecordingRun current = run;
        if (current == null) return;
        if (current.walking()) { walkIn(current); return; }
        if (current.phase() == RecordingRun.Phase.BLOCKED) { actor.play(current, RecordingRun.BLOCKED_RETRY_TICKS); return; }
        run = handedOver(current);
        walkIn(run);
    }

    /** The run a new owner continues: the same cursor, mode, direction and step segment, walking in to the pose at the cursor. */
    static RecordingRun handedOver(RecordingRun previous) { return previous.resumed(); }

    /** A planned leg to the run's goal pose, or play at once when the body already stands there. */
    void walkIn(RecordingRun target) {
        if (run != target || !actor.active()) return;
        WorldPosition at = actor.position();
        RecordingPose pose = target.goal();
        if (at.point().distance(new Vec3(pose.x(), pose.y(), pose.z())) <= ARRIVED) { target.arrived(); actor.play(target, 1); return; }
        WorldPosition goal = new WorldPosition(at.world(), new Vec3(pose.x(), Math.floor(pose.y() + 0.000001), pose.z()));
        double speed = actor.walkSpeed();
        try { actor.walk(target, goal, speed, actor.turns() + RecordingSteps.walkTicks(at.point().distance(goal.point()), speed)); }
        catch (IllegalArgumentException invalid) { end("invalid: " + invalid.getMessage()); }
    }

    /** The walk reached its goal pose: the frames play on from the next turn. */
    void arrived(RecordingRun target) {
        if (run != target) return;
        target.arrived();
        actor.play(target, 1);
    }

    /** The walk could not reach its goal: aim further ahead, or apply the movement.stuck rules after five skips. */
    void walkFailed(RecordingRun target, String reason) {
        if (run != target || !actor.active()) return;
        if (target.walkFailed() == RecordingRun.Failure.RETRY_FURTHER) { walkIn(target); return; }
        if (actor.teleportsWhenStuck()) {
            actor.teleport(target.goal());
            target.teleported();
            actor.play(target, 1);
            return;
        }
        target.blocked();
        if (target.source().playback() == Playback.ONCE) { end("blocked: " + reason); return; }
        actor.play(target, RecordingRun.BLOCKED_RETRY_TICKS);
        actor.status("blocked", reason + "; retrying in " + RecordingRun.BLOCKED_RETRY_TICKS + " ticks");
    }

    /** The run stops for good, and a waiting behavior fails with the reason. */
    void end(String reason) {
        if (run == null) return;
        held = run; run = null; playing = false;
        actor.ended(reason, true);
    }

    /**
     * One playing turn: the cursor moves a tick, the step to the interpolated pose is checked against the terrain this
     * owner holds and charged to the region's motion cells, and the tick's events run. False when the region ran out of
     * cells, so the turn comes back next tick with the cursor where it was.
     */
    boolean turn(RecordingRun target) {
        if (run != target) return true;
        if (target.phase() == RecordingRun.Phase.BLOCKED) { target.retry(); walkIn(target); return true; }
        if (target.phase() == RecordingRun.Phase.UNAVAILABLE || target.phase() == RecordingRun.Phase.SLEEPING) target.retry();
        WorldPosition at = actor.position();
        RecordingPlayback.State before = target.state();
        target.playback().advance();
        RecordingPose next = target.playback().pose();
        Vec3 to = new Vec3(next.x(), next.y(), next.z());
        if (!actor.loaded(at.world(), Math.floorDiv((int) Math.floor(to.x()), 16), Math.floorDiv((int) Math.floor(to.z()), 16))) {
            pause(target, before, "unavailable terrain at the next frame; no chunk loading requested");
            return true;
        }
        double distance = at.point().distance(to);
        boolean moved = distance > 0.000001;
        if (moved) {
            int wanted = Math.min(actor.cellLimit(), RecordingSteps.cells(actor.hullWidth(), actor.hullHeight(), distance));
            int granted = actor.takeCells(wanted);
            if (granted < wanted) { actor.refundCells(granted); target.rewind(before); return false; }
            MotionValidator.Result check = RecordingSteps.check(at.point(), next, actor.hullWidth(), actor.hullHeight(), actor.terrain(at.world()));
            actor.refundCells(Math.max(0, granted - check.sampledCells()));
            if (check.status() == MotionValidator.Status.UNAVAILABLE_TERRAIN) { pause(target, before, check.detail()); return true; }
            if (!check.clear()) {
                target.refused();
                walkIn(target);
                actor.status("catching up", "blocked: " + check.status() + "; " + check.detail());
                return true;
            }
        }
        actor.step(new WorldPosition(at.world(), to), next, moved);
        for (RecordingEvent event : target.playback().due()) actor.event(at.world(), event);
        if (target.finished()) {
            held = target; run = null; playing = false;
            actor.ended("finished", false);
            return true;
        }
        actor.status("playing", "region owner");
        return true;
    }

    private void pause(RecordingRun target, RecordingPlayback.State before, String reason) {
        target.rewind(before);
        if (actor.watched()) { actor.stop("unavailable: " + reason); return; }
        target.paused(RecordingRun.Phase.UNAVAILABLE);
        actor.retryIn(20);
        actor.status("unavailable", reason);
    }

    /** {@code; recording=... state=...} for navigation status and inspect, or the declared source before it loads. */
    String describe(String declared) {
        RecordingRun current = run, kept = held;
        if (current != null) return "; " + current.describe();
        if (kept != null) return "; " + kept.describe(kept.playback().finished() ? "finished" : "stopped");
        return declared == null ? "" : "; recording-source=" + declared;
    }
}
