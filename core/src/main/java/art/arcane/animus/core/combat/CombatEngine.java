package art.arcane.animus.core.combat;

import art.arcane.animus.core.config.CombatBudgets;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Autonomous combat for registered NPCs. Single owner thread; all work is capped per tick and idle NPCs
 * sleep. All damage in either direction goes through {@link #hit}, which asks protection first.
 * Every combat NPC registers; at most {@code maximumNpcs} of them fight or scan at once. An NPC with no player near and
 * nothing to fight sleeps and holds no slot, and NPCs waiting for a slot get one in the order they asked.
 * <p>
 * A regionized server runs one engine per NPC on that NPC's owner. Such an engine reaches combatants it does not hold
 * only through its world: their published views ({@link CombatWorld#peer}), hits and alerts delivered to their owners,
 * and fights reported to engines elsewhere; per-tick work is drawn from the region's shared {@link Allowance}.
 */
public final class CombatEngine implements CombatOperations, AutoCloseable {
    public record Status(UUID npc, CombatState state, Optional<TargetRef> target, boolean disengaged, int ammunition) {}
    /** Per-tick work an engine may do, shared by every engine that ticks in the same region tick. */
    public interface Allowance {
        enum Kind { BRAINS, SCANS, PATHS, ALERTS, HELPER_UPDATES }
        /** Up to {@code wanted} units for this tick. */
        int take(Kind kind, int wanted);
        /** Units taken this tick and not used. */
        void refund(Kind kind, int unused);
        /** A single engine on its own thread: the configured budgets alone bound it. */
        Allowance UNLIMITED = new Allowance() {
            @Override public int take(Kind kind, int wanted) { return wanted; }
            @Override public void refund(Kind kind, int unused) {}
        };
    }

    private record Scheduled(long wake, long order, CombatBrain brain) {}
    private record Fight(TargetRef attacker, TargetRef victim, FightKind kind) {}
    private record Chat(TargetRef player, String folded) {}

    private static final int EVENT_QUEUE = 64;
    private static final int SIGHTS_PER_EVENT = 8;
    /** Registered combat NPCs, sleeping ones included; the same bound as the NPC runtime's active NPCs. */
    public static final int MAXIMUM_REGISTERED = 10_000;
    private final CombatBudgets budgets;
    private final CombatWorld world;
    private final ProtectionGate protection;
    private final RandomGenerator random;
    private final Consumer<String> diagnostics;
    private final Allowance allowance;
    private final Map<UUID, CombatBrain> brains = new HashMap<>();
    private final Map<String, List<CombatBrain>> squads = new HashMap<>();
    private final Map<TargetRef, List<CombatBrain>> guardians = new HashMap<>();
    private final List<CombatBrain> watchers = new ArrayList<>();
    private final PriorityQueue<Scheduled> schedule = new PriorityQueue<>(
            Comparator.comparingLong(Scheduled::wake).thenComparingLong(Scheduled::order));
    private final HelperLedger helpers;
    private final ArrayDeque<Fight> fights = new ArrayDeque<>();
    private final ArrayDeque<Chat> chats = new ArrayDeque<>();
    private final List<UUID> deletions = new ArrayList<>();
    private final ArrayDeque<CombatBrain> waiting = new ArrayDeque<>();
    private int active;
    private int peakWaiting;
    private boolean pressure;
    private long tick;
    private long order;
    private long droppedEvents;
    private long regionUnavailable;
    private int scansLeft;
    private int pathsLeft;
    private int alertsLeft;
    private int sightsLeft;
    private boolean closed;

    public CombatEngine(CombatBudgets budgets, CombatWorld world, ProtectionAdapter adapter, RandomGenerator random, Consumer<String> diagnostics) {
        this(budgets, world, adapter, random, diagnostics, Allowance.UNLIMITED);
    }
    public CombatEngine(CombatBudgets budgets, CombatWorld world, ProtectionAdapter adapter, RandomGenerator random, Consumer<String> diagnostics, Allowance allowance) {
        this.allowance = Objects.requireNonNull(allowance, "allowance");
        this.budgets = Objects.requireNonNull(budgets, "budgets");
        this.world = Objects.requireNonNull(world, "world");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.protection = new ProtectionGate(adapter, diagnostics);
        this.random = Objects.requireNonNull(random, "random");
        helpers = new HelperLedger(budgets.maximumHelpers(), budgets.helpersPerNpc());
    }

    public int size() { return brains.size(); }
    /** NPCs holding one of the {@code maximumNpcs} slots: fighting, fleeing, returning or scanning near a player. */
    public int activeCount() { return active; }
    /** The {@code maximumNpcs} slot limit. */
    public int slotLimit() { return budgets.maximumNpcs(); }
    /** NPCs that want a slot and wait their turn. */
    public int waitingCount() { return waiting.size(); }
    /** The most NPCs that waited at once since the engine started. */
    public int peakWaiting() { return peakWaiting; }
    /** Slot use for status lines: active of the limit, waiting, sleeping without a slot, and the peak wait. */
    public String capacity() {
        return "fighting-or-scanning=" + active + "/" + budgets.maximumNpcs() + " | waiting=" + waiting.size()
                + " | sleeping=" + (brains.size() - active - waiting.size()) + " | peak-waiting=" + peakWaiting;
    }
    public int helperCount() { return helpers.size(); }
    public long protectionDenials() { return protection.denials(); }
    public long protectionFailures() { return protection.failures(); }
    /** Fight and chat events dropped because the queue was full. */
    public long droppedEvents() { return droppedEvents; }
    /** NPCs whose pursuit region could not be checked on this server. */
    public long regionUnavailable() { return regionUnavailable; }
    public ProtectionGate protection() { return protection; }
    public boolean registered(UUID npc) { return brains.containsKey(npc); }
    /** Whom the NPC guards now, if anyone. */
    public Optional<TargetRef> guarded(UUID npc) {
        CombatBrain brain = brains.get(npc);
        return brain == null ? Optional.empty() : Optional.ofNullable(brain.guarded());
    }
    public Set<UUID> npcs() { return Set.copyOf(brains.keySet()); }
    /** Whether any NPC here reacts to fights or chat it witnesses. */
    public boolean watches() { return !watchers.isEmpty(); }
    public boolean ownsHelper(UUID helper) { return helpers.tracks(helper); }

    public Optional<Status> status(UUID npc) {
        CombatBrain brain = brains.get(npc);
        if (brain == null) return Optional.empty();
        return Optional.of(new Status(npc, brain.state(), Optional.ofNullable(brain.target()), brain.disengaged(), brain.ammunition()));
    }

    /** Starts or replaces this NPC's combat. False when {@link #MAXIMUM_REGISTERED} NPCs are registered. */
    public boolean register(UUID npc, String handle, CombatSettings settings, CombatLoadout loadout) {
        if (closed) return false;
        CombatBrain previous = brains.get(npc);
        if (previous != null && previous.settings().equals(settings) && previous.loadout().equals(loadout)) return true;
        if (previous == null && brains.size() >= MAXIMUM_REGISTERED) return false;
        if (previous != null) unregister(npc);
        CombatBrain brain = new CombatBrain(this, world, npc, handle, settings, loadout);
        brains.put(npc, brain);
        settings.squad().name().ifPresent((String squad) -> squads.computeIfAbsent(squad, (String key) -> new ArrayList<>()).add(brain));
        indexGuard(brain);
        if (brain.watches()) watchers.add(brain);
        schedule(brain, tick + 1);
        return true;
    }

    /** Ends this NPC's combat and removes every helper it owns. Its runtime has gone, so nothing is commanded. */
    public void unregister(UUID npc) {
        CombatBrain brain = brains.remove(npc);
        if (brain == null) return;
        brain.retire();
        vacate(brain);
        removeHelpers(npc);
        watchers.remove(brain);
        brain.settings().squad().name().ifPresent((String squad) -> {
            List<CombatBrain> members = squads.get(squad);
            if (members != null) {
                members.remove(brain);
                if (members.isEmpty()) squads.remove(squad);
            }
        });
        unindexGuard(brain);
    }

    public void tick() {
        if (closed) return;
        tick++;
        Scheduled due = schedule.peek();
        boolean work = due != null && due.wake() <= tick || helpers.size() > 0 || !fights.isEmpty() || !chats.isEmpty();
        if (!work) return;
        scansLeft = allowance.take(Allowance.Kind.SCANS, budgets.targetScansPerTick());
        pathsLeft = allowance.take(Allowance.Kind.PATHS, budgets.pathRequestsPerTick());
        alertsLeft = allowance.take(Allowance.Kind.ALERTS, budgets.squadAlertsPerTick());
        try {
            int visits = helpers.size() == 0 ? 0 : allowance.take(Allowance.Kind.HELPER_UPDATES, Math.min(budgets.helperUpdatesPerTick(), helpers.size()));
            allowance.refund(Allowance.Kind.HELPER_UPDATES, visits - updateHelpers(visits));
            events();
            int brainTurns = allowance.take(Allowance.Kind.BRAINS, budgets.brainsPerTick()), visited = 0;
            while (visited < brainTurns) {
                Scheduled next = schedule.peek();
                if (next == null || next.wake() > tick) break;
                schedule.poll();
                CombatBrain brain = next.brain();
                if (brain.retired() || brain.wake() != next.wake()) continue;
                visited++;
                schedule(brain, tick + Math.max(1, brain.update(tick)));
            }
            allowance.refund(Allowance.Kind.BRAINS, brainTurns - visited);
        } finally {
            allowance.refund(Allowance.Kind.SCANS, scansLeft);
            allowance.refund(Allowance.Kind.PATHS, pathsLeft);
            allowance.refund(Allowance.Kind.ALERTS, alertsLeft);
            scansLeft = 0; pathsLeft = 0; alertsLeft = 0;
        }
    }

    long now() { return tick; }
    CombatBudgets budgets() { return budgets; }
    RandomGenerator random() { return random; }
    boolean takeScan() { return scansLeft > 0 && scansLeft-- > 0; }
    boolean takePath() { return pathsLeft > 0 && pathsLeft-- > 0; }
    boolean takeSight() { return sightsLeft > 0 && sightsLeft-- > 0; }
    boolean helperCapacity(UUID owner) { return helpers.hasCapacity(owner); }
    String speaker(CombatBrain brain) {
        String name = world.npcName(brain.npc());
        return name.isBlank() ? brain.handle() : name;
    }
    Optional<Boolean> inRegion(String region, WorldPosition position) { return protection.inRegion(region, position); }
    void regionUnavailable(CombatBrain brain, String region) {
        regionUnavailable++;
        diagnostics.accept("Combat NPC " + brain.handle() + ": pursuit region " + region + " cannot be checked (no protection plugin provides regions here, or the region does not exist); the region leash is not applied");
    }
    void guardStalled(CombatBrain brain, long ticks, String distance, String movement, String remedy) {
        diagnostics.accept("Combat NPC " + brain.handle() + ": guard made no progress toward its charge for " + ticks + " ticks (distance "
                + distance + "; movement: " + movement + "); " + remedy);
    }
    /** Whether a player is near enough that this NPC should stay awake. */
    boolean playersNear(WorldPosition center, double radius) { return world.playersNear(center, radius); }

    /** True when the NPC holds a slot or takes a free one; otherwise it joins the end of the queue once. */
    boolean claim(CombatBrain brain) {
        if (brain.slot() == CombatBrain.Slot.ACTIVE) return true;
        if (brain.slot() == CombatBrain.Slot.WAITING) return false;
        if (active < budgets.maximumNpcs() && waiting.isEmpty()) {
            brain.slot(CombatBrain.Slot.ACTIVE);
            active++;
            return true;
        }
        enqueue(brain);
        return false;
    }
    /** Gives up the slot or the place in the queue; the slot goes to the NPC that has waited longest. */
    void vacate(CombatBrain brain) {
        if (brain.slot() == CombatBrain.Slot.WAITING) waiting.remove(brain);
        if (brain.slot() == CombatBrain.Slot.ACTIVE) active--;
        brain.slot(CombatBrain.Slot.NONE);
        grant();
    }
    /** An NPC that only scans hands its slot to the longest waiting NPC and queues again behind the others. */
    void yieldTurn(CombatBrain brain) {
        if (brain.slot() != CombatBrain.Slot.ACTIVE || waiting.isEmpty()) return;
        active--;
        brain.slot(CombatBrain.Slot.NONE);
        enqueue(brain);
        grant();
    }
    private void enqueue(CombatBrain brain) {
        brain.slot(CombatBrain.Slot.WAITING);
        waiting.addLast(brain);
        peakWaiting = Math.max(peakWaiting, waiting.size());
        if (pressure) return;
        pressure = true;
        diagnostics.accept("Combat capacity reached: " + budgets.maximumNpcs() + " NPCs fight or scan at once ([combat] maximumNpcs); "
                + waiting.size() + " more wait their turn. See /animus combat status");
    }
    private void grant() {
        while (active < budgets.maximumNpcs() && !waiting.isEmpty()) {
            CombatBrain next = waiting.pollFirst();
            next.slot(CombatBrain.Slot.NONE);
            if (next.retired()) continue;
            next.slot(CombatBrain.Slot.ACTIVE);
            active++;
            wake(next);
        }
        if (pressure && waiting.isEmpty() && active < budgets.maximumNpcs()) {
            pressure = false;
            diagnostics.accept("Combat capacity free: no NPC waits for a combat slot (at most " + peakWaiting + " waited)");
        }
    }

    void requestDeletion(UUID npc) { if (!deletions.contains(npc)) deletions.add(npc); }

    /** NPCs that died with {@code deleteOnDeath} since the last call. Each death is returned once. */
    public List<UUID> drainDeletions() {
        List<UUID> drained = List.copyOf(deletions);
        deletions.clear();
        return drained;
    }

    private void schedule(CombatBrain brain, long wake) {
        brain.scheduled(wake);
        schedule.add(new Scheduled(wake, order++, brain));
    }
    /** Brings a sleeping NPC forward to the next tick. Never postpones one that is already due. */
    void wake(CombatBrain brain) {
        if (!brain.retired() && brain.wake() > tick + 1) schedule(brain, tick + 1);
    }

    /** Real entities and combat NPCs near the center, capped by the scan budgets. */
    List<CombatTarget> scan(CombatBrain brain, WorldPosition center, double radius) {
        List<CombatTarget> found = new ArrayList<>(world.nearbyReal(center, radius, budgets.scanCandidates()));
        SpatialNpcIndex.Scan npcs = world.nearbyNpcs(center, radius, brain.npcCursor(),
                new SpatialNpcIndex.Budget(budgets.scanCells(), budgets.scanCandidates()));
        brain.npcCursor(npcs.next().orElse(null));
        for (VirtualNpcState.Snapshot snapshot : npcs.candidates()) {
            CombatBrain other = brains.get(snapshot.npcId());
            if (other != null && other != brain) found.add(view(other, snapshot));
            else if (other == null && !snapshot.npcId().equals(brain.npc())) world.peer(snapshot.npcId()).ifPresent(found::add);
        }
        return found;
    }

    Optional<CombatTarget> resolve(TargetRef ref) {
        if (ref.kind() != TargetKind.NPC) return world.real(ref);
        CombatBrain brain = brains.get(ref.id());
        if (brain == null) return world.peer(ref.id());
        Optional<VirtualNpcState.Snapshot> snapshot = world.npc(ref.id());
        if (snapshot.isEmpty() || !snapshot.get().active()) return Optional.empty();
        return Optional.of(view(brain, snapshot.get()));
    }

    private CombatTarget view(CombatBrain brain, VirtualNpcState.Snapshot snapshot) {
        return npcTarget(brain.npc(), speaker(brain), snapshot, brain.handle(), brain.settings(), brain.loadout());
    }
    /** How a combat NPC appears to every rule and hit, here or as a view its owner published for other engines. */
    public static CombatTarget npcTarget(UUID npc, String name, VirtualNpcState.Snapshot snapshot, String handle, CombatSettings settings, CombatLoadout loadout) {
        List<String> worn = new ArrayList<>();
        for (String slot : List.of("head", "chest", "legs", "feet")) if (!loadout.item(slot).equals(CombatLoadout.EMPTY)) worn.add(loadout.item(slot));
        CombatTarget.Details details = new CombatTarget.Details(settings.defense().maximumHealth(), loadout.item("main_hand"), loadout.item("off_hand"), worn, Set.of());
        return new CombatTarget(new TargetRef(TargetKind.NPC, npc), "animus:npc", name, snapshot.position(),
                snapshot.bounds(), snapshot.health(), Set.of(TargetCategory.NPCS), handle, settings.squad().name(), details);
    }

    /** Offers this target to the NPC's squad, spending the shared per-tick alert budget. */
    void alertSquad(CombatBrain source, VirtualNpcState.Snapshot self, TargetRef target) {
        Optional<String> squad = source.settings().squad().name();
        if (squad.isEmpty()) return;
        double range = source.settings().squad().alertRange();
        for (CombatBrain member : squads.getOrDefault(squad.get(), List.of())) {
            if (alertsLeft <= 0) return;
            if (member == source || !member.awaitsTarget()) continue;
            Optional<VirtualNpcState.Snapshot> other = world.npc(member.npc());
            if (other.isEmpty() || !other.get().active() || !other.get().position().world().equals(self.position().world())
                    || other.get().position().point().distance(self.position().point()) > range) continue;
            alertsLeft--;
            member.alerted(target);
        }
        if (alertsLeft > 0) alertsLeft -= Math.max(0, Math.min(alertsLeft, world.alertPeers(squad.get(), source.npc(), self.position(), range, target, alertsLeft)));
    }
    /** A squad mate another engine holds offers this target. */
    public void alerted(UUID npc, TargetRef target) {
        CombatBrain member = brains.get(npc);
        if (closed || member == null || !member.awaitsTarget()) return;
        member.alerted(target);
        wake(member);
    }

    /**
     * A real entity hurt another. Guards of the victim respond, guards that assist the attacker join in, and NPCs with
     * fight rules learn of it on a later tick.
     */
    public void realDamaged(TargetRef victim, TargetRef attacker) {
        for (CombatBrain guardian : guardians.getOrDefault(victim, List.of())) guardian.alerted(attacker);
        fought(attacker, victim);
    }
    /** A fight another engine saw or the platform reported for this engine only: it is not passed on again. */
    public void heardFight(TargetRef victim, TargetRef attacker) {
        if (closed) return;
        for (CombatBrain guardian : guardians.getOrDefault(victim, List.of())) { guardian.alerted(attacker); wake(guardian); }
        witnessed(attacker, victim);
    }

    /** A player said this in chat. NPCs with chat rules nearby learn of it on a later tick. Owner thread only. */
    public void chat(TargetRef player, String message) {
        if (closed || watchers.isEmpty() || message == null || message.isEmpty()) return;
        if (chats.size() >= EVENT_QUEUE) { droppedEvents++; return; }
        chats.addLast(new Chat(player, message.toLowerCase(Locale.ROOT)));
    }

    private void fought(TargetRef attacker, TargetRef victim) {
        witnessed(attacker, victim);
        if (!closed) world.foughtElsewhere(attacker, victim);
    }
    private void witnessed(TargetRef attacker, TargetRef victim) {
        for (CombatBrain guardian : guardians.getOrDefault(attacker, List.of())) {
            if (guardian.settings().guard().assist()) guardian.alerted(victim);
        }
        if (closed || watchers.isEmpty()) return;
        Optional<FightKind> kind = FightKind.of(attacker, victim);
        if (kind.isEmpty()) return;
        if (fights.size() >= EVENT_QUEUE) { droppedEvents++; return; }
        fights.addLast(new Fight(attacker, victim, kind.get()));
    }

    /** At most {@code eventsPerTick} fights and messages, each with a bounded number of sight checks. */
    private void events() {
        for (int handled = 0; handled < budgets.eventsPerTick(); handled++) {
            Fight fight = fights.pollFirst();
            if (fight != null) {
                fight(fight);
                continue;
            }
            Chat chat = chats.pollFirst();
            if (chat == null) return;
            said(chat);
        }
    }

    private void fight(Fight fight) {
        Optional<CombatTarget> attacker = resolve(fight.attacker());
        if (attacker.isEmpty()) return;
        Supplier<Optional<CombatTarget>> observed = new Supplier<>() {
            private Optional<CombatTarget> victim;
            @Override public Optional<CombatTarget> get() {
                if (victim == null) victim = resolve(fight.victim());
                return victim;
            }
        };
        sightsLeft = SIGHTS_PER_EVENT;
        for (CombatBrain watcher : List.copyOf(watchers)) {
            if (watcher.npc().equals(fight.attacker().id())) continue;
            boolean asTarget = watcher.reacts(watcher.settings().targets(), fight.kind(), fight.attacker(), observed);
            boolean asThreat = watcher.reacts(watcher.settings().avoids(), fight.kind(), fight.attacker(), observed);
            if (asTarget || asThreat) watcher.witnessed(attacker.get(), asTarget, asThreat);
        }
    }

    private void said(Chat chat) {
        Optional<CombatTarget> player = resolve(chat.player());
        if (player.isEmpty()) return;
        sightsLeft = SIGHTS_PER_EVENT;
        for (CombatBrain watcher : List.copyOf(watchers)) {
            boolean asTarget = CombatBrain.said(watcher.settings().targets(), chat.folded());
            boolean asThreat = CombatBrain.said(watcher.settings().avoids(), chat.folded());
            if (asTarget || asThreat) watcher.witnessed(player.get(), asTarget, asThreat);
        }
    }

    /** All damage goes through here: protection first, then defense, then exactly one damage authority. */
    private CombatWorld.DamageOutcome hit(TargetRef attacker, WorldPosition from, CombatTarget target, double amount,
                                          CombatWorld.DamageKind kind, double reach, double knockback, boolean victimDrops) {
        if (!allowed(attacker, from, target)) return new CombatWorld.DamageOutcome(CombatWorld.DamageStatus.PROTECTED, 0, false);
        if (target.ref().kind() != TargetKind.NPC) {
            CombatWorld.DamageOutcome outcome = world.damageReal(attacker.id(), target, amount, kind, reach, knockback, victimDrops);
            if (outcome.status() == CombatWorld.DamageStatus.APPLIED) realDamaged(target.ref(), attacker);
            return outcome;
        }
        CombatBrain defender = brains.get(target.ref().id());
        if (defender == null) return world.strikePeer(attacker, from, target, amount, knockback);
        return strike(defender, attacker, from, target, amount, knockback);
    }

    /**
     * A hit on an NPC this engine holds, delivered by the engine that dealt it. The attacker's engine already asked
     * protection; this side applies defense and the one damage authority.
     */
    public CombatWorld.DamageOutcome struck(UUID npc, TargetRef attacker, WorldPosition from, double amount, double knockback) {
        CombatBrain defender = brains.get(npc);
        Optional<VirtualNpcState.Snapshot> snapshot = world.npc(npc);
        if (closed || defender == null || snapshot.isEmpty() || !snapshot.get().active() || !(amount > 0))
            return new CombatWorld.DamageOutcome(CombatWorld.DamageStatus.NO_CHANGE, 0, false);
        CombatWorld.DamageOutcome outcome = strike(defender, attacker, from, view(defender, snapshot.get()), amount, knockback);
        wake(defender);
        return outcome;
    }
    private CombatWorld.DamageOutcome strike(CombatBrain defender, TargetRef attacker, WorldPosition from, CombatTarget target, double amount, double knockback) {
        double mitigated = defender.mitigate(attacker, from, amount, resolve(attacker));
        if (mitigated <= 0) return new CombatWorld.DamageOutcome(CombatWorld.DamageStatus.NO_CHANGE, 0, false);
        CombatWorld.DamageOutcome outcome = world.damageVirtual(defender.npc(), attacker, mitigated);
        if (outcome.status() != CombatWorld.DamageStatus.APPLIED) return outcome;
        afterVirtualHit(defender, attacker, from, target, knockback, outcome.killed());
        return outcome;
    }

    private boolean allowed(TargetRef attacker, WorldPosition from, CombatTarget target) {
        ProtectionAdapter.Action action = switch (attacker.kind()) {
            case NPC -> ProtectionAdapter.Action.NPC_ATTACK;
            case PLAYER -> ProtectionAdapter.Action.PLAYER_ATTACK;
            case MOB -> ProtectionAdapter.Action.MOB_ATTACK;
        };
        return protection.allows(new ProtectionAdapter.Request(action, attacker, from, target.ref(), target.position(), target.categories()));
    }

    private void afterVirtualHit(CombatBrain defender, TargetRef attacker, WorldPosition from, CombatTarget target, double knockback, boolean killed) {
        fought(attacker, target.ref());
        if (killed) {
            defender.die(target.position());
            return;
        }
        if (knockback > 0 && defender.settings().defense().knockback()) {
            Vec3 away = target.position().point().subtract(from.point());
            if (Math.abs(away.x()) + Math.abs(away.z()) > .0001) world.knockback(defender.npc(), new Vec3(away.x(), 0, away.z()), knockback);
        }
        defender.attacked(attacker);
        for (CombatBrain guardian : guardians.getOrDefault(target.ref(), List.of())) guardian.alerted(attacker);
    }

    CombatWorld.DamageOutcome melee(CombatBrain brain, VirtualNpcState.Snapshot self, CombatTarget target) {
        CombatSettings.Melee melee = brain.settings().melee();
        return hit(new TargetRef(TargetKind.NPC, brain.npc()), self.position(), target, brain.meleeDamage(), CombatWorld.DamageKind.MELEE,
                melee.reach(), melee.knockback(), brain.settings().death().victimDrops());
    }

    /** Launches one real helper projectile toward the target and records it before anything can touch it. */
    boolean shoot(CombatBrain brain, WorldPosition from, CombatTarget target) {
        CombatSettings.Ranged ranged = brain.settings().ranged();
        Vec3 aim = CombatGeometry.center(target.bounds());
        Vec3 line = aim.subtract(from.point());
        double distance = line.distance(Vec3.ZERO);
        if (distance < .0001) return false;
        Vec3 direction = line.normalized();
        double spread = ranged.inaccuracy() * .0075;
        double arc = ranged.weapon().gravity() ? .025 * distance / ranged.velocity() : 0;
        Vec3 velocity = new Vec3(
                direction.x() * ranged.velocity() + random.nextGaussian() * spread,
                direction.y() * ranged.velocity() + random.nextGaussian() * spread + arc,
                direction.z() * ranged.velocity() + random.nextGaussian() * spread);
        Optional<UUID> helper = world.launchHelper(brain.npc(), from, velocity, ranged.weapon());
        if (helper.isEmpty()) return false;
        helpers.track(new HelperLedger.Shot(helper.get(), brain.npc(), Optional.of(target.ref()), brain.rangedDamage(), ranged.knockback(),
                ranged.safeShot(), ranged.effect(), tick + budgets.helperLifetimeTicks(), from.point()));
        return true;
    }

    /** Visits at most {@code visits} helpers; returns how many it visited. */
    private int updateHelpers(int visits) {
        for (int i = 0; i < visits; i++) {
            Optional<HelperLedger.Shot> next = helpers.next();
            if (next.isEmpty()) return i;
            HelperLedger.Shot shot = next.get();
            Optional<Vec3> position = world.helperPosition(shot.helper());
            if (position.isEmpty()) {
                helpers.claim(shot.helper());
                continue;
            }
            if (tick >= shot.expiresAt()) {
                retire(shot.helper());
                continue;
            }
            if (!collide(shot, position.get())) shot.moved(position.get());
        }
        return visits;
    }

    /** Whether a shot may land on this participant: a helpful potion reaches friends, anything else only foes. */
    private static boolean reaches(HelperLedger.Shot shot, CombatBrain owner, TargetRef struck, Optional<String> squad) {
        if (struck.id().equals(owner.npc())) return false;
        boolean helpful = shot.effect().map(CombatSettings.Effect::helpful).orElse(false);
        if (!helpful && owner.friendly(struck, squad)) return false;
        return !shot.safeShot() || shot.intended().equals(Optional.of(struck));
    }

    /** Real projectiles cannot touch virtual bodies, so their flight segment is tested against nearby NPC bounds. */
    private boolean collide(HelperLedger.Shot shot, Vec3 position) {
        Vec3 from = shot.lastPosition();
        double length = from.distance(position);
        if (length < .0001) return false;
        CombatBrain owner = brains.get(shot.owner());
        Optional<VirtualNpcState.Snapshot> self = world.npc(shot.owner());
        if (owner == null || self.isEmpty()) return false;
        Vec3 middle = from.add(position).multiply(.5);
        SpatialNpcIndex.Scan near = world.nearbyNpcs(new WorldPosition(self.get().position().world(), middle), length / 2 + 4, null,
                new SpatialNpcIndex.Budget(budgets.scanCells(), budgets.scanCandidates()));
        Vec3 direction = position.subtract(from);
        CombatBrain struck = null;
        VirtualNpcState.Snapshot struckState = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (VirtualNpcState.Snapshot candidate : near.candidates()) {
            CombatBrain other = brains.get(candidate.npcId());
            if (other == null || !reaches(shot, owner, new TargetRef(TargetKind.NPC, other.npc()), other.settings().squad().name())) continue;
            OptionalDouble distance = candidate.bounds().rayDistance(from, direction, length);
            if (distance.isPresent() && distance.getAsDouble() < nearest) {
                nearest = distance.getAsDouble();
                struck = other;
                struckState = candidate;
            }
        }
        if (struck == null || helpers.claim(shot.helper()).isEmpty()) return false;
        world.removeHelper(shot.helper());
        land(shot, owner, self.get().position(), view(struck, struckState));
        return true;
    }

    /**
     * A tracked helper touched a real entity. Its vanilla damage and effects must be cancelled by the platform; this is
     * the only effect it has. True when the helper was live and has been spent.
     */
    public boolean helperHitReal(UUID helper, TargetRef struck) {
        Optional<HelperLedger.Shot> claimed = helpers.claim(helper);
        if (claimed.isEmpty()) return false;
        world.removeHelper(helper);
        HelperLedger.Shot shot = claimed.get();
        CombatBrain owner = brains.get(shot.owner());
        Optional<VirtualNpcState.Snapshot> self = world.npc(shot.owner());
        Optional<CombatTarget> target = resolve(struck);
        if (owner == null || self.isEmpty() || target.isEmpty()) return true;
        if (!reaches(shot, owner, struck, target.get().squad())) return true;
        land(shot, owner, self.get().position(), target.get());
        return true;
    }

    /** Applies a spent shot once: damage, or a potion's effect. */
    private void land(HelperLedger.Shot shot, CombatBrain owner, WorldPosition from, CombatTarget target) {
        TargetRef attacker = new TargetRef(TargetKind.NPC, shot.owner());
        if (shot.effect().isEmpty()) {
            hit(attacker, from, target, shot.damage(), CombatWorld.DamageKind.PROJECTILE, 0, shot.knockback(), owner.settings().death().victimDrops());
            return;
        }
        CombatSettings.Effect effect = shot.effect().get();
        if (!effect.helpful() && !allowed(attacker, from, target)) return;
        if (target.ref().kind() != TargetKind.NPC) {
            if (world.applyEffect(shot.owner(), target, effect) && !effect.helpful()) realDamaged(target.ref(), attacker);
            return;
        }
        if (effect.id().equals("minecraft:instant_health")) world.heal(target.ref().id(), 4 << effect.amplifier());
        else if (effect.id().equals("minecraft:instant_damage")) {
            CombatBrain defender = brains.get(target.ref().id());
            if (defender == null) return;
            double mitigated = defender.mitigate(attacker, from, 6 << effect.amplifier(), resolve(attacker));
            if (mitigated <= 0) return;
            CombatWorld.DamageOutcome outcome = world.damageVirtual(defender.npc(), attacker, mitigated);
            if (outcome.status() == CombatWorld.DamageStatus.APPLIED) afterVirtualHit(defender, attacker, from, target, 0, outcome.killed());
        }
    }

    public boolean helperHitBlock(UUID helper) { return retire(helper); }

    /** Removes this helper if it is still tracked. For cancellation and unload paths in the platform. */
    public boolean retire(UUID helper) {
        if (helpers.claim(helper).isEmpty()) return false;
        world.removeHelper(helper);
        return true;
    }

    void removeHelpers(UUID owner) {
        for (UUID helper : helpers.retireOwner(owner)) world.removeHelper(helper);
    }

    /** A real player hit this NPC's validated hitbox. NPCs with no enabled combat cannot be hurt. */
    public CombatWorld.DamageOutcome playerAttack(UUID npc, TargetRef player, WorldPosition from, double amount) {
        CombatBrain brain = brains.get(npc);
        Optional<VirtualNpcState.Snapshot> snapshot = world.npc(npc);
        if (brain == null || snapshot.isEmpty() || !snapshot.get().active() || !(amount > 0))
            return new CombatWorld.DamageOutcome(CombatWorld.DamageStatus.NO_CHANGE, 0, false);
        return hit(player, from, view(brain, snapshot.get()), amount, CombatWorld.DamageKind.MELEE, 0, .4, true);
    }

    /** Applies this NPC's defense to a hit from outside the engine. Returns the amount to apply, or 0. */
    public double mitigateExternal(UUID npc, TargetRef attacker, WorldPosition from, double amount) {
        CombatBrain brain = brains.get(npc);
        return brain == null ? amount : brain.mitigate(attacker, from, amount, resolve(attacker));
    }

    /** Reports a hit applied outside the engine, such as a controlled mob's. */
    public void externalHit(UUID npc, TargetRef attacker, VirtualNpcState.Snapshot after) {
        CombatBrain brain = brains.get(npc);
        if (brain == null) return;
        fought(attacker, new TargetRef(TargetKind.NPC, npc));
        if (after.health() == 0) brain.die(after.position());
        else brain.attacked(attacker);
    }

    private void indexGuard(CombatBrain brain) {
        TargetRef guarded = brain.guarded();
        if (guarded != null) guardians.computeIfAbsent(guarded, (TargetRef key) -> new ArrayList<>()).add(brain);
    }
    private void unindexGuard(CombatBrain brain) {
        TargetRef guarded = brain.guarded();
        if (guarded == null) return;
        List<CombatBrain> list = guardians.get(guarded);
        if (list == null) return;
        list.remove(brain);
        if (list.isEmpty()) guardians.remove(guarded);
    }

    @Override public Result engage(UUID npc) {
        return control(npc, (CombatBrain brain) -> {
            brain.engage();
            return new Result(CombatOperations.Status.APPLIED, "Combat engaged");
        });
    }
    @Override public Result disengage(UUID npc) {
        return control(npc, (CombatBrain brain) -> {
            brain.disengage();
            removeHelpers(npc);
            return new Result(CombatOperations.Status.APPLIED, "Combat disengaged");
        });
    }
    @Override public Result target(UUID npc, TargetRef target) {
        return control(npc, (CombatBrain brain) -> {
            Optional<VirtualNpcState.Snapshot> self = world.npc(npc);
            Optional<CombatTarget> seen = target.id().equals(npc) ? Optional.empty() : resolve(target);
            if (self.isEmpty() || seen.isEmpty() || !seen.get().position().world().equals(self.get().position().world()))
                return new Result(CombatOperations.Status.INVALID_TARGET, "Target is not available in this NPC's world");
            brain.force(target);
            return new Result(CombatOperations.Status.APPLIED, "Target set");
        });
    }
    @Override public Result release(UUID npc) {
        return control(npc, (CombatBrain brain) -> {
            brain.release();
            return new Result(CombatOperations.Status.APPLIED, "Target released");
        });
    }
    @Override public Result guard(UUID npc, Optional<TargetRef> guarded) {
        return control(npc, (CombatBrain brain) -> {
            if (guarded.isPresent() && (guarded.get().id().equals(npc) || resolve(guarded.get()).isEmpty()))
                return new Result(CombatOperations.Status.INVALID_TARGET, "Guarded participant is not available");
            unindexGuard(brain);
            brain.guard(guarded.orElse(null));
            indexGuard(brain);
            return new Result(CombatOperations.Status.APPLIED, guarded.isPresent() ? "Guarding" : "Guard cleared");
        });
    }
    private interface Control { Result apply(CombatBrain brain); }
    private Result control(UUID npc, Control control) {
        if (closed) return new Result(CombatOperations.Status.UNAVAILABLE, "Combat is shutting down");
        CombatBrain brain = brains.get(npc);
        if (brain == null) return new Result(CombatOperations.Status.NOT_COMBATANT, "NPC has no enabled combat");
        if (brain.state() == CombatState.DEAD) return new Result(CombatOperations.Status.DEAD, "NPC is dead");
        Result result = control.apply(brain);
        wake(brain);
        return result;
    }

    /** Removes every helper and ends all combat. Safe to call twice. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        for (UUID helper : helpers.retireAll()) world.removeHelper(helper);
        for (CombatBrain brain : List.copyOf(brains.values())) brain.retire();
        brains.clear();
        squads.clear();
        guardians.clear();
        watchers.clear();
        fights.clear();
        chats.clear();
        schedule.clear();
        waiting.clear();
        active = 0;
    }

    /** Whether the line from a shooter to a target crosses this body. */
    static boolean crosses(Hitbox body, Vec3 from, Vec3 to) {
        double length = from.distance(to);
        return length > .0001 && body.rayDistance(from, to.subtract(from), length).isPresent();
    }
}
