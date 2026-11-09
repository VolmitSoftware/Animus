package art.arcane.animus.core.runtime;

import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.recording.Playback;
import art.arcane.animus.core.recording.RecordingDocument;
import art.arcane.animus.core.recording.RecordingEvent;
import art.arcane.animus.core.recording.RecordingPlayback;
import art.arcane.animus.core.recording.RecordingPose;
import art.arcane.animus.core.recording.RecordingRun;
import art.arcane.animus.core.recording.RecordingSource;
import art.arcane.animus.core.recording.RecordingWorlds;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

/**
 * Recording playback of one runtime: the walk to a run's first pose, one recorded tick per server tick after it, the
 * catch-up after a refused step, and the stuck rules of the walk. Each run takes its turns as a route of
 * {@link RouteNavigator}. Owner thread only.
 */
final class RecordingPlayer<P, I> {
    static final String RECORDINGS_DISABLED = "recordings are disabled in config.toml";
    /** Blocks per second of the walk to a recording's first frame and of a catch-up, unless the NPC's movement sets a speed. */
    private static final double RECORDING_WALK_SPEED = 4;
    /** A body this close to a recording pose plays it without walking there first. */
    private static final double ARRIVED = 0.25;
    private final PersistedNpcService<P, I> service;

    RecordingPlayer(PersistedNpcService<P, I> service) { this.service = service; }

    /** Replaces the current route with a recording read from {@code recordings/<id>.json}; the read and any failure show in navigation status. */
    void play(PersistedNpcService<P, I>.Value value, String recording, Optional<Playback> playback) {
        Objects.requireNonNull(playback);
        if (recording == null || !recording.matches(RecordingDocument.ID)) throw new IllegalArgumentException("Invalid recording identifier");
        if (!service.recordingSettings.enabled()) throw new IllegalStateException(RECORDINGS_DISABLED);
        if (service.reads.routeReadsBusy()) throw new IllegalStateException("Recording reads are busy; retry shortly");
        RouteNavigator<P, I> routes = service.routes;
        routes.failWatch(value, "cancelled: replaced by a recording");
        service.reads.cancelRoute(value);
        routes.stopRoute(value, "cancelled: replaced by recording");
        value.waypoints = null; value.heldRecording = null; RouteNavigator.forgetStep(value);
        if (!service.reads.readRecording(value, new RecordingSource(recording, playback))) throw new IllegalStateException("I/O queue is full; retry shortly");
    }
    int playing() {
        int playing = 0;
        for (PersistedNpcService<P, I>.Entry entry : service.entries.values()) if (entry.value != null && entry.value.recording != null) playing++;
        return playing;
    }
    static String status(PersistedNpcService<?, ?>.Value value) {
        if (value.recording != null) return "; " + value.recording.describe();
        if (value.heldRecording != null) return "; " + value.heldRecording.describe(value.heldRecording.playback().finished() ? "finished" : "stopped");
        return value.recordingSource == null ? "" : "; recording-source=" + value.recordingSource.recording();
    }
    /** Starts a run: the NPC walks to the pose at its cursor, or plays at once when it already stands there. */
    void start(PersistedNpcService<P, I>.Value value, RecordingRun run) {
        value.recording = run;
        value.navigationStatus = "starting";
        beginWalk(value, run);
    }
    /** A planned leg to the run's goal pose; a goal the body already stands at plays without walking. */
    private void beginWalk(PersistedNpcService<P, I>.Value value, RecordingRun run) {
        if (value.recording != run || !value.state.snapshot().active()) return;
        WorldPosition at = value.state.snapshot().position();
        RecordingPose pose = run.goal();
        Vec3 target = new Vec3(pose.x(), pose.y(), pose.z());
        if (at.point().distance(target) <= ARRIVED) { run.arrived(); beginPlay(value, run, 1); return; }
        WorldPosition goal = new WorldPosition(at.world(), new Vec3(pose.x(), Math.floor(pose.y() + 0.000001), pose.z()));
        double speed = value.movement.routeSpeed(false, RECORDING_WALK_SPEED);
        try { service.routes.validateDestination(value, goal, MovementProfile.Mode.WALK, speed); }
        catch (IllegalArgumentException invalid) { end(value, run, "invalid: " + invalid.getMessage()); return; }
        RouteNavigator<P, I>.Route route = service.routes.recordingTurn(value, goal, speed, run, true);
        route.deadline = service.tickNumber + RecordingSteps.walkTicks(at.point().distance(goal.point()), speed);
        service.routes.queue(route, "queued");
    }
    /** The run's playing turn: one recorded tick per server tick from {@code delay} ticks on. */
    private void beginPlay(PersistedNpcService<P, I>.Value value, RecordingRun run, int delay) {
        if (value.recording != run) return;
        RouteNavigator<P, I>.Route route = service.routes.recordingTurn(value, value.state.snapshot().position(), RECORDING_WALK_SPEED, run, false);
        route.nextAttempt = service.tickNumber + Math.max(1, delay);
        service.routes.queue(route, run.phase() == RecordingRun.Phase.BLOCKED ? "blocked" : "playing");
    }
    /** The walk reached its goal pose: the frames play on from the next tick. */
    void arrived(RouteNavigator<P, I>.Route route) {
        PersistedNpcService<P, I>.Value value = route.value; RecordingRun run = route.recording;
        if (value.route != route) return;
        value.route = null;
        run.arrived();
        beginPlay(value, run, 1);
    }
    /** The walk could not reach its goal: aim further ahead, or apply the movement.stuck rules after five skips. */
    void walkFailed(RouteNavigator<P, I>.Route route, String reason) {
        PersistedNpcService<P, I>.Value value = route.value; RecordingRun run = route.recording;
        route.cancel();
        service.routes.order.remove(route);
        if (value.route == route) value.route = null;
        if (value.recording != run || !service.valueCurrent(value)) return;
        if (run.walkFailed() == RecordingRun.Failure.RETRY_FURTHER) { beginWalk(value, run); return; }
        if (value.movement.teleportsWhenStuck()) {
            RecordingPose pose = run.goal();
            WorldPosition goal = new WorldPosition(value.state.snapshot().position().world(), new Vec3(pose.x(), pose.y(), pose.z()));
            value.state.move(RouteRules.stuckTarget(goal, MovementProfile.Mode.WALK, PersistedNpcService.moveWidth(value), PersistedNpcService.moveHeight(value), service.host.collision(goal.world())));
            service.index.upsert(value.state.snapshot());
            value.poseRevision++; PersistedNpcService.setPose(value, (float) pose.yaw(), (float) pose.pitch(), true);
            run.teleported();
            beginPlay(value, run, 1);
            return;
        }
        run.blocked();
        if (run.playback().mode() == Playback.ONCE) { end(value, run, "blocked: " + reason); return; }
        beginPlay(value, run, RecordingRun.BLOCKED_RETRY_TICKS);
        if (value.route != null) service.routes.status(value.route, "blocked", reason + "; retrying in " + RecordingRun.BLOCKED_RETRY_TICKS + " ticks");
    }
    /** The run stops for good: a blocked once playback, or an invalid goal. A waiting behavior fails with the reason. */
    void end(PersistedNpcService<P, I>.Value value, RecordingRun run, String reason) {
        if (value.recording != run) return;
        RouteNavigator<P, I>.Route route = value.route;
        if (route != null) {
            value.route = null; service.routes.drop(route);
        }
        value.recording = null; value.heldRecording = run;
        value.recordingDoors.closeAll(service.host.doors()); value.doors.closeAll(service.host.doors());
        service.resetLook(value);
        if (run.step()) { value.heldRecording = null; service.routes.stepFailed(value, value.stepLeg, reason); return; }
        value.navigationStatus = reason;
        service.routes.failWatch(value, reason);
    }
    /**
     * One playing turn: the cursor moves a tick, the body steps to the interpolated pose after the same terrain check a
     * route step gets, and the tick's events run. A refused step catches up; terrain that is not loaded pauses the cursor.
     */
    RouteScheduler.Result play(RouteNavigator<P, I>.Route route) {
        PersistedNpcService<P, I>.Value value = route.value; RecordingRun run = route.recording;
        RouteNavigator<P, I> routes = service.routes;
        if (value.recording != run) { routes.stopRoute(value, "cancelled: recording replaced"); return RouteScheduler.Result.DONE; }
        if (run.phase() == RecordingRun.Phase.BLOCKED) {
            value.route = null;
            run.retry();
            beginWalk(value, run);
            return RouteScheduler.Result.DONE;
        }
        if (run.phase() == RecordingRun.Phase.UNAVAILABLE || run.phase() == RecordingRun.Phase.SLEEPING) run.retry();
        WorldPosition at = value.state.snapshot().position();
        String world = at.world();
        if (!service.host.worldLoaded(world)) { routes.stopRoute(value, "cancelled: world unavailable"); return RouteScheduler.Result.DONE; }
        RecordingPlayback.State before = run.state();
        run.playback().advance();
        RecordingPose pose = run.playback().pose();
        Vec3 to = new Vec3(pose.x(), pose.y(), pose.z());
        if (!service.host.chunkLoaded(world, Math.floorDiv((int) Math.floor(to.x()), 16), Math.floorDiv((int) Math.floor(to.z()), 16))) {
            pause(route, before, "unavailable terrain at the next frame; no chunk loading requested");
            return routes.current(route) ? RouteScheduler.Result.KEEP : RouteScheduler.Result.DONE;
        }
        double distance = at.point().distance(to);
        if (distance > 0.000001) {
            double width = PersistedNpcService.moveWidth(value), height = PersistedNpcService.moveHeight(value);
            int cells = RecordingSteps.cells(width, height, distance);
            if (!service.budget.cells(cells, service.navigationSettings)) { run.rewind(before); return RouteScheduler.Result.DEFER; }
            MotionValidator.Result check = RecordingSteps.check(at.point(), pose, width, height, service.host.collision(world));
            service.budget.motionCells -= check.sampledCells();
            if (check.status() == MotionValidator.Status.UNAVAILABLE_TERRAIN) {
                pause(route, before, check.detail());
                return routes.current(route) ? RouteScheduler.Result.KEEP : RouteScheduler.Result.DONE;
            }
            if (!check.clear()) {
                value.route = null;
                run.refused();
                beginWalk(value, run);
                if (value.route != null) routes.status(value.route, "catching up", "blocked: " + check.status() + "; " + check.detail());
                return RouteScheduler.Result.DONE;
            }
            value.state.move(new WorldPosition(world, to)); service.index.upsert(value.state.snapshot());
            value.poseRevision++;
        }
        PersistedNpcService.setPose(value, (float) pose.yaw(), (float) pose.pitch(), pose.onGround());
        applyLook(value, pose);
        for (RecordingEvent event : run.playback().due()) playEvent(value, world, event);
        route.progressTick = service.tickNumber;
        if (run.finished()) {
            value.route = null; value.recording = null; value.heldRecording = run;
            value.recordingDoors.closeAll(service.host.doors());
            service.resetLook(value);
            if (run.step()) { value.heldRecording = null; routes.stepReached(value, route); return RouteScheduler.Result.DONE; }
            value.navigationStatus = "finished";
            routes.finishWatch(value);
            return RouteScheduler.Result.DONE;
        }
        routes.status(route, "playing", "");
        return RouteScheduler.Result.KEEP;
    }
    /** The next frame cannot be played yet: the cursor stays and the turn comes back later. A waiting behavior is told now. */
    private void pause(RouteNavigator<P, I>.Route route, RecordingPlayback.State before, String reason) {
        RecordingRun run = route.recording;
        run.rewind(before);
        if (route.value.routeWatch != null) { service.routes.stopRoute(route.value, "unavailable: " + reason); return; }
        run.paused(RecordingRun.Phase.UNAVAILABLE);
        route.nextAttempt = service.tickNumber + 20;
        service.routes.status(route, "unavailable", reason);
    }
    private void playEvent(PersistedNpcService<P, I>.Value value, String world, RecordingEvent event) {
        switch (event) {
            case RecordingEvent.Swing ignored -> service.swing(value);
            case RecordingEvent.Equipment change -> {
                if (!service.packets.equipmentAvailable()) return;
                Optional<I> stack = change.item().isPresent() ? Optional.ofNullable(service.host.item(change.item().get(), "$.events", new ArrayList<>())) : service.host.emptyItem();
                if (change.item().isPresent() && stack.isEmpty()) return;
                if (stack.isPresent()) value.equipment.put(change.slot(), stack.get());
                else value.equipment.remove(change.slot());
                value.equipmentRevision++;
            }
            case RecordingEvent.Use use -> value.recordingDoors.use(world, new BlockPos(use.x(), use.y(), use.z()), service.host.doors());
        }
    }
    /** Sneaking, swimming, sprinting and gliding show on a player body as metadata, without respawning it; other bodies show the sneaking and sprinting flags. */
    private void applyLook(PersistedNpcService<P, I>.Value value, RecordingPose pose) {
        boolean player = BodyRules.PLAYER.equals(value.spec.type());
        NpcPose declared = value.spec.appearance().pose();
        service.changeLook(value, player ? RecordingSteps.pose(pose, declared) : declared, RecordingSteps.motion(pose, player));
    }
    /**
     * A recording read finished. A started read plays from the first frame; a hotload re-read leaves an unchanged
     * recording alone, restarts a changed one that is playing, forgets the kept cursor of a changed stopped one, and
     * starts an NPC that had nothing to play. A failed read keeps the last valid recording.
     */
    void finishRead(PersistedNpcService<P, I>.Value value, RecordingSource source, boolean start, RecordingDocument document, String failure) {
        RecordingRun current = value.recording != null ? value.recording : value.heldRecording;
        String error = failure == null && !service.recordingSettings.enabled() ? RECORDINGS_DISABLED : failure;
        if (error == null)
            error = RecordingWorlds.refusal(document, value.state.snapshot().position().world(), service.host::resolveWorld).orElse(null);
        if (error != null) {
            value.routeError = error + "; last-valid-recording-retained=" + (current != null);
            service.routes.failWatch(value, "unavailable: " + error);
            if (value.route == null && value.recording == null) value.navigationStatus = "unavailable: recording " + source.recording();
            return;
        }
        value.routeError = null;
        if (value.routeWatch != null && source.mode(document) != Playback.ONCE) {
            value.navigationStatus = "invalid: a looping recording never finishes"; service.routes.failWatch(value, value.navigationStatus); return;
        }
        boolean same = current != null && current.source().equals(source) && current.document().equals(document);
        if (!start) {
            if (same) return;
            if (value.recording == null && value.heldRecording != null) { value.heldRecording = null; return; }
            if (value.recording == null && value.route != null) return;
        }
        service.routes.stopRoute(value, "replaced by recording reload");
        value.heldRecording = null; value.waypoints = null;
        start(value, new RecordingRun(source, document));
    }
}
