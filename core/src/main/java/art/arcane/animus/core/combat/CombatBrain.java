package art.arcane.animus.core.combat;

import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** One NPC's combat state machine. Owned by the engine and driven only on its thread. */
final class CombatBrain {
    /** How close across, and up or down, a returning NPC must stand to its post to be on it. */
    private static final double ON_POST = .3, ON_POST_HEIGHT = .6, ARRIVED = .001;
    /**
     * A returning NPC whose walk stopped within this of its post, as a path that ends beside a goal it cannot reach does,
     * takes a short final step onto it; a refused step is a nudge instead.
     */
    private static final double FINAL_STEP = 2.5, FINAL_STEP_HEIGHT = 2;
    /** A return walk that has not moved this far for {@link #FINAL_STEP_TICKS} has ended; for {@link #RETURN_STALL_TICKS}, it is planned again. */
    private static final double STALLED = .05;
    private static final long FINAL_STEP_TICKS = 10, RETURN_STALL_TICKS = 100;
    private static final int STUCK_LIMIT = 3;
    private static final int VISIBILITY_CANDIDATES = 8;
    private static final double FIELD_OF_VIEW_COSINE = Math.cos(Math.toRadians(70));
    private static final long GUARD_INTERVAL = GuardFollow.MINIMUM_REPLAN_TICKS;
    private static final double HURRY_SPEED = 1.4;
    private static final long REGION_INTERVAL = 10;
    /** A guard that should be following but has not moved a block for this long catches up or replans. */
    static final long STALL_TICKS = 60;
    private static final double PROGRESS = 1;
    private static final int GREETING_MEMORY = 256;
    /** How long a target that protection or a cancelled hit refused is passed over. */
    static final long REFUSAL_TICKS = 100;
    /** How often a chase asks protection again whether its target may still be hit. */
    private static final long PROTECTION_INTERVAL = 10;
    private static final int REFUSAL_MEMORY = 32;

    private final CombatEngine engine;
    private final CombatWorld world;
    private final UUID npc;
    private final String handle;
    private final CombatSettings settings;
    private final CombatLoadout loadout;
    private final TargetRules.Context context;
    private final double meleeDamage;
    private final double rangedDamage;
    private final double armor;
    private final StrikeWindow meleeWindow = new StrikeWindow();
    private final StrikeWindow rangedWindow = new StrikeWindow();
    private final Set<UUID> greeted = new HashSet<>();
    private final Map<UUID, Long> lastGreeting = new HashMap<>();
    /** Targets left alone until the tick given, because protection or a cancelled hit refused them. */
    private final Map<TargetRef, Long> refusals = new HashMap<>();
    private final GuardFollow follow = new GuardFollow();
    private WorldPosition progressFrom;
    private long progressAt;
    private long lastGuarded = Long.MIN_VALUE / 2;
    private boolean stallReported;
    /** Whether the NPC holds one of the engine's active slots, waits for one, or neither. */
    enum Slot { NONE, WAITING, ACTIVE }
    /** Extra blocks beyond the scan radius in which a player keeps the NPC awake, about one sleep of running. */
    private static final double WAKE_MARGIN = 16;
    private Slot slot = Slot.NONE;
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
    private boolean regionReported;
    /** Whether viewers see the bow drawn, and whether this turn's fight wants it drawn. */
    private boolean drawn, drawThisTurn;
    /** Where this fight's leash runs from and where it returns: the post, or where a patrolling NPC left its route. */
    private WorldPosition anchor;
    /** Where the returning NPC last moved and when, to tell a walk that ended from one still under way. */
    private WorldPosition returnFrom;
    private long returnMovedAt;
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
    private long nextRegionCheck;
    private long nextProtectionCheck;
    private long invulnerableUntil;
    private long respawnAt = -1;
    /** The next respawn point for {@code in_order}; runtime only, so a restart starts from the first. */
    private int nextPoint;
    /** When a permadeath is recorded: after the body's fall has played, since recording it despawns the NPC. -1 for none. */
    private long permadeathAt = -1;
    /** Ticks a dead body lies before a permadeath is recorded, as long as the death animation. */
    static final int DEATH_ANIMATION_TICKS = 20;
    private int stuck;
    private int ammunition;
    private final int fullAmmunition;

    CombatBrain(CombatEngine engine, CombatWorld world, UUID npc, String handle, CombatSettings settings, CombatLoadout loadout) {
        this.engine = engine;
        this.world = world;
        this.npc = npc;
        this.handle = handle;
        this.settings = settings;
        this.loadout = loadout;
        context = new TargetRules.Context() {
            @Override public Optional<UUID> owner() { return loadout.owner(); }
            @Override public boolean permitted(TargetRef player, String node) { return world.permitted(player, node); }
        };
        meleeDamage = EquipmentStats.melee(settings.melee(), loadout);
        rangedDamage = EquipmentStats.ranged(settings.ranged(), settings.melee(), loadout);
        armor = EquipmentStats.armor(settings.defense(), loadout);
        fullAmmunition = settings.ranged().useAmmo() && !settings.ranged().weapon().ammunition().isEmpty() ? loadout.count(settings.ranged().weapon().ammunition()) : -1;
        ammunition = fullAmmunition;
        guarded = settings.guard().player().map((UUID id) -> new TargetRef(TargetKind.PLAYER, id))
                .or(() -> settings.guard().npc().map((UUID id) -> new TargetRef(TargetKind.NPC, id))).orElse(null);
    }

    UUID npc() { return npc; }
    String handle() { return handle; }
    CombatSettings settings() { return settings; }
    CombatLoadout loadout() { return loadout; }
    TargetRules.Context context() { return context; }
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
    boolean permadeathPending() { return permadeathAt >= 0; }
    Slot slot() { return slot; }
    void slot(Slot slot) { this.slot = slot; }
    boolean awaitsTarget() { return target == null && !disengaged && state != CombatState.DEAD; }
    double meleeDamage() { return meleeDamage; }
    double rangedDamage() { return rangedDamage; }
    /** Shots left when ammunition is counted, or -1 when it is not. */
    int ammunition() { return ammunition; }
    /** Whether fight or chat rules make this NPC react to events nearby. */
    boolean watches() {
        return !settings.targets().events().empty() || !settings.avoids().events().empty() || !settings.targets().chat().isEmpty() || !settings.avoids().chat().isEmpty();
    }

    boolean friendly(TargetRef other, Optional<String> otherSquad) {
        if (other.id().equals(npc) || other.equals(guarded)) return true;
        return settings.squad().name().isPresent() && settings.squad().name().equals(otherSquad);
    }

    private double scanRange() { return Math.min(settings.targeting().scanRange(), engine.budgets().maximumScanRange()); }
    private double speed() { return Math.min(settings.pursuit().speed(), engine.budgets().maximumSpeed()); }

    /** Runs one turn and returns the ticks until the next one. */
    long update(long now) {
        Optional<VirtualNpcState.Snapshot> current = world.npc(npc);
        long idle = engine.budgets().idleSleepTicks();
        if (current.isEmpty()) return idle;
        VirtualNpcState.Snapshot self = current.get();
        drawThisTurn = false;
        if (self.health() == 0) { showDraw(); engine.vacate(this); return updateDead(now, self); }
        if (!self.active()) { showDraw(); engine.vacate(this); return idle; }
        heal(now, self);
        boolean engaged = engaged();
        if (!engaged && (disengaged || settings.passive() || !engine.playersNear(self.position(), searchRadius() + WAKE_MARGIN))) {
            // Nothing to fight and nobody to see it: sleep without scanning and without a slot. A passive NPC never scans.
            engine.vacate(this);
            if (settings.passive()) return healing(self) ? Math.max(1, Math.min(idle, nextHeal - now)) : idle;
            if (!disengaged) return idle;
        } else if (!engine.claim(this)) return idle;
        long next = turn(now, self);
        showDraw();
        if (!engaged()) engine.yieldTurn(this);
        return next;
    }

    /** Raises or lowers the bow when this turn's fight changed whether it is drawn. */
    private void showDraw() {
        if (drawn == drawThisTurn) return;
        drawn = drawThisTurn;
        world.drawing(npc, drawn);
    }

    private boolean engaged() { return target != null || threat != null || state == CombatState.RETURNING; }
    private boolean healing(VirtualNpcState.Snapshot self) { return self.health() < settings.defense().maximumHealth() && settings.defense().healRateTicks() > 0; }
    private double searchRadius() {
        double radius = Math.max(scanRange(), settings.avoids().empty() ? 0 : settings.avoidance().range());
        return settings.greetings().enabled() ? Math.max(radius, settings.greetings().range() + 1) : radius;
    }

    private long turn(long now, VirtualNpcState.Snapshot self) {
        if (alertPending && target != null) {
            alertPending = false;
            engine.alertSquad(this, self, target);
        }
        if (threat != null) return flee(now, self);
        if (target != null && chargeLeft(self)) lose(self);
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
            lastSeen = engine.scan(this, self.position(), searchRadius());
            if (lastSeen.isEmpty()) backoff = Math.min(Math.max(interval, backoff * 2), Math.max(interval, engine.budgets().idleSleepTicks()));
            else backoff = 0;
            nextScan = now + (backoff > 0 ? backoff : interval);
            greet(now, self);
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
        if (healing(self)) until = Math.min(until, Math.max(1, nextHeal - now));
        return until;
    }

    /** Says the greeting or warning to players who came within range since the last scan, at most once per cooldown each. */
    private void greet(long now, VirtualNpcState.Snapshot self) {
        CombatSettings.Greetings greetings = settings.greetings();
        if (!greetings.enabled()) return;
        Vec3 point = self.position().point();
        Set<UUID> present = new HashSet<>();
        int checks = 0;
        for (CombatTarget seen : lastSeen) {
            if (seen.ref().kind() != TargetKind.PLAYER) continue;
            UUID player = seen.ref().id();
            double distance = seen.position().point().distance(point);
            if (distance >= greetings.range() + 1) continue;
            if (greeted.contains(player)) {
                present.add(player);
                continue;
            }
            if (distance >= greetings.range() || checks++ >= VISIBILITY_CANDIDATES || !noticeable(self, seen)) continue;
            present.add(player);
            Long last = lastGreeting.get(player);
            if (last != null && now - last < greetings.cooldownTicks()) continue;
            lastGreeting.put(player, now);
            boolean hostile = settings.targets().matches(seen, context) && !settings.ignores().matches(seen, context) && !friendly(seen.ref(), seen.squad());
            Optional<String> line = hostile ? greetings.hostile() : greetings.friendly();
            if (line.isPresent()) world.tell(player, engine.speaker(this), line.get());
        }
        greeted.clear();
        greeted.addAll(present);
        if (lastGreeting.size() > GREETING_MEMORY) lastGreeting.values().removeIf((Long at) -> now - at >= greetings.cooldownTicks());
    }

    private Optional<CombatTarget> feared(VirtualNpcState.Snapshot self) {
        if (settings.avoids().empty()) return Optional.empty();
        Vec3 point = self.position().point();
        Set<TargetRef> grouped = together(settings.avoids(), self, settings.avoidance().range());
        for (CombatTarget candidate : lastSeen) {
            if ((settings.avoids().matches(candidate, context) || grouped.contains(candidate.ref())) && !friendly(candidate.ref(), candidate.squad())
                    && candidate.position().point().distance(point) <= settings.avoidance().range()) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /**
     * Candidates that complete a {@code together} group: each rule set of the group matched by a different candidate in
     * range at this scan. Every member of a completed group is eligible.
     */
    private Set<TargetRef> together(TargetRules rules, VirtualNpcState.Snapshot self, double range) {
        if (rules.together().isEmpty()) return Set.of();
        Vec3 point = self.position().point();
        List<CombatTarget> pool = new ArrayList<>();
        for (CombatTarget candidate : lastSeen) {
            if (candidate.position().point().distance(point) <= range && !settings.ignores().matches(candidate, context) && !friendly(candidate.ref(), candidate.squad()))
                pool.add(candidate);
        }
        pool.sort(Comparator.comparingDouble((CombatTarget candidate) -> candidate.position().point().distance(point)));
        Set<TargetRef> result = new HashSet<>();
        for (List<TargetRules> group : rules.together()) {
            Set<TargetRef> used = new HashSet<>();
            for (TargetRules part : group) {
                for (CombatTarget candidate : pool) {
                    if (!used.contains(candidate.ref()) && part.matchesAll(candidate, context)) {
                        used.add(candidate.ref());
                        break;
                    }
                }
            }
            if (used.size() == group.size()) result.addAll(used);
        }
        return result;
    }

    /** The nearest allowed candidate. Line-of-sight and region queries are capped per scan. */
    private Optional<CombatTarget> select(VirtualNpcState.Snapshot self) {
        Vec3 point = self.position().point();
        boolean helpful = settings.ranged().enabled() && settings.ranged().helpful();
        Set<TargetRef> grouped = together(settings.targets(), self, scanRange());
        List<CombatTarget> eligible = new ArrayList<>();
        for (CombatTarget candidate : lastSeen) {
            if (!(settings.targets().matches(candidate, context) || grouped.contains(candidate.ref())) || settings.ignores().matches(candidate, context)) continue;
            if (candidate.ref().id().equals(npc) || !helpful && friendly(candidate.ref(), candidate.squad()) || refused(candidate.ref())) continue;
            if (candidate.position().point().distance(point) > scanRange()) continue;
            if (anchor != null && guarded == null && anchor.point().distance(candidate.position().point()) > settings.pursuit().leashRange()) continue;
            eligible.add(candidate);
        }
        eligible.sort(Comparator.comparingDouble((CombatTarget candidate) -> candidate.position().point().distance(point)));
        int checks = 0;
        for (CombatTarget candidate : eligible) {
            if (checks++ >= VISIBILITY_CANDIDATES) break;
            if (!insideRegion(candidate.position()) || !noticeable(self, candidate)) continue;
            if (helpful || engine.permits(this, self, candidate)) return Optional.of(candidate);
            remember(candidate.ref());
        }
        return Optional.empty();
    }

    /** Whether the position satisfies the pursuit region. Without one, or where regions are unavailable, every position does. */
    private boolean insideRegion(WorldPosition position) {
        Optional<String> region = settings.pursuit().region();
        if (region.isEmpty()) return true;
        Optional<Boolean> inside = engine.inRegion(region.get(), position);
        if (inside.isEmpty()) {
            if (!regionReported) {
                regionReported = true;
                engine.regionUnavailable(this, region.get());
            }
            return true;
        }
        return inside.get();
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
        if (pursuit.region().isPresent() && now >= nextRegionCheck) {
            nextRegionCheck = now + REGION_INTERVAL;
            if (!insideRegion(seen.get().position())) return lose(self);
        }
        if (now >= nextProtectionCheck && !(settings.ranged().enabled() && settings.ranged().helpful())) {
            nextProtectionCheck = now + PROTECTION_INTERVAL;
            if (!engine.permits(this, self, seen.get())) {
                refuse(target);
                return 1;
            }
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
            lastSeen = engine.scan(this, self.position(), Math.max(scanRange(), settings.greetings().enabled() ? settings.greetings().range() + 1 : 0));
            nextScan = now + settings.targeting().scanIntervalTicks();
            greet(now, self);
            Optional<CombatTarget> nearer = select(self);
            if (nearer.isPresent() && !nearer.get().ref().equals(target)) {
                target = nearer.get().ref();
                seen = nearer;
                nextProtectionCheck = now + PROTECTION_INTERVAL;
            }
        }
        return engaged(now, self, seen.get(), visible);
    }

    private long engaged(long now, VirtualNpcState.Snapshot self, CombatTarget foe, boolean visible) {
        CombatSettings.Melee melee = settings.melee();
        CombatSettings.Ranged ranged = settings.ranged();
        boolean ready = now >= reactionUntil;
        double distance = foe.position().point().distance(self.position().point());
        boolean meleeAllowed = melee.enabled() && !(ranged.enabled() && ranged.helpful());
        boolean inMelee = meleeAllowed && CombatGeometry.reach(CombatGeometry.center(self.bounds()), foe.bounds()) <= melee.reach();
        boolean rangedUsable = !inMelee && ranged.enabled() && ammunition != 0 && visible && distance <= ranged.range() && distance >= ranged.minimumRange();
        if (inMelee && visible) {
            if (ready && meleeWindow.reserve(now, melee.cooldownTicks())) {
                CombatWorld.DamageOutcome outcome;
                try {
                    world.face(npc, CombatGeometry.center(foe.bounds()));
                    world.swing(npc);
                    outcome = engine.melee(this, self, foe);
                } finally {
                    meleeWindow.release();
                }
                if (refusal(outcome.status())) refuse(foe.ref());
                if (target == null || state == CombatState.DEAD) return 1;
            }
            state = CombatState.ATTACKING;
        } else if (rangedUsable) {
            drawThisTurn = ranged.weapon().drawn();
            if (ready && engine.helperCapacity(npc) && clearShot(self, foe) && rangedWindow.reserve(now, ranged.cooldownTicks())) {
                try {
                    world.face(npc, CombatGeometry.center(foe.bounds()));
                    if (engine.shoot(this, new WorldPosition(self.position().world(), eye(self)), foe)) {
                        if (!ranged.weapon().drawn()) world.swing(npc);
                        if (ammunition > 0) ammunition--;
                    }
                } finally {
                    rangedWindow.release();
                }
            }
            state = CombatState.ATTACKING;
        }
        boolean chase;
        if (inMelee && visible) chase = false;
        else if (rangedUsable) chase = settings.pursuit().rangedChase();
        else if (meleeAllowed) chase = settings.pursuit().closeChase();
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

    private enum Goal { PURSUE, RETURN, FLEE }

    /** True when a new walk toward the goal was accepted. */
    private boolean plan(long now, VirtualNpcState.Snapshot self, WorldPosition goal, Goal purpose) {
        if (now < nextPlan || !engine.takePath()) return false;
        nextPlan = now + settings.pursuit().replanTicks();
        CombatWorld.MoveStatus status = world.move(npc, goal, speed());
        if (status == CombatWorld.MoveStatus.ACCEPTED) {
            moving = true;
            stuck = 0;
            return true;
        }
        if (status == CombatWorld.MoveStatus.REJECTED && ++stuck >= STUCK_LIMIT) {
            stuck = 0;
            stuck(self, goal, purpose);
        }
        return false;
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
        if (anchor == null) anchor = anchorFor(self.position());
    }

    /** The post when the NPC has one in this world, never where a fight last left it; otherwise where it stands now. */
    private WorldPosition anchorFor(WorldPosition here) {
        return world.post(npc).filter((WorldPosition post) -> post.world().equals(here.world())).orElse(here);
    }

    private static boolean on(WorldPosition anchor, WorldPosition here) {
        Vec3 a = anchor.point(), b = here.point();
        return Math.hypot(a.x() - b.x(), a.z() - b.z()) <= ON_POST && Math.abs(a.y() - b.y()) <= ON_POST_HEIGHT;
    }

    private long returning(long now, VirtualNpcState.Snapshot self) {
        WorldPosition here = self.position();
        if (anchor == null || !anchor.world().equals(here.world())) {
            finish();
            return 1;
        }
        if (on(anchor, here)) {
            // Close enough to count as there; the last fraction is a step onto the post itself when the ground allows it.
            if (here.point().distance(anchor.point()) > ARRIVED) world.step(npc, anchor);
            arrive();
            return 1;
        }
        if (returnFrom == null || !returnFrom.world().equals(here.world()) || returnFrom.point().distance(here.point()) >= STALLED) {
            returnFrom = here;
            returnMovedAt = now;
        }
        long still = now - returnMovedAt;
        Vec3 offset = anchor.point().subtract(here.point());
        if (still >= FINAL_STEP_TICKS && Math.hypot(offset.x(), offset.z()) <= FINAL_STEP && Math.abs(offset.y()) <= FINAL_STEP_HEIGHT) {
            // The walk ended beside the post, as a path that cannot reach it does: step the rest of the way, or be nudged.
            if (moving) { world.stop(npc); moving = false; }
            if (world.step(npc, anchor) || world.teleport(npc, anchor)) {
                arrive();
                return 1;
            }
        }
        // One walk to the post at a time: asking again restarts its planning, so only a walk that ended or stalled is replaced.
        if ((!moving || still >= RETURN_STALL_TICKS) && plan(now, self, anchor, Goal.RETURN)) returnMovedAt = now;
        return Math.min(5, settings.pursuit().replanTicks());
    }

    /** On the post again: the fight is over and the NPC faces and stands as its definition says. */
    private void arrive() {
        boolean posted = world.post(npc).isPresent();
        finish();
        if (posted) world.restorePose(npc);
    }

    /** The fight ended: go back to the post when configured, otherwise hand back to any patrol now. */
    private long lose(VirtualNpcState.Snapshot self) {
        target = null;
        forced = false;
        alertPending = false;
        if (guarded == null && settings.pursuit().returnToPost() && anchor != null && anchor.world().equals(self.position().world())
                && !on(anchor, self.position())) {
            state = CombatState.RETURNING;
            nextPlan = 0;
            stuck = 0;
            returnFrom = null;
            // The chase walk is not a walk to the post; the next turn replaces it.
            moving = false;
        } else if (guarded == null && settings.pursuit().returnToPost()) {
            arrive();
        } else {
            finish();
        }
        return 1;
    }

    private void finish() {
        boolean wasEngaged = anchor != null || moving || state != CombatState.IDLE;
        state = CombatState.IDLE;
        // The next fight anchors at the post again, never where this one stopped.
        anchor = null;
        returnFrom = null;
        moving = false;
        stuck = 0;
        if (wasEngaged) world.release(npc);
    }

    private long guarding(long now, VirtualNpcState.Snapshot self) {
        Optional<CombatTarget> charge = engine.resolve(guarded);
        if (charge.isEmpty()) {
            if (state == CombatState.GUARDING) finish();
            follow.reset();
            progressFrom = null;
            return GUARD_INTERVAL;
        }
        state = CombatState.GUARDING;
        WorldPosition at = charge.get().position();
        // A fight, a death or an absent charge paused following: measure progress afresh.
        if (progressFrom == null || now - lastGuarded > GUARD_INTERVAL * 2) mark(now, self.position());
        lastGuarded = now;
        GuardFollow.Step step = follow.next(now, self.position(), at, settings.guard(), settings.pursuit().replanTicks());
        switch (step.action()) {
            case CATCH_UP -> {
                if (catchUp(now, step, at)) return GUARD_INTERVAL;
                if (self.position().world().equals(at.world())) walk(now, step, at);
            }
            case WALK -> walk(now, step, at);
            case HOLD -> {}
        }
        watch(now, at, step);
        return GUARD_INTERVAL;
    }

    private boolean catchUp(long now, GuardFollow.Step step, WorldPosition charge) {
        world.stop(npc);
        moving = false;
        for (Vec3 spot : GuardFollow.catchUpSpots(step.goal(), charge)) {
            if (world.teleport(npc, new WorldPosition(charge.world(), spot))) {
                follow.planned(now, charge);
                world.npc(npc).ifPresent((VirtualNpcState.Snapshot moved) -> mark(now, moved.position()));
                return true;
            }
        }
        return false;
    }

    /**
     * The watchdog: a guard that should be following but has not moved a block in {@link #STALL_TICKS} catches up by
     * teleport when it may, otherwise drops its plan so the next turn replans. The first stall since walking last
     * worked is logged with the movement's own reason.
     */
    private void watch(long now, WorldPosition charge, GuardFollow.Step step) {
        Optional<VirtualNpcState.Snapshot> current = world.npc(npc);
        if (current.isEmpty()) return;
        WorldPosition here = current.get().position();
        boolean sameWorld = here.world().equals(charge.world());
        double distance = sameWorld ? here.point().distance(charge.point()) : Double.POSITIVE_INFINITY;
        boolean walked = here.world().equals(progressFrom.world()) && here.point().distance(progressFrom.point()) >= PROGRESS;
        if (distance <= settings.guard().followDistance() + PROGRESS || walked) {
            if (walked) stallReported = false;
            mark(now, here);
            return;
        }
        if (now - progressAt < STALL_TICKS) return;
        boolean teleports = settings.guard().catchUpDistance() > 0;
        if (!stallReported) {
            stallReported = true;
            engine.guardStalled(this, now - progressAt, sameWorld ? String.format(Locale.ROOT, "%.1f", distance) : "another world",
                    world.movement(npc), teleports ? "catching up" : "replanning");
        }
        mark(now, here);
        if (teleports && catchUp(now, step, charge)) return;
        follow.reset();
        world.stop(npc);
        moving = false;
    }

    private void mark(long now, WorldPosition position) {
        progressFrom = position;
        progressAt = now;
    }

    private void walk(long now, GuardFollow.Step step, WorldPosition charge) {
        if (!engine.takePath()) return;
        double speed = step.hurry() ? Math.min(engine.budgets().maximumSpeed(), speed() * HURRY_SPEED) : speed();
        CombatWorld.MoveStatus status = world.move(npc, step.goal(), speed);
        if (status == CombatWorld.MoveStatus.ACCEPTED) {
            moving = true;
            stuck = 0;
            follow.planned(now, charge);
        }
    }

    /** A guard stops fighting when its charge left it behind, so it can catch up. */
    private boolean chargeLeft(VirtualNpcState.Snapshot self) {
        if (guarded == null || settings.guard().catchUpDistance() <= 0) return false;
        Optional<CombatTarget> charge = engine.resolve(guarded);
        return charge.isPresent() && (!charge.get().position().world().equals(self.position().world())
                || charge.get().position().point().distance(self.position().point()) > settings.guard().catchUpDistance());
    }

    private void setTarget(TargetRef ref, long now, VirtualNpcState.Snapshot self) {
        target = ref;
        seenAt = now;
        reactionUntil = now + settings.targeting().reactionTicks();
        nextProtectionCheck = now + PROTECTION_INTERVAL;
        alertPending = true;
        stuck = 0;
        nextPlan = 0;
        if (anchor == null) anchor = anchorFor(self.position());
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
        nextProtectionCheck = now;
        nextPlan = 0;
        stuck = 0;
        if (anchor == null) anchor = anchorFor(self.get().position());
    }

    void force(TargetRef ref) {
        disengaged = false;
        refusals.remove(ref);
        adopt(ref, true);
        alertPending = settings.squad().name().isPresent();
    }

    /** A squad mate or guarded participant named this target. Only an NPC with no target takes it. */
    void alerted(TargetRef ref) {
        if (!awaitsTarget() || ref.id().equals(npc) || refused(ref)) return;
        Optional<CombatTarget> seen = engine.resolve(ref);
        if (seen.isEmpty() || friendly(ref, seen.get().squad())) return;
        adopt(ref, false);
        engine.wake(this);
    }

    /**
     * A fight or a chat message nearby. The attacker or speaker becomes a target when the target rules ask for it, or a
     * threat when the avoid rules do, provided this NPC could notice it. An NPC already fighting keeps its target.
     */
    void witnessed(CombatTarget subject, boolean asTarget, boolean asThreat) {
        if (disengaged || state == CombatState.DEAD || subject.ref().id().equals(npc) || friendly(subject.ref(), subject.squad())) return;
        if (settings.ignores().matches(subject, context)) return;
        Optional<VirtualNpcState.Snapshot> current = world.npc(npc);
        if (current.isEmpty() || !current.get().active()) return;
        VirtualNpcState.Snapshot self = current.get();
        if (!subject.position().world().equals(self.position().world())) return;
        double distance = subject.position().point().distance(self.position().point());
        boolean fear = asThreat && threat == null && distance <= settings.avoidance().range();
        boolean attack = asTarget && awaitsTarget() && threat == null && distance <= scanRange() && !refused(subject.ref());
        if (!fear && !attack || !engine.takeSight() || !noticeable(self, subject)) return;
        if (fear) beginFlight(subject.ref(), self);
        else {
            adopt(subject.ref(), false);
            alertPending = settings.squad().name().isPresent();
        }
        engine.wake(this);
    }

    /** Whether these rules react to this fight. The victim is observed only when a rule needs it. */
    boolean reacts(TargetRules rules, FightKind kind, TargetRef attacker, Supplier<Optional<CombatTarget>> victim) {
        TargetRules.Fights fights = rules.events();
        if (fights.kinds().contains(kind)) return true;
        if (fights.playerAttacks().isPresent() && attacker.kind() == TargetKind.PLAYER
                && victim.get().map((CombatTarget seen) -> fights.playerAttacks().get().matches(seen, context)).orElse(false)) return true;
        return fights.anyAttacks().isPresent() && attacker.kind() != TargetKind.NPC
                && victim.get().map((CombatTarget seen) -> fights.anyAttacks().get().matches(seen, context)).orElse(false);
    }

    /** Whether this message contains any of the rules' chat text. */
    static boolean said(TargetRules rules, String folded) {
        for (String text : rules.chat()) if (folded.contains(text)) return true;
        return false;
    }

    /** This NPC took damage. Wakes it, and it may fight back or flee. */
    void attacked(TargetRef attacker) {
        engine.wake(this);
        if (disengaged || settings.passive() || attacker.id().equals(npc)) return;
        Optional<CombatTarget> seen = engine.resolve(attacker);
        if (seen.isEmpty() || friendly(attacker, seen.get().squad())) return;
        Optional<VirtualNpcState.Snapshot> self = world.npc(npc);
        if (self.isEmpty() || !self.get().active()) return;
        if (settings.avoidance().fleeWhenAttacked()) {
            beginFlight(attacker, self.get());
            return;
        }
        if (settings.targeting().fightBack() && target == null && !refused(attacker)) {
            adopt(attacker, false);
            alertPending = settings.squad().name().isPresent();
        }
    }

    /**
     * The damage that remains after this NPC's defense, or 0. Starts invulnerability frames when any remains. A source the
     * NPC does not accept, or a player without its attacker permission, deals nothing.
     */
    double mitigate(TargetRef attacker, WorldPosition from, double amount, Optional<CombatTarget> attackerView, CombatWorld.DamageKind kind) {
        CombatSettings.Defense defense = settings.defense();
        long now = engine.now();
        if (defense.invincible() || now < invulnerableUntil || state == CombatState.DEAD || !defense.accepts(attacker.kind(), kind)) return 0;
        if (attacker.kind() == TargetKind.PLAYER && defense.attackerPermission().isPresent() && !world.permitted(attacker, defense.attackerPermission().get())) return 0;
        Optional<VirtualNpcState.Snapshot> self = world.npc(npc);
        if (self.isEmpty()) return 0;
        if (defense.protectFromRange() > 0 && (!from.world().equals(self.get().position().world())
                || from.point().distance(self.get().position().point()) > defense.protectFromRange())) return 0;
        if (defense.protectedFromIgnored() && attackerView.isPresent() && settings.ignores().matches(attackerView.get(), context)) return 0;
        double reduced = defense.armorPoints() > 0 ? CombatAttributes.armored(amount, defense.armorPoints(), defense.armorToughness()) : amount * (1 - armor);
        if (reduced <= 0) return 0;
        invulnerableUntil = now + defense.invulnerableTicks();
        return reduced;
    }

    /**
     * The environment's damage that remains after this NPC's defense, or 0: only a listed source, outside invulnerability
     * frames. {@code armored} damage is reduced by armor as a hit is; falls and burning are not.
     */
    double mitigateEnvironment(CombatSettings.DamageSource source, double amount, boolean armored) {
        CombatSettings.Defense defense = settings.defense();
        long now = engine.now();
        if (!defense.takes(source) || now < invulnerableUntil || state == CombatState.DEAD || !(amount > 0)) return 0;
        double reduced = !armored ? amount : defense.armorPoints() > 0 ? CombatAttributes.armored(amount, defense.armorPoints(), defense.armorToughness()) : amount * (1 - armor);
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

    /**
     * Processes a death exactly once: helpers, drops, experience, kill credit, and then the respawn timer, the permadeath
     * record or the deletion request. {@code killer} is null when the last blow came from no known attacker.
     */
    void die(WorldPosition place, TargetRef killer) {
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
            if (engine.random().nextDouble() >= rule.chance()) continue;
            int amount = rule.maximum() > rule.amount() ? rule.amount() + engine.random().nextInt(rule.maximum() - rule.amount() + 1) : rule.amount();
            drops.add(new CombatWorld.Drop(rule.item(), amount, rule.components()));
        }
        if (death.dropEquipment()) drops.addAll(loadout.worn());
        if (!drops.isEmpty() || death.experience() > 0) world.drop(place, drops, death.experience());
        if (killer != null && !killer.id().equals(npc)) world.credit(killer, npc);
        int delay = settings.respawn().delayTicks();
        respawnAt = delay < 0 ? -1 : engine.now() + delay;
        if (settings.respawn().deleteOnDeath()) engine.requestDeletion(npc);
        if (settings.respawn().permadeath()) permadeathAt = engine.now() + DEATH_ANIMATION_TICKS;
        engine.wake(this);
    }

    private long updateDead(long now, VirtualNpcState.Snapshot self) {
        if (!deathHandled) die(self.position(), null);
        long idle = engine.budgets().idleSleepTicks();
        if (permadeathAt >= 0) {
            if (now < permadeathAt) return permadeathAt - now;
            permadeathAt = -1;
            engine.requestPermadeath(npc);
        }
        if (respawnAt < 0) return idle;
        if (now < respawnAt) return respawnAt - now;
        if (!respawn(now)) {
            respawnAt = now + 20;
            return 20;
        }
        return 1;
    }

    /** Brings a dead NPC back now at its next respawn point, or heals a living one fully. False when it cannot come back yet. */
    boolean revive() {
        permadeathAt = -1;
        Optional<VirtualNpcState.Snapshot> self = world.npc(npc);
        if (self.isEmpty()) return false;
        if (self.get().health() > 0) {
            double missing = settings.defense().maximumHealth() - self.get().health();
            if (missing > 0) world.heal(npc, missing);
            return true;
        }
        if (!deathHandled) die(self.get().position(), null);
        if (!respawn(engine.now())) return false;
        engine.wake(this);
        return true;
    }

    /** Where the NPC comes back: the next listed point by the respawn order, else its home or where it fell. */
    private Optional<WorldPosition> respawnPoint() {
        List<CombatSettings.Location> locations = settings.respawn().locations();
        if (locations.isEmpty()) return settings.respawn().home() ? world.home(npc).or(() -> Optional.ofNullable(deathPlace)) : Optional.ofNullable(deathPlace);
        return switch (settings.respawn().order()) {
            case RANDOM -> world.resolve(locations.get(engine.random().nextInt(locations.size())));
            case IN_ORDER -> world.resolve(locations.get(Math.floorMod(nextPoint, locations.size())));
            case NEAREST -> {
                WorldPosition best = null;
                for (CombatSettings.Location location : locations) {
                    Optional<WorldPosition> resolved = world.resolve(location);
                    if (resolved.isEmpty()) continue;
                    if (best == null || nearer(resolved.get(), best)) best = resolved.get();
                }
                yield Optional.ofNullable(best);
            }
        };
    }
    /** Whether this point is nearer to where the NPC fell than the other; a point in its own world beats any elsewhere. */
    private boolean nearer(WorldPosition point, WorldPosition other) {
        if (deathPlace == null) return false;
        boolean here = point.world().equals(deathPlace.world()), there = other.world().equals(deathPlace.world());
        if (here != there) return here;
        return here && point.point().distance(deathPlace.point()) < other.point().distance(deathPlace.point());
    }

    private boolean respawn(long now) {
        Optional<WorldPosition> at = respawnPoint();
        if (at.isEmpty() || !world.revive(npc, at.get())) return false;
        nextPoint++;
        deathHandled = false;
        respawnAt = -1;
        state = CombatState.IDLE;
        nextScan = now;
        backoff = 0;
        nextHeal = 0;
        invulnerableUntil = 0;
        ammunition = fullAmmunition;
        refusals.clear();
        greeted.clear();
        lastSeen = List.of();
        follow.reset();
        progressFrom = null;
        stallReported = false;
        homeward(at.get());
        // Nothing to walk back to: a patrol starts its route again from the first point.
        if (state != CombatState.RETURNING) world.release(npc);
        return true;
    }

    /** A respawn away from the post walks back to it, as the end of a fight does. */
    private void homeward(WorldPosition here) {
        if (guarded != null || !settings.pursuit().returnToPost()) return;
        Optional<WorldPosition> post = world.post(npc).filter((WorldPosition at) -> at.world().equals(here.world()));
        if (post.isEmpty() || on(post.get(), here)) return;
        anchor = post.get();
        state = CombatState.RETURNING;
        nextPlan = 0;
        stuck = 0;
        returnFrom = null;
        moving = false;
    }

    /** Whether a hit with this result was refused rather than merely ineffective. */
    private static boolean refusal(CombatWorld.DamageStatus status) {
        return status == CombatWorld.DamageStatus.PROTECTED || status == CombatWorld.DamageStatus.CANCELLED;
    }
    private boolean refused(TargetRef ref) {
        Long until = refusals.get(ref);
        if (until == null) return false;
        if (engine.now() < until) return true;
        refusals.remove(ref);
        return false;
    }
    private void remember(TargetRef ref) {
        if (refusals.size() >= REFUSAL_MEMORY) {
            long now = engine.now();
            refusals.values().removeIf((Long until) -> until <= now);
            if (refusals.size() >= REFUSAL_MEMORY) return;
        }
        refusals.put(ref, engine.now() + REFUSAL_TICKS);
    }
    /**
     * Protection or a cancelled hit refused this target: it is passed over for {@link #REFUSAL_TICKS}, and a fight with
     * it ends now, mid-swing or mid-path, the way any lost target ends.
     */
    void refuse(TargetRef ref) {
        remember(ref);
        if (!ref.equals(target)) return;
        Optional<VirtualNpcState.Snapshot> self = world.npc(npc);
        if (self.isPresent()) lose(self.get());
        else target = null;
        engine.wake(this);
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

    /** Drops target, threat and movement and stands where it is; the engine is winding down. */
    void stand() {
        threat = null;
        target = null;
        forced = false;
        alertPending = false;
        finish();
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
        follow.reset();
        progressFrom = null;
        stallReported = false;
        if (charge == null && state == CombatState.GUARDING) finish();
        nextPlan = 0;
    }

    private static Vec3 eye(VirtualNpcState.Snapshot self) {
        Vec3 min = self.bounds().min();
        Vec3 max = self.bounds().max();
        return new Vec3((min.x() + max.x()) / 2, min.y() + (max.y() - min.y()) * .85, (min.z() + max.z()) / 2);
    }
}
