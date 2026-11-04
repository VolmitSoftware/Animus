package art.arcane.animus.core.combat;

import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** One NPC's combat state machine. Owned by the engine and driven only on its thread. */
final class CombatBrain {
    private static final double RETURN_TOLERANCE = 1.5;
    private static final int STUCK_LIMIT = 3;
    private static final int VISIBILITY_CANDIDATES = 8;
    private static final double FIELD_OF_VIEW_COSINE = Math.cos(Math.toRadians(70));
    private static final long GUARD_INTERVAL = 10;

    private final CombatEngine engine;
    private final CombatWorld world;
    private final UUID npc;
    private final String handle;
    private final CombatSettings settings;
    private final StrikeWindow meleeWindow = new StrikeWindow();
    private final StrikeWindow rangedWindow = new StrikeWindow();
    private CombatState state = CombatState.IDLE;
    private TargetRef target;
    private TargetRef threat;
    private TargetRef guarded;
    private boolean forced;
    private boolean disengaged;
    private boolean retired;
    private boolean moving;
    private boolean alertPending;
    private boolean deathHandled;
    private WorldPosition anchor;
    private WorldPosition deathPlace;
    private SpatialNpcIndex.Cursor npcCursor;
    private List<CombatTarget> lastSeen = List.of();
    private long wake;
    private long seenAt;
    private long reactionUntil;
    private long nextScan;
    private long backoff;
    private long nextPlan;
    private long nextHeal;
    private long invulnerableUntil;
    private long respawnAt = -1;
    private int stuck;

    CombatBrain(CombatEngine engine, CombatWorld world, UUID npc, String handle, CombatSettings settings) {
        this.engine = engine;
        this.world = world;
        this.npc = npc;
        this.handle = handle;
        this.settings = settings;
        guarded = settings.guard().player().map((UUID id) -> new TargetRef(TargetKind.PLAYER, id))
                .or(() -> settings.guard().npc().map((UUID id) -> new TargetRef(TargetKind.NPC, id))).orElse(null);
    }

    UUID npc() { return npc; }
    String handle() { return handle; }
    CombatSettings settings() { return settings; }
    CombatState state() { return state; }
    TargetRef target() { return target; }
    TargetRef guarded() { return guarded; }
    boolean disengaged() { return disengaged; }
    boolean retired() { return retired; }
    long wake() { return wake; }
    void scheduled(long wake) { this.wake = wake; }
    SpatialNpcIndex.Cursor npcCursor() { return npcCursor; }
    void npcCursor(SpatialNpcIndex.Cursor cursor) { npcCursor = cursor; }
    void retire() { retired = true; }
    boolean awaitsTarget() { return target == null && !disengaged && state != CombatState.DEAD; }

    boolean friendly(TargetRef other, Optional<String> otherSquad) {
        if (other.id().equals(npc) || other.equals(guarded)) return true;
        return settings.squad().name().isPresent() && settings.squad().name().equals(otherSquad);
    }

    /** Runs one turn and returns the ticks until the next one. */
    long update(long now) {
        Optional<VirtualNpcState.Snapshot> current = world.npc(npc);
        long idle = engine.budgets().idleSleepTicks();
        if (current.isEmpty()) return idle;
        VirtualNpcState.Snapshot self = current.get();
        if (self.health() == 0) return updateDead(now, self);
        if (!self.active()) return idle;
        heal(now, self);
        if (alertPending && target != null) {
            alertPending = false;
            engine.alertSquad(this, self, target);
        }
        if (threat != null) return flee(now, self);
        if (target != null) {
            long delay = fight(now, self);
            if (target != null) return delay;
        }
        if (state == CombatState.RETURNING) return returning(now, self);
        return search(now, self);
    }

    private long search(long now, VirtualNpcState.Snapshot self) {
        long interval = settings.targeting().scanIntervalTicks();
        if (!disengaged && now >= nextScan) {
            if (!engine.takeScan()) return 1;
            double radius = Math.max(settings.targeting().scanRange(), settings.avoids().empty() ? 0 : settings.avoidance().range());
            lastSeen = engine.scan(this, self.position(), radius);
            if (lastSeen.isEmpty()) backoff = Math.min(Math.max(interval, backoff * 2), Math.max(interval, engine.budgets().idleSleepTicks()));
            else backoff = 0;
            nextScan = now + (backoff > 0 ? backoff : interval);
            Optional<CombatTarget> feared = feared(self);
            if (feared.isPresent()) {
                beginFlight(feared.get().ref(), self);
                return flee(now, self);
            }
            Optional<CombatTarget> chosen = select(self);
            if (chosen.isPresent()) {
                setTarget(chosen.get().ref(), now, self);
                long delay = fight(now, self);
                if (target != null) return delay;
                if (state == CombatState.RETURNING) return returning(now, self);
            }
        }
        long until = disengaged ? engine.budgets().idleSleepTicks() : Math.max(1, nextScan - now);
        if (guarded != null) return Math.min(until, guarding(now, self));
        if (self.health() < settings.defense().maximumHealth() && settings.defense().healRateTicks() > 0)
            until = Math.min(until, Math.max(1, nextHeal - now));
        return until;
    }

    private Optional<CombatTarget> feared(VirtualNpcState.Snapshot self) {
        if (settings.avoids().empty()) return Optional.empty();
        Vec3 point = self.position().point();
        for (CombatTarget candidate : lastSeen) {
            if (settings.avoids().matches(candidate) && !friendly(candidate.ref(), candidate.squad())
                    && candidate.position().point().distance(point) <= settings.avoidance().range()) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /** The nearest allowed candidate. Line-of-sight queries are capped per scan. */
    private Optional<CombatTarget> select(VirtualNpcState.Snapshot self) {
        Vec3 point = self.position().point();
        List<CombatTarget> eligible = new ArrayList<>();
        for (CombatTarget candidate : lastSeen) {
            if (!settings.targets().matches(candidate) || settings.ignores().matches(candidate) || friendly(candidate.ref(), candidate.squad())) continue;
            if (candidate.position().point().distance(point) > settings.targeting().scanRange()) continue;
            if (anchor != null && guarded == null && anchor.point().distance(candidate.position().point()) > settings.pursuit().leashRange()) continue;
            eligible.add(candidate);
        }
        eligible.sort(Comparator.comparingDouble((CombatTarget candidate) -> candidate.position().point().distance(point)));
        int checks = 0;
        for (CombatTarget candidate : eligible) {
            if (checks++ >= VISIBILITY_CANDIDATES) break;
            if (noticeable(self, candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private boolean noticeable(VirtualNpcState.Snapshot self, CombatTarget candidate) {
        CombatSettings.LineOfSight rule = settings.targeting().lineOfSight();
        if (rule == CombatSettings.LineOfSight.IGNORED) return true;
        if (rule == CombatSettings.LineOfSight.FIELD_OF_VIEW) {
            double yaw = Math.toRadians(world.yaw(npc));
            Vec3 toward = candidate.position().point().subtract(self.position().point());
            double length = Math.hypot(toward.x(), toward.z());
            if (length > .0001 && (-Math.sin(yaw) * toward.x() + Math.cos(yaw) * toward.z()) / length < FIELD_OF_VIEW_COSINE) return false;
        }
        return world.lineOfSight(self.position().world(), eye(self), CombatGeometry.center(candidate.bounds())) == CombatWorld.Visibility.CLEAR;
    }

    private long fight(long now, VirtualNpcState.Snapshot self) {
        Optional<CombatTarget> seen = engine.resolve(target);
        if (seen.isEmpty() || seen.get().health() <= 0 || !seen.get().position().world().equals(self.position().world())) return lose(self);
        Vec3 point = self.position().point();
        CombatSettings.Pursuit pursuit = settings.pursuit();
        if (seen.get().position().point().distance(point) > pursuit.giveUpRange()) return lose(self);
        if (anchor != null && guarded == null) {
            double reachMargin = settings.melee().enabled() ? settings.melee().reach() : 0;
            if (anchor.point().distance(point) > pursuit.leashRange()
                    || anchor.point().distance(seen.get().position().point()) > pursuit.leashRange() + reachMargin) return lose(self);
        }
        boolean visible = true;
        if (settings.targeting().lineOfSight() != CombatSettings.LineOfSight.IGNORED) {
            CombatWorld.Visibility visibility = world.lineOfSight(self.position().world(), eye(self), CombatGeometry.center(seen.get().bounds()));
            if (visibility == CombatWorld.Visibility.UNAVAILABLE) return lose(self);
            if (visibility == CombatWorld.Visibility.CLEAR) seenAt = now;
            else {
                visible = false;
                if (now - seenAt > settings.targeting().memoryTicks()) return lose(self);
            }
        }
        if (!settings.targeting().retainTarget() && !forced && !disengaged && now >= nextScan && engine.takeScan()) {
            lastSeen = engine.scan(this, self.position(), settings.targeting().scanRange());
            nextScan = now + settings.targeting().scanIntervalTicks();
            Optional<CombatTarget> nearer = select(self);
            if (nearer.isPresent() && !nearer.get().ref().equals(target)) {
                target = nearer.get().ref();
                seen = nearer;
            }
        }
        return engaged(now, self, seen.get(), visible);
    }

    private long engaged(long now, VirtualNpcState.Snapshot self, CombatTarget foe, boolean visible) {
        CombatSettings.Melee melee = settings.melee();
        CombatSettings.Ranged ranged = settings.ranged();
        boolean ready = now >= reactionUntil;
        double distance = foe.position().point().distance(self.position().point());
        boolean inMelee = melee.enabled() && CombatGeometry.reach(CombatGeometry.center(self.bounds()), foe.bounds()) <= melee.reach();
        boolean rangedUsable = !inMelee && ranged.enabled() && visible && distance <= ranged.range() && distance >= ranged.minimumRange();
        if (inMelee && visible) {
            if (ready && meleeWindow.reserve(now, melee.cooldownTicks())) {
                try {
                    world.face(npc, CombatGeometry.center(foe.bounds()));
                    engine.melee(this, self, foe);
                } finally {
                    meleeWindow.release();
                }
                if (target == null || state == CombatState.DEAD) return 1;
            }
            state = CombatState.ATTACKING;
        } else if (rangedUsable) {
            if (ready && engine.helperCapacity(npc) && clearShot(self, foe) && rangedWindow.reserve(now, ranged.cooldownTicks())) {
                try {
                    world.face(npc, CombatGeometry.center(foe.bounds()));
                    engine.shoot(this, new WorldPosition(self.position().world(), eye(self)), foe);
                } finally {
                    rangedWindow.release();
                }
            }
            state = CombatState.ATTACKING;
        }
        boolean chase;
        if (inMelee && visible) chase = false;
        else if (rangedUsable) chase = settings.pursuit().rangedChase();
        else if (melee.enabled()) chase = settings.pursuit().closeChase();
        else chase = settings.pursuit().rangedChase();
        if (chase) {
            state = CombatState.PURSUING;
            plan(now, self, approach(self, foe), Goal.PURSUE);
        } else if (moving) {
            moving = false;
            world.stop(npc);
        }
        return 1;
    }

    private boolean clearShot(VirtualNpcState.Snapshot self, CombatTarget foe) {
        if (!settings.ranged().safeShot()) return true;
        Vec3 from = eye(self);
        Vec3 to = CombatGeometry.center(foe.bounds());
        for (CombatTarget seen : lastSeen) {
            if (!seen.ref().equals(foe.ref()) && friendly(seen.ref(), seen.squad()) && CombatEngine.crosses(seen.bounds(), from, to)) return false;
        }
        return true;
    }

    private WorldPosition approach(VirtualNpcState.Snapshot self, CombatTarget foe) {
        Vec3 goal = foe.position().point();
        Vec3 toward = self.position().point().subtract(goal);
        double horizontal = Math.hypot(toward.x(), toward.z());
        double offset = Math.min(horizontal, settings.melee().enabled() ? settings.melee().reach() * .6 : 2);
        if (horizontal > .0001) goal = goal.add(new Vec3(toward.x() / horizontal * offset, 0, toward.z() / horizontal * offset));
        return new WorldPosition(self.position().world(), new Vec3(goal.x(), Math.floor(goal.y()), goal.z()));
    }

    private enum Goal { PURSUE, RETURN, GUARD, FLEE }

    private void plan(long now, VirtualNpcState.Snapshot self, WorldPosition goal, Goal purpose) {
        if (now < nextPlan || !engine.takePath()) return;
        nextPlan = now + settings.pursuit().replanTicks();
        CombatWorld.MoveStatus status = world.move(npc, goal, settings.pursuit().speed());
        if (status == CombatWorld.MoveStatus.ACCEPTED) {
            moving = true;
            stuck = 0;
        } else if (status == CombatWorld.MoveStatus.REJECTED && ++stuck >= STUCK_LIMIT) {
            stuck = 0;
            stuck(self, goal, purpose);
        }
    }

    private void stuck(VirtualNpcState.Snapshot self, WorldPosition goal, Goal purpose) {
        if (settings.pursuit().teleportWhenStuck() && purpose != Goal.FLEE && world.teleport(npc, goal)) return;
        if (purpose == Goal.PURSUE) lose(self);
        else if (purpose == Goal.RETURN) finish();
    }

    private long flee(long now, VirtualNpcState.Snapshot self) {
        Optional<CombatTarget> feared = engine.resolve(threat);
        double range = settings.avoidance().range();
        Vec3 point = self.position().point();
        if (feared.isEmpty() || !feared.get().position().world().equals(self.position().world())
                || feared.get().position().point().distance(point) > range + 2) {
            threat = null;
            return lose(self);
        }
        state = CombatState.AVOIDING;
        Optional<WorldPosition> refuge = settings.avoidance().returnPoint().flatMap(world::resolve);
        WorldPosition goal;
        if (refuge.isPresent()) goal = refuge.get();
        else {
            Vec3 away = point.subtract(feared.get().position().point());
            double horizontal = Math.hypot(away.x(), away.z());
            Vec3 unit = horizontal > .0001 ? new Vec3(away.x() / horizontal, 0, away.z() / horizontal) : new Vec3(1, 0, 0);
            Vec3 far = point.add(unit.multiply(range + 2));
            goal = new WorldPosition(self.position().world(), new Vec3(far.x(), Math.floor(far.y()), far.z()));
        }
        plan(now, self, goal, Goal.FLEE);
        return Math.min(5, settings.pursuit().replanTicks());
    }

    private void beginFlight(TargetRef from, VirtualNpcState.Snapshot self) {
        threat = from;
        target = null;
        forced = false;
        if (anchor == null) anchor = self.position();
    }

    private long returning(long now, VirtualNpcState.Snapshot self) {
        if (anchor == null || !anchor.world().equals(self.position().world())
                || anchor.point().distance(self.position().point()) <= RETURN_TOLERANCE) {
            finish();
            return 1;
        }
        plan(now, self, anchor, Goal.RETURN);
        return Math.min(5, settings.pursuit().replanTicks());
    }

    /** The fight ended: go back to the post when configured, otherwise hand back to any patrol now. */
    private long lose(VirtualNpcState.Snapshot self) {
        target = null;
        forced = false;
        alertPending = false;
        if (guarded == null && settings.pursuit().returnToPost() && anchor != null && anchor.world().equals(self.position().world())
                && anchor.point().distance(self.position().point()) > RETURN_TOLERANCE) {
            state = CombatState.RETURNING;
            nextPlan = 0;
            stuck = 0;
        } else {
            finish();
        }
        return 1;
    }

    private void finish() {
        boolean wasEngaged = anchor != null || moving || state != CombatState.IDLE;
        state = CombatState.IDLE;
        anchor = null;
        moving = false;
        stuck = 0;
        if (wasEngaged) world.release(npc);
    }

    private long guarding(long now, VirtualNpcState.Snapshot self) {
        Optional<CombatTarget> charge = engine.resolve(guarded);
        if (charge.isEmpty() || !charge.get().position().world().equals(self.position().world())) {
            if (state == CombatState.GUARDING) finish();
            return GUARD_INTERVAL;
        }
        state = CombatState.GUARDING;
        CombatSettings.Guard guard = settings.guard();
        if (charge.get().position().point().distance(self.position().point()) > guard.followDistance() && now >= nextPlan) {
            Vec3 center = charge.get().position().point();
            double angle = engine.random().nextDouble() * Math.PI * 2;
            double radius = engine.random().nextDouble() * guard.pointRange();
            Vec3 point = new Vec3(center.x() + Math.cos(angle) * radius, Math.floor(center.y()), center.z() + Math.sin(angle) * radius);
            plan(now, self, new WorldPosition(self.position().world(), point), Goal.GUARD);
        }
        return GUARD_INTERVAL;
    }

    private void setTarget(TargetRef ref, long now, VirtualNpcState.Snapshot self) {
        target = ref;
        seenAt = now;
        reactionUntil = now + settings.targeting().reactionTicks();
        alertPending = true;
        stuck = 0;
        nextPlan = 0;
        if (anchor == null) anchor = self.position();
        if (settings.squad().name().isPresent()) {
            alertPending = false;
            engine.alertSquad(this, self, ref);
        }
    }

    private void adopt(TargetRef ref, boolean explicit) {
        Optional<VirtualNpcState.Snapshot> self = world.npc(npc);
        if (self.isEmpty() || !self.get().active()) return;
        long now = engine.now();
        target = ref;
        forced = explicit;
        threat = null;
        seenAt = now;
        reactionUntil = now + settings.targeting().reactionTicks();
        nextPlan = 0;
        stuck = 0;
        if (anchor == null) anchor = self.get().position();
    }

    void force(TargetRef ref) {
        disengaged = false;
        adopt(ref, true);
        alertPending = settings.squad().name().isPresent();
    }

    /** A squad mate or guarded participant named this target. Only an NPC with no target takes it. */
    void alerted(TargetRef ref) {
        if (!awaitsTarget() || ref.id().equals(npc)) return;
        Optional<CombatTarget> seen = engine.resolve(ref);
        if (seen.isEmpty() || friendly(ref, seen.get().squad())) return;
        adopt(ref, false);
        engine.wake(this);
    }

    /** This NPC took damage. Wakes it, and it may fight back or flee. */
    void attacked(TargetRef attacker) {
        engine.wake(this);
        if (disengaged || attacker.id().equals(npc)) return;
        Optional<CombatTarget> seen = engine.resolve(attacker);
        if (seen.isEmpty() || friendly(attacker, seen.get().squad())) return;
        Optional<VirtualNpcState.Snapshot> self = world.npc(npc);
        if (self.isEmpty() || !self.get().active()) return;
        if (settings.avoidance().fleeWhenAttacked()) {
            beginFlight(attacker, self.get());
            return;
        }
        if (settings.targeting().fightBack() && target == null) {
            adopt(attacker, false);
            alertPending = settings.squad().name().isPresent();
        }
    }

    /** The damage that remains after this NPC's defense, or 0. Starts invulnerability frames when any remains. */
    double mitigate(TargetRef attacker, WorldPosition from, double amount, Optional<CombatTarget> attackerView) {
        CombatSettings.Defense defense = settings.defense();
        long now = engine.now();
        if (defense.invincible() || now < invulnerableUntil || state == CombatState.DEAD) return 0;
        Optional<VirtualNpcState.Snapshot> self = world.npc(npc);
        if (self.isEmpty()) return 0;
        if (defense.protectFromRange() > 0 && (!from.world().equals(self.get().position().world())
                || from.point().distance(self.get().position().point()) > defense.protectFromRange())) return 0;
        if (defense.protectedFromIgnored() && attackerView.isPresent() && settings.ignores().matches(attackerView.get())) return 0;
        double reduced = amount * (1 - defense.armor());
        if (reduced <= 0) return 0;
        invulnerableUntil = now + defense.invulnerableTicks();
        return reduced;
    }

    private void heal(long now, VirtualNpcState.Snapshot self) {
        int rate = settings.defense().healRateTicks();
        if (rate <= 0) return;
        if (self.health() >= settings.defense().maximumHealth()) {
            nextHeal = 0;
            return;
        }
        if (nextHeal == 0) nextHeal = now + rate;
        if (now >= nextHeal) {
            world.heal(npc, 1);
            nextHeal = now + rate;
        }
    }

    /** Processes a death exactly once: helpers, drops, experience and the respawn timer. */
    void die(WorldPosition place) {
        if (deathHandled) return;
        deathHandled = true;
        deathPlace = place;
        state = CombatState.DEAD;
        target = null;
        threat = null;
        forced = false;
        moving = false;
        anchor = null;
        engine.removeHelpers(npc);
        CombatSettings.Death death = settings.death();
        List<CombatWorld.Drop> drops = new ArrayList<>();
        for (CombatSettings.DropRule rule : death.drops()) {
            if (engine.random().nextDouble() < rule.chance()) drops.add(new CombatWorld.Drop(rule.item(), rule.amount()));
        }
        if (!drops.isEmpty() || death.experience() > 0) world.drop(place, drops, death.experience());
        int delay = settings.respawn().delayTicks();
        respawnAt = delay < 0 ? -1 : engine.now() + delay;
        engine.wake(this);
    }

    private long updateDead(long now, VirtualNpcState.Snapshot self) {
        if (!deathHandled) die(self.position());
        long idle = engine.budgets().idleSleepTicks();
        if (respawnAt < 0) return idle;
        if (now < respawnAt) return respawnAt - now;
        List<CombatSettings.Location> locations = settings.respawn().locations();
        Optional<WorldPosition> at = locations.isEmpty() ? Optional.of(deathPlace)
                : world.resolve(locations.get(engine.random().nextInt(locations.size())));
        if (at.isEmpty() || !world.revive(npc, at.get())) {
            respawnAt = now + 20;
            return 20;
        }
        deathHandled = false;
        respawnAt = -1;
        state = CombatState.IDLE;
        nextScan = now;
        backoff = 0;
        nextHeal = 0;
        return 1;
    }

    void engage() {
        disengaged = false;
        nextScan = 0;
    }

    void disengage() {
        disengaged = true;
        threat = null;
        Optional<VirtualNpcState.Snapshot> self = world.npc(npc);
        if (self.isPresent()) lose(self.get());
        else target = null;
    }

    void release() {
        threat = null;
        Optional<VirtualNpcState.Snapshot> self = world.npc(npc);
        if (self.isPresent()) lose(self.get());
        else target = null;
        nextScan = engine.now() + settings.targeting().scanIntervalTicks();
    }

    void guard(TargetRef charge) {
        guarded = charge;
        if (charge == null && state == CombatState.GUARDING) finish();
        nextPlan = 0;
    }

    private static Vec3 eye(VirtualNpcState.Snapshot self) {
        Vec3 min = self.bounds().min();
        Vec3 max = self.bounds().max();
        return new Vec3((min.x() + max.x()) / 2, min.y() + (max.y() - min.y()) * .85, (min.z() + max.z()) / 2);
    }
}
