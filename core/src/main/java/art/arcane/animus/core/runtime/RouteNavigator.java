package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusLog;
import art.arcane.animus.core.config.NavigationSettings;
import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.BodyMobility;
import art.arcane.animus.core.navigation.CaptureProvenance;
import art.arcane.animus.core.navigation.LongLeg;
import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.navigation.MovementStyle;
import art.arcane.animus.core.navigation.NavigationPlan;
import art.arcane.animus.core.navigation.PathExecution;
import art.arcane.animus.core.navigation.PathPlanner;
import art.arcane.animus.core.navigation.StyledLeg;
import art.arcane.animus.core.navigation.RoutePause;
import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.recording.RecordingRun;
import art.arcane.animus.core.recording.RouteRecordings;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

/**
 * Routes and waypoint patrols of one runtime: manual legs, inline and reusable routes, recording steps, planning,
 * walking, stuck rules and arrival. Every route takes turns in one scheduler under the tick's {@link TickBudget}.
 * Owner thread only.
 */
final class RouteNavigator<P, I> {
    /** A sleeping route looks for a nearby player again after this many ticks. */
    private static final int AWAKE_RECHECK_TICKS = 20;
    private static final String STEP_ERROR = "recording step ";
    private final PersistedNpcService<P, I> service;
    /** Every route takes turns here; there is no limit on how many exist, only on the work done in one tick. */
    final RouteScheduler<Route> order;
    final NavigationPressure pressure = new NavigationPressure();

    RouteNavigator(PersistedNpcService<P, I> service, NavigationSettings settings) {
        this.service = service;
        order = new RouteScheduler<>(settings.routesPerTick(), settings.wakeChecksPerTick(), (Route route) -> route.nextAttempt);
    }

    // ---- Requests from commands and behaviors ----

    UUID navigate(PersistedNpcService<P, I>.Value value, WorldPosition destination, MovementProfile.Mode mode, double blocksPerSecond) {
        Objects.requireNonNull(destination);
        MovementProfile.Mode legMode = value.movement.legMode(Objects.requireNonNull(mode), null);
        validateDestination(value, destination, legMode, blocksPerSecond);
        failWatch(value, "cancelled: replaced by a new route");
        stopRoute(value, "cancelled: replaced by manual route");
        service.reads.cancelRoute(value);
        value.waypoints = null;
        Route route = new Route(value, destination, legMode, blocksPerSecond, null, null);
        value.route = route; order.add(route, service.tickNumber); value.navigationStatus = "queued; route=" + route.id;
        return route.id;
    }
    /** Stops every route, read and recording of the NPC; true when one was running. */
    boolean stop(PersistedNpcService<P, I>.Value value) {
        boolean running = value.route != null || value.routeReadQueued || value.routeTask != null || value.recording != null
                || (value.waypoints != null && !value.waypoints.complete());
        service.reads.cancelRoute(value); value.waypoints = null; stopRoute(value, "cancelled by administrator"); failWatch(value, "cancelled: navigation stopped"); return running;
    }
    /** Replaces the current route with a reusable route; its read and any read failure are reported in navigation status. */
    void follow(PersistedNpcService<P, I>.Value value, String route) {
        if (route == null || !route.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid reusable route identifier");
        if (service.reads.routeReadsBusy()) throw new IllegalStateException("Reusable route reads are busy; retry shortly");
        failWatch(value, "cancelled: replaced by a new route");
        service.reads.cancelRoute(value);
        stopRoute(value, "cancelled: replaced by reusable route");
        value.waypoints = null; forgetStep(value);
        service.reads.readRoute(value, route);
        value.navigationStatus = "loading reusable route " + route;
    }
    /** The current route's outcome, for one watcher. Call right after starting the route, in the same owner turn. */
    CompletableFuture<Void> await(PersistedNpcService<P, I>.Value value) {
        if (value.route == null && value.routeTask == null) throw new IllegalStateException("The NPC has no route to wait for");
        failWatch(value, "cancelled: replaced by a new wait");
        value.routeWatch = new CompletableFuture<>();
        return value.routeWatch;
    }
    /**
     * Restarts the definition's own patrol if nothing else moves the NPC: a recording stopped earlier continues from its
     * cursor, a declared recording or reusable route is read again, and inline waypoints start from their first point.
     */
    boolean resume(PersistedNpcService<P, I>.Value value) {
        if (value.route != null || value.routeTask != null || value.routeReadQueued || value.recording != null) return false;
        RecordingRun held = value.heldRecording;
        if (held != null && held.step()) {
            value.heldRecording = null;
            WaypointProgress route = value.stepProgress;
            if (!held.playback().finished() && route != null && route.current(value.stepLeg)) {
                value.waypoints = route;
                service.recordings.start(value, held.resumed());
                return true;
            }
            forgetStep(value);
        }
        if (held != null && !held.playback().finished()) {
            value.heldRecording = null; value.waypoints = null;
            service.recordings.start(value, RecordingRun.resume(held.source(), held.document(), Optional.of(held.state())));
            return true;
        }
        if (startInline(value)) return true;
        if (value.recordingSource != null) {
            value.heldRecording = null;
            return service.reads.readRecording(value, value.recordingSource);
        }
        service.reads.requestRoute(value);
        return value.routeSource != null;
    }
    void failWatch(PersistedNpcService<P, I>.Value value, String reason) {
        CompletableFuture<Void> watch = value.routeWatch;
        if (watch == null) return;
        value.routeWatch = null;
        watch.completeExceptionally(NavigationRefusals.of(reason));
    }
    void finishWatch(PersistedNpcService<P, I>.Value value) {
        CompletableFuture<Void> watch = value.routeWatch;
        if (watch == null) return;
        value.routeWatch = null;
        watch.complete(null);
    }

    // ---- Route lifecycle ----

    MovementProfile profile(PersistedNpcService<P, I>.Value value, MovementProfile.Mode mode) { return value.movement.apply(routeProfile(value, mode), value.spec.type()); }
    private MovementProfile routeProfile(PersistedNpcService<P, I>.Value value, MovementProfile.Mode mode) {
        return BodyMobility.forRoute(value.spec.type(), PersistedNpcService.moveWidth(value), PersistedNpcService.moveHeight(value), mode);
    }
    void validateDestination(PersistedNpcService<P, I>.Value value, WorldPosition destination, MovementProfile.Mode mode, double speed) {
        RouteRules.validateLeg(value.state.snapshot().position(), destination, mode, speed, PersistedNpcService.moveWidth(value), PersistedNpcService.moveHeight(value));
    }
    boolean current(Route route) {
        PersistedNpcService<P, I>.Value value = route.value; PersistedNpcService<P, I>.Entry entry = service.byUuid.get(value.spec.definition().uuid());
        return service.available() && service.runtime.isRunning() && entry != null && entry.value == value && value.route == route
                && value.state.snapshot().active() && value.authority.accepts(service.runtime.npcs().snapshot().get(entry.id));
    }
    /** The route becomes the NPC's current one and takes its turns from this tick. */
    void queue(Route route, String state) {
        route.value.route = route; order.add(route, service.tickNumber); status(route, state, "");
    }
    /** A recording's turn: a planned walk to its goal pose when {@code walk}, otherwise its frames. */
    Route recordingTurn(PersistedNpcService<P, I>.Value value, WorldPosition goal, double speed, RecordingRun run, boolean walk) {
        return new Route(value, goal, MovementProfile.Mode.WALK, speed, run, walk);
    }
    /** Ends the route's turns, cancelling its plan and its walk; the NPC's route field is the caller's. */
    void drop(Route route) { order.remove(route); route.cancel(); }
    void stopRoute(PersistedNpcService<P, I>.Value value, String reason) {
        Route route = value.route; value.route = null;
        value.doors.closeAll(service.host.doors());
        RecordingRun run = value.recording;
        if (run != null) {
            // The cursor stays in the value, so resumePatrol continues from it.
            value.recording = null; value.heldRecording = run;
            value.recordingDoors.closeAll(service.host.doors());
            service.resetLook(value);
        }
        if (route != null && route.styled != null && run == null) service.resetLook(value);
        if (route != null) drop(route);
        if (route == null) value.navigationStatus = reason;
        else { status(route, reason, ""); failWatch(value, reason); }
    }
    void status(Route route, String state, String detail) {
        route.value.navigationStatus = state + "; route=" + route.id + "; waypoint=" + route.waypoint + "; goal=" + route.goal.point()
                + (route.capture == null ? "" : "; capture=" + route.capture.firstTick() + ".." + route.capture.lastTick()
                + "; sampled=" + route.capture.sampledVoxels() + "; unavailable=" + route.capture.unavailableVoxels())
                + (detail.isEmpty() ? "" : "; " + detail);
    }
    private void suspend(Route route, String reason) {
        // A waiting behavior is told now and the route ends, rather than holding its slot until terrain returns.
        if (route.value.routeWatch != null) { stopRoute(route.value, "unavailable: " + reason); return; }
        route.dropPlan();
        // Resume by planning from the current point. An airborne kinematic phase is retained and revalidated.
        route.nextAttempt = service.tickNumber + 40; status(route, "suspended", reason);
    }
    void worldUnloaded(String world) {
        for (Route route : order.routes()) if (route.goal.world().equals(world))
            stopRoute(route.value, "cancelled: world unloaded");
    }
    /** Shutdown: every route stops, so waiting behaviors hear of it, and the scheduler forgets them. */
    void close() {
        for (Route route : order.routes()) stopRoute(route.value, "cancelled: runtime shutdown");
        order.clear();
    }

    // ---- Waypoints ----

    private WaypointProgress progress(PersistedNpcService<P, I>.Value value, WaypointSettings declared, String route) {
        return WaypointProgress.of(declared, value.spec.position().point(), value.spec.definition().uuid(), route);
    }
    /** Queues the route's next leg. A pause leg holds the route's slot until its wait ends; a random or wander leg that cannot start pauses instead. */
    private void beginLeg(PersistedNpcService<P, I>.Value value, Optional<WaypointProgress.Leg> next) {
        WaypointProgress progress = value.waypoints;
        if (progress == null || next.isEmpty() || !value.state.snapshot().active()) return;
        WaypointProgress.Leg leg = next.get();
        WaypointSettings waypointSettings = progress.settings();
        WorldPosition at = value.state.snapshot().position();
        WorldPosition goal = leg.goal() == null ? at : new WorldPosition(at.world(), leg.goal());
        double speed = progress.speed(value.movement.routeSpeed(value.routeSpeedDeclared, waypointSettings.speed()));
        MovementStyle style = waypointSettings.styleTo(leg.arrival());
        MovementProfile.Mode mode = value.movement.legMode(waypointSettings.mode(), style);
        if (leg.goal() != null) {
            try { validateDestination(value, goal, mode, speed); }
            catch (IllegalArgumentException invalid) {
                if (leg.jittered()) { beginLeg(value, progress.exact(leg)); return; }
                value.navigationStatus = "invalid waypoint " + leg.arrival() + ": " + invalid.getMessage(); failWatch(value, value.navigationStatus);
                Optional<WaypointProgress.Leg> retry = progress.failed(leg);
                if (retry.isPresent()) beginLeg(value, retry);
                return;
            }
        }
        Route route = new Route(value, goal, mode, speed, leg, style);
        route.nextAttempt = service.tickNumber + Math.max(1, leg.waitTicks());
        queue(route, leg.pause() ? "waiting to retry" : "queued");
    }
    /**
     * Starts the definition's inline route; false when it has none. A route with recording steps first reads their
     * recordings, so its first leg waits for that read.
     */
    boolean startInline(PersistedNpcService<P, I>.Value value) {
        value.waypoints = WaypointSettings.parse(value.spec.definition()).map((WaypointSettings declared) -> progress(value, declared, WaypointProgress.INLINE)).orElse(null);
        if (value.waypoints == null) return false;
        if (value.waypoints.settings().recordings().isEmpty()) beginLeg(value, value.waypoints.start(value.state.snapshot().position().point()));
        else service.reads.requestRoute(value);
        return true;
    }
    /** A step leg's wait is over: walk in to the segment's first frame and play it, or block the leg with the reason it cannot. */
    private void startStep(PersistedNpcService<P, I>.Value value, WaypointProgress.Leg leg) {
        WaypointProgress progress = value.waypoints;
        if (progress == null || !progress.current(leg) || !value.state.snapshot().active()) return;
        if (!service.recordingSettings.enabled()) { stepFailed(value, leg, RecordingPlayer.RECORDINGS_DISABLED); return; }
        RouteRecordings recordings = progress.recordings() == null ? RouteRecordings.NONE : progress.recordings();
        RouteRecordings.Start start = recordings.start(leg.arrival(), leg.step(), value.state.snapshot().position().world(), service.host::resolveWorld);
        if (start.run() == null) { stepFailed(value, leg, start.problem()); return; }
        if (value.routeError != null && value.routeError.startsWith(STEP_ERROR)) value.routeError = null;
        value.stepLeg = leg; value.stepProgress = progress; value.heldRecording = null;
        service.recordings.start(value, start.run());
    }
    /** A step that cannot play or finish is a blocked leg: the route's failure rules apply, so random routes retry and others halt. */
    void stepFailed(PersistedNpcService<P, I>.Value value, WaypointProgress.Leg leg, String reason) {
        WaypointProgress progress = value.waypoints;
        value.stepLeg = null; value.stepProgress = null;
        value.routeError = STEP_ERROR + (leg == null ? "" : leg.arrival() + ": ") + reason;
        value.navigationStatus = "blocked: " + value.routeError;
        failWatch(value, value.navigationStatus);
        if (progress == null || leg == null || !progress.current(leg)) return;
        String failure = value.navigationStatus;
        beginLeg(value, progress.failed(leg));
        if (value.route != null) status(value.route, "waiting to retry", "after " + failure);
    }
    /** A step played its segment to toTick: the waypoint is reached where the segment ended, and the route goes on from there. */
    void stepReached(PersistedNpcService<P, I>.Value value, Route route) {
        WaypointProgress progress = value.waypoints; WaypointProgress.Leg leg = value.stepLeg;
        value.stepLeg = null; value.stepProgress = null;
        if (leg == null) return;
        value.arrivals++;
        value.navigationStatus = "arrived; route=" + route.id + "; waypoint=" + leg.arrival() + "; recording step";
        service.host.waypointReached(value.spec.definition().uuid(), value.spec.definition().id(), route.id, leg.arrival(), value.state.snapshot().position());
        if (service.triggers != null) service.triggers.waypoint(value.spec.definition().uuid(), leg.arrival());
        if (progress == null || value.waypoints != progress || value.route != null || !service.valueCurrent(value) || !progress.current(leg)) {
            if (progress == null) finishWatch(value);
            return;
        }
        Optional<WaypointProgress.Leg> next = progress.arrived(leg, value.state.snapshot().position().point());
        if (next.isPresent()) beginLeg(value, next);
        else finishWatch(value);
    }
    /** Drops a stopped step and its route, so a later resumePatrol starts the declared route rather than the old step. */
    static void forgetStep(PersistedNpcService<?, ?>.Value value) {
        if (value.heldRecording != null && value.heldRecording.step()) value.heldRecording = null;
        if (value.recording == null || !value.recording.step()) { value.stepLeg = null; value.stepProgress = null; }
    }
    /**
     * The route is unchanged but a recording its steps play changed: a step playing it restarts from fromTick, as a
     * recording source restarts, and a stopped step of it forgets its cursor.
     */
    private void recordingsReloaded(PersistedNpcService<P, I>.Value value, RouteRecordings before, RouteRecordings after) {
        RecordingRun playing = value.recording;
        if (playing != null && playing.step() && after.changed(before, playing.source().recording())) {
            WaypointProgress.Leg leg = value.stepLeg;
            stopRoute(value, "recording step restarted: recording changed");
            value.heldRecording = null;
            startStep(value, leg);
            return;
        }
        RecordingRun held = value.heldRecording;
        if (held != null && held.step() && after.changed(before, held.source().recording())) value.heldRecording = null;
    }
    /** A reusable route, or the recordings of an inline route's steps, finished reading for a current NPC. */
    void finishRead(PersistedNpcService<P, I>.Value value, String source, boolean inline, WaypointSettings settings, RouteRecordings recordings, String error) {
        if (error != null) {
            value.routeError = error + "; last-valid-route-retained=" + (value.waypoints != null);
            failWatch(value, "unavailable: " + error);
            if (value.route == null && value.waypoints == null) value.navigationStatus = "unavailable: reusable route";
            return;
        }
        value.routeError = null;
        if (value.routeWatch != null && settings.endless()) { value.navigationStatus = "invalid: a looping route never finishes"; failWatch(value, value.navigationStatus); return; }
        if (inline && value.waypoints == null) return;
        if (value.waypoints != null && !value.waypoints.reload(settings)) {
            RouteRecordings before = value.waypoints.recordings();
            value.waypoints.recordings(recordings);
            if (before != null) { recordingsReloaded(value, before, recordings); return; }
        } else {
            stopRoute(value, "replaced by reusable route reload");
            forgetStep(value);
            if (value.waypoints == null) value.waypoints = progress(value, settings, inline ? WaypointProgress.INLINE : source);
            value.waypoints.recordings(recordings);
        }
        beginLeg(value, value.waypoints.start(value.state.snapshot().position().point()));
    }

    // ---- Turns ----

    void tick() {
        AnimusMetrics metrics = service.metrics;
        NavigationSettings settings = service.navigationSettings;
        service.budget.navigation(settings);
        RouteScheduler.Tick turn = order.tick(service.tickNumber, new RouteScheduler.Listener<>() {
            @Override public boolean awake(Route route) { return routeAwake(route); }
            @Override public RouteScheduler.Result work(Route route) {
                if (!service.available()) return RouteScheduler.Result.DEFER;
                if (!metrics.sampling()) return workRoute(route);
                long started = metrics.start();
                RouteScheduler.Result result = workRoute(route);
                metrics.npcCost(metrics.npc(route.value.spec.definition().uuid()), metrics.start() - started);
                return result;
            }
            @Override public void slept(Route route) {
                route.dropPlan();
                route.value.doors.closeAll(service.host.doors());
                if (route.recording != null) route.recording.paused(RecordingRun.Phase.SLEEPING);
                status(route, "sleeping", "no player within " + (int) service.settings.viewDistance() + " blocks");
            }
            @Override public void woke(Route route) {
                route.progressTick = service.tickNumber;
                if (route.recording != null) route.recording.retry();
                if (route.recording != null && route.walk)
                    route.deadline = service.tickNumber + RecordingSteps.walkTicks(route.value.state.snapshot().position().point().distance(route.goal.point()), route.speed);
                status(route, "queued", "a player is near");
            }
        });
        pressure.observe(service.tickNumber, turn.deferred() + service.budget.pathRefusals, "routesPerTick=" + settings.routesPerTick()
                + ", motionCellsPerTick=" + settings.motionCellsPerTick() + ", planningStartsPerTick=" + settings.planningStartsPerTick()
                + ", planningJobs=" + settings.planningJobs() + ", captureReadsPerTick=" + settings.captureReadsPerTick()
                + " in [navigation] of config.toml").ifPresent(service.host::warn);
    }
    /**
     * A patrol sleeps while no player is within view distance: it holds no turn, plan or budget. A manual route, and one
     * a behavior waits for, never sleeps. The answer is kept for a second, so checking costs one player scan per route per second.
     */
    private boolean routeAwake(Route route) {
        if ((route.leg == null && route.recording == null) || route.value.routeWatch != null || !current(route)) return true;
        // A new route has never looked: awakeChecked starts unset, and subtracting from it must not overflow into "recent".
        if (route.awakeChecked != Long.MIN_VALUE && service.tickNumber - route.awakeChecked < AWAKE_RECHECK_TICKS) return route.awake;
        route.awakeChecked = service.tickNumber;
        route.awake = !service.playersNear(route.value.state.snapshot().position(), service.settings.viewDistance(), 1).isEmpty();
        return route.awake;
    }
    /** Whether the NPC's route pause holds it now; a player within its range starts one, at most every few ticks. */
    private boolean paused(PersistedNpcService<P, I>.Value value, long tick) {
        RoutePause pause = value.pause;
        if (pause == null) return false;
        if (pause.playerRange() > 0 && value.pauseClock.checkDue(tick)
                && !service.playersNear(value.state.snapshot().position(), pause.playerRange(), 1).isEmpty()) value.pauseClock.pause(pause, tick);
        return value.pauseClock.paused(tick);
    }
    private RouteScheduler.Result workRoute(Route route) {
        PersistedNpcService<P, I>.Value value = route.value;
        long tick = service.tickNumber;
        if (!current(route)) { stopRoute(value, "cancelled by definition/lifecycle change"); return RouteScheduler.Result.DONE; }
        if (route.recording != null && !service.recordingSettings.enabled()) {
            value.routeError = RecordingPlayer.RECORDINGS_DISABLED;
            service.recordings.end(value, route.recording, "unavailable: " + RecordingPlayer.RECORDINGS_DISABLED);
            return RouteScheduler.Result.DONE;
        }
        if (route.recording != null && !route.walk) return service.recordings.play(route);
        if (route.leg != null && route.leg.pause()) {
            value.route = null;
            if (value.waypoints != null) beginLeg(value, value.waypoints.resume(route.leg, value.state.snapshot().position().point()));
            return RouteScheduler.Result.DONE;
        }
        if (route.leg != null && route.leg.step() != null && route.recording == null) {
            value.route = null;
            startStep(value, route.leg);
            return RouteScheduler.Result.DONE;
        }
        if (route.leg != null && paused(value, tick)) {
            route.progressTick = tick;
            status(route, "paused", "");
            return RouteScheduler.Result.KEEP;
        }
        String world = route.goal.world();
        if (!service.host.worldLoaded(world)) { stopRoute(value, "cancelled: world unavailable"); return RouteScheduler.Result.DONE; }
        if (route.recording != null && tick > route.deadline) {
            failLeg(route, "unreachable: the walk to the recording took longer than " + (route.deadline - route.progressStart) + " ticks");
            return current(route) ? RouteScheduler.Result.KEEP : RouteScheduler.Result.DONE;
        }
        if ((route.leg != null || route.recording != null) && value.movement.stuck(tick - route.progressTick)) {
            failLeg(route, "stuck: no progress for " + (tick - route.progressTick) + " ticks");
            return current(route) ? RouteScheduler.Result.KEEP : RouteScheduler.Result.DONE;
        }
        TickBudget budget = service.budget;
        try {
            if (route.execution == null) {
                if (route.plan == null) {
                    BlockPos feet = BlockPos.containing(value.state.snapshot().position().point());
                    BlockPos goal = BlockPos.containing(route.goal.point());
                    LongLeg.Request request = route.longLeg.request(value.state.snapshot().position().point());
                    // A segment of a long leg needs only the terrain it captures; an unloaded chunk there makes its plan wait.
                    if (!service.host.chunkLoaded(world, Math.floorDiv(feet.x(), 16), Math.floorDiv(feet.z(), 16))
                            || (request.direct() && !service.host.chunkLoaded(world, Math.floorDiv(goal.x(), 16), Math.floorDiv(goal.z(), 16)))) {
                        suspend(route, "unavailable terrain; no chunk loading requested");
                    } else if (service.falls.beforeLeg(route)) {
                        return RouteScheduler.Result.KEEP;
                    } else if (budget.planStarts <= 0) {
                        status(route, "queued", "waiting for a planning turn");
                        return RouteScheduler.Result.YIELD;
                    } else {
                        budget.planStarts--;
                        route.plan = service.navigation.plan(world, request.bounds(), feet, request.aim(), route.profile(), request.limits());
                        route.result = route.plan.future().toCompletableFuture(); status(route, "planning", "");
                    }
                } else if (route.result.isDone()) {
                    PathPlanner.Result planned = route.result.join(); route.capture = route.plan.capture().orElse(null); route.plan = null; route.result = null;
                    if (planned.status() == PathPlanner.Status.UNAVAILABLE_TERRAIN) suspend(route, "unavailable captured terrain");
                    else {
                        LongLeg.Next next = route.longLeg.planned(planned);
                        if (next == LongLeg.Next.BLOCKED) blockLeg(route, "failed: " + planned.status());
                        else if (next == LongLeg.Next.RETRY) {
                            route.nextAttempt = tick + RouteRules.REPLAN_BACKOFF_TICKS;
                            status(route, "replanning", "attempt " + route.longLeg.attempts() + " after " + planned.status());
                        }
                        else {
                            route.segment = next == LongLeg.Next.SEGMENT;
                            Vec3 end = next == LongLeg.Next.FINAL ? route.goal.point() : planned.path().getLast().point();
                            route.execution = new PathExecution(value.state.snapshot().position().point(), planned.path(), end, route.profile());
                            status(route, "moving", RouteRules.moving(route.segment));
                        }
                    }
                }
            }
            if (current(route) && route.execution != null && tick >= route.nextAttempt) {
                if (!route.execution.position().equals(value.state.snapshot().position().point())) { stopRoute(value, "cancelled: authoritative position changed"); }
                else if (route.execution.finished()) executed(route);
                else {
                    double width = PersistedNpcService.moveWidth(value), height = PersistedNpcService.moveHeight(value);
                    PathExecution.Proposal step = route.execution.next(route.styled == null ? route.speed / 20 : route.styled.stepDistance(route.speed)).orElseThrow();
                    int sweep = RouteRules.motionCells(width, height, step.from().distance(step.to())), settle = RouteRules.motionCells(width, height, 0);
                    // A step larger than the whole tick's cells still runs when it is the tick's first, so a big body never stalls.
                    if (!budget.cells(sweep + (step.landing() ? settle : 0), service.navigationSettings)) return RouteScheduler.Result.DEFER;
                    MotionValidator.Sampler terrain = service.host.collision(world);
                    if (route.mode == MovementProfile.Mode.WALK && value.movement.openDoors()) {
                        Optional<BlockPos> door = value.doors.prepare(world, step.from(), step.to(), width, route.height(), terrain, service.host.doors());
                        if (door.isPresent()) {
                            failLeg(route, "blocked: the door at " + door.get().x() + " " + door.get().y() + " " + door.get().z() + " could not be opened");
                            return current(route) ? RouteScheduler.Result.KEEP : RouteScheduler.Result.DONE;
                        }
                    }
                    MotionValidator.Result validation = MotionValidator.check(step.from(), step.to(), width, route.height(), step.medium(), step.requireSupport(), sweep, terrain);
                    budget.motionCells -= validation.sampledCells();
                    if (validation.clear() && step.landing()) {
                        validation = MotionValidator.check(step.to(), step.to(), width, route.height(),
                                route.mode == MovementProfile.Mode.SWIM ? MotionValidator.Medium.WATER : MotionValidator.Medium.GROUND,
                                route.mode != MovementProfile.Mode.SWIM, settle, terrain);
                        budget.motionCells -= validation.sampledCells();
                    }
                    if (validation.status() == MotionValidator.Status.UNAVAILABLE_TERRAIN) suspend(route, validation.detail());
                    else if (!validation.clear()) blockedStep(route, "blocked: " + validation.status() + "; " + validation.detail());
                    else {
                        route.execution.commit(step);
                        Vec3 delta = step.to().subtract(step.from());
                        float yaw = Math.hypot(delta.x(), delta.z()) > .000001 ? (float) Math.toDegrees(Math.atan2(-delta.x(), delta.z())) : value.yaw;
                        value.state.move(new WorldPosition(route.goal.world(), step.to())); service.index.upsert(value.state.snapshot());
                        value.poseRevision++; PersistedNpcService.setPose(value, yaw, 0, step.onGround());
                        if (route.styled != null) service.styleLook(value, route.styled.moved(step, value.spec.appearance().pose(), (Vec3 at, double room) -> {
                            MotionValidator.Result clear = MotionValidator.check(at, at, PersistedNpcService.moveWidth(value), room, MotionValidator.Medium.AMPHIBIOUS_AIR, false, settle, terrain);
                            budget.motionCells -= clear.sampledCells();
                            return clear.status() == MotionValidator.Status.BLOCKED;
                        }));
                        value.doors.passed(step.to(), PersistedNpcService.moveWidth(value), service.host.doors());
                        route.progressTick = tick;
                        status(route, "moving", RouteRules.moving(route.segment));
                        if (value.movement.arrived(step.to(), route.goal.point())) arrive(route);
                        else if (route.execution.finished()) executed(route);
                    }
                }
            }
        } catch (RejectedExecutionException full) { budget.pathRefusals++; route.nextAttempt = tick + 20; status(route, "queued", "path capacity"); }
        catch (RuntimeException failure) {
            if (value.route == route) failLeg(route, "failed: " + PersistedNpcService.concise(failure));
            else stopRoute(value, "failed: " + PersistedNpcService.concise(failure));
        }
        return current(route) ? RouteScheduler.Result.KEEP : RouteScheduler.Result.DONE;
    }
    /** A walked path ended: a segment of a long leg plans the next one from here, the last one arrives. */
    private void executed(Route route) {
        if (!route.segment) { arrive(route); return; }
        route.execution = null; route.segment = false;
        status(route, "queued", "next long leg segment");
    }
    /** Terrain changed in front of the body: plan again from here until the leg runs out of tries. */
    private void blockedStep(Route route, String reason) {
        if (route.longLeg.blocked() == LongLeg.Next.BLOCKED) { blockLeg(route, reason); return; }
        route.execution = null; route.segment = false; route.nextAttempt = service.tickNumber + RouteRules.REPLAN_BACKOFF_TICKS;
        status(route, "replanning", reason);
    }
    /**
     * Terrain blocks this leg for good, or no plan found a way. Its route fails as any failed leg does, and the server log
     * says so once per NPC, naming the leg and which of the two it was, so an imported patrol that stops is explained.
     */
    private void blockLeg(Route route, String reason) {
        if (route.recording != null) { failLeg(route, reason); return; }
        PersistedNpcService<P, I>.Value value = route.value;
        if (exactPoint(route, reason)) return;
        String id = value.spec.definition().id();
        boolean once = AnimusLog.shared().debug();
        if (!once || !value.blockedWarned) {
            if (once) value.blockedWarned = true;
            boolean terrain = route.longLeg.terrainBlocks();
            AnimusLog.shared().routine(RouteRules.blockedLegs(terrain), id, RouteRules.blockedLeg(id, route.waypoint, route.origin, route.goal, reason,
                    route.leg != null && value.movement.teleportsWhenStuck(), terrain), Optional.of(RouteRules.blockedLegCase(id, route.waypoint, reason)), service.host::warn);
        }
        failLeg(route, reason);
    }
    /** A leg that cannot finish: random and wander routes pause and choose again, every other route halts. */
    private void failLeg(Route route, String reason) {
        if (route.recording != null) { service.recordings.walkFailed(route, reason); return; }
        PersistedNpcService<P, I>.Value value = route.value;
        if (exactPoint(route, reason) || teleportStuck(route)) return;
        WaypointProgress progress = value.waypoints;
        stopRoute(value, reason);
        if (progress == null || route.leg == null || !progress.retries() || value.route != null || !service.valueCurrent(value)) return;
        String failure = value.navigationStatus;
        beginLeg(value, progress.failed(route.leg));
        if (value.route != null) status(value.route, "waiting to retry", "after " + failure);
    }
    /** A leg aimed at a jittered goal that cannot finish walks to its exact point instead, once, before any stuck rule. */
    private boolean exactPoint(Route route, String reason) {
        PersistedNpcService<P, I>.Value value = route.value; WaypointProgress progress = value.waypoints;
        if (route.leg == null || !route.leg.jittered() || progress == null || value.route != route || !current(route)) return false;
        Optional<WaypointProgress.Leg> exact = progress.exact(route.leg);
        if (exact.isEmpty()) return false;
        drop(route); value.route = null;
        beginLeg(value, exact);
        if (value.route != null) status(value.route, "queued", "exact point after " + reason);
        return true;
    }
    /**
     * A waypoint leg of an NPC whose movement teleports when stuck ends at its point, as Citizens' default stuck action
     * does, and the route goes on. False when the NPC stops instead.
     */
    private boolean teleportStuck(Route route) {
        PersistedNpcService<P, I>.Value value = route.value;
        if (route.leg == null || route.leg.pause() || !value.movement.teleportsWhenStuck() || value.route != route || !current(route)) return false;
        route.cancel();
        route.plan = null; route.result = null; route.execution = null;
        order.remove(route);
        value.state.move(RouteRules.stuckTarget(route.goal, route.mode, PersistedNpcService.moveWidth(value), PersistedNpcService.moveHeight(value), service.host.collision(route.goal.world())));
        service.index.upsert(value.state.snapshot());
        value.poseRevision++; PersistedNpcService.setPose(value, value.yaw, 0, true);
        arrive(route);
        return true;
    }
    /** Arrival keeps doors the NPC still stands in only while another leg follows; a finished route closes them all. */
    void arrive(Route route) {
        PersistedNpcService<P, I>.Value value = route.value;
        arriveAt(route);
        if (route.styled != null && value.recording == null)
            service.styleLook(value, value.route != null ? route.styled.resting(value.spec.appearance().pose()) : route.styled.finished(value.spec.appearance().pose()));
        if (value.route == null) value.doors.closeAll(service.host.doors());
        else value.doors.passed(value.state.snapshot().position().point(), PersistedNpcService.moveWidth(value), service.host.doors());
    }
    private void arriveAt(Route route) {
        PersistedNpcService<P, I>.Value value = route.value; if (!current(route)) return;
        if (route.recording != null) { service.recordings.arrived(route); return; }
        WaypointProgress progress = value.waypoints;
        value.route = null; status(route, "arrived", "");
        value.arrivals++;
        service.host.waypointReached(value.spec.definition().uuid(), value.spec.definition().id(), route.id, route.waypoint, value.state.snapshot().position());
        if (service.triggers != null) service.triggers.waypoint(value.spec.definition().uuid(), route.waypoint);
        if (progress == null) finishWatch(value);
        PersistedNpcService<P, I>.Entry entry = service.byUuid.get(value.spec.definition().uuid());
        if (!service.available() || entry == null || entry.value != value || value.route != null || value.waypoints != progress || progress == null
                || !value.state.snapshot().active() || !value.authority.accepts(service.runtime.npcs().snapshot().get(entry.id))) return;
        teleport(value, progress.settings().teleportAt(route.waypoint));
        Optional<WaypointProgress.Leg> next = route.leg == null ? Optional.empty() : progress.arrived(route.leg, value.state.snapshot().position().point());
        if (next.isPresent()) beginLeg(value, next);
        else finishWatch(value);
    }

    /** A point's teleport moves the NPC before its wait; terrain that is not loaded or not clear leaves it where it arrived. */
    private void teleport(PersistedNpcService<P, I>.Value value, WaypointSettings.Teleport teleport) {
        if (teleport == null) return;
        WorldPosition at = value.state.snapshot().position();
        String world = teleport.world() == null ? at.world() : service.host.resolveWorld(teleport.world()).orElse(null);
        if (world == null) { service.host.warn("NPC " + value.spec.definition().id() + " could not teleport: world " + teleport.world() + " is not loaded"); return; }
        if (!service.relocate(value.spec.definition().id(), new WorldPosition(world, teleport.position()))) {
            service.host.warn("NPC " + value.spec.definition().id() + " could not teleport to " + teleport.position() + ": the place is not loaded or not clear");
            return;
        }
        PersistedNpcService.setPose(value, teleport.yaw() == null ? value.yaw : teleport.yaw(), teleport.pitch() == null ? value.pitch : teleport.pitch(), true);
    }
    final class Route {
        final UUID id = UUID.randomUUID(); final PersistedNpcService<P, I>.Value value; final WorldPosition goal;
        final MovementProfile.Mode mode; final double speed; final int waypoint; final WaypointProgress.Leg leg;
        final Vec3 origin; final LongLeg longLeg;
        /** A recording's turn: a planned walk to its goal pose when {@link #walk}, otherwise its frames. */
        final RecordingRun recording; final boolean walk;
        NavigationPlan plan; CompletableFuture<PathPlanner.Result> result; PathExecution execution; CaptureProvenance capture;
        long nextAttempt, awakeChecked = Long.MIN_VALUE, progressTick;
        /** A recording walk fails as unreachable after this tick; a playing turn has none. */
        long deadline = Long.MAX_VALUE; final long progressStart;
        /** Segment: the path being walked ends part way along a long leg. */
        boolean awake = true, segment;
        /** The style a route or manual leg walks in; a recording's walk and playback have none. */
        final StyledLeg styled;
        Route(PersistedNpcService<P, I>.Value value, WorldPosition goal, MovementProfile.Mode mode, double speed, WaypointProgress.Leg leg, MovementStyle style) {
            this(value, goal, mode, speed, leg, null, false, style);
        }
        Route(PersistedNpcService<P, I>.Value value, WorldPosition goal, MovementProfile.Mode mode, double speed, RecordingRun recording, boolean walk) {
            this(value, goal, mode, speed, null, recording, walk, null);
        }
        private Route(PersistedNpcService<P, I>.Value value, WorldPosition goal, MovementProfile.Mode mode, double speed, WaypointProgress.Leg leg, RecordingRun recording, boolean walk,
                      MovementStyle style) {
            this.recording = recording; this.walk = walk;
            this.value = value; this.goal = goal; this.mode = mode; this.speed = speed; this.leg = leg; this.waypoint = leg == null ? -1 : leg.arrival();
            progressTick = service.tickNumber; progressStart = service.tickNumber;
            origin = value.state.snapshot().position().point();
            styled = recording == null ? StyledLeg.of(value.movement, style, routeProfile(value, mode), value.spec.type()) : null;
            longLeg = new LongLeg(origin, goal.point(), PersistedNpcService.moveWidth(value), styled == null ? PersistedNpcService.moveHeight(value) : styled.height(), value.movement.pathRange());
        }
        MovementProfile profile() { return styled == null ? RouteNavigator.this.profile(value, mode) : styled.profile(); }
        /** The hull height its steps are checked with: lower while the leg may crawl. */
        double height() { return styled == null ? PersistedNpcService.moveHeight(value) : styled.height(); }
        /** Cancels the plan in flight and the walk. */
        void cancel() {
            if (plan != null) plan.cancel();
            if (execution != null) execution.cancel();
        }
        /** Forgets the plan in flight, so the next turn plans again from where the body stands. */
        void dropPlan() {
            if (plan != null) plan.cancel();
            plan = null; result = null;
        }
    }
}
