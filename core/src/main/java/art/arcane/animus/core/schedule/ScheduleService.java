package art.arcane.animus.core.schedule;

import art.arcane.animus.core.AnimusLog;
import art.arcane.animus.core.navigation.MovementSettings;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Runs every active NPC's {@code animus:schedule}. {@link #tick} is called every owner tick and does its work once a
 * second: each scheduled NPC gets one step on its own owner, which reads the world clock, starts a leg when the time,
 * the weather or a finished stay picks another location, applies the arrival when a leg ends and starts wander legs.
 * Nothing is saved: after a restart each NPC walks to the location the clock gives it.
 */
public final class ScheduleService {
    /** Owner ticks between steps. */
    public static final int STEP_TICKS = 20;
    /** Seconds before a leg that could not reach its location is tried again. */
    public static final int RETRY_SECONDS = 30;
    /** The namespace whose command permission also lets imported Citizens schedules run their arrival commands. */
    public static final String IMPORTED_COMMANDS = "citizens";
    private static final double DEFAULT_SPEED = 2, EYE = 1.62, LOOK_DISTANCE = 4;
    private static final int NONE = 0, ARRIVED = 1, FAILED = 2;

    enum Phase { IDLE, TRAVELING, AT, WANDERING, RETRY }
    record Bound(NpcDefinition definition, ScheduleSettings schedule) {}

    /** One NPC's progress. Steps run one at a time on the NPC's owner; {@link #signal} is set from route completions. */
    static final class Walker {
        /** Replaced by an edit that leaves the schedule, speed and pose alone, so the walk goes on. */
        volatile NpcDefinition definition;
        final ScheduleSettings schedule; final double speed; final NpcPose declared;
        final AtomicBoolean queued = new AtomicBoolean();
        final AtomicInteger signal = new AtomicInteger(NONE);
        volatile Phase phase = Phase.IDLE;
        int location = -1, slot = -1;
        /** Counts legs, so a completion of a replaced leg is ignored. */
        volatile int leg;
        long stayUntil, nextWander, retryAt;
        boolean posed;
        Walker(NpcDefinition definition, ScheduleSettings schedule) {
            this.definition = definition; this.schedule = schedule;
            speed = speed(definition, schedule);
            declared = NpcAppearance.of(definition).pose();
        }
        static double speed(NpcDefinition definition, ScheduleSettings schedule) {
            return schedule.speed().orElse(MovementSettings.parse(definition.json()).speed().orElse(DEFAULT_SPEED));
        }
        /** Whether an edit to {@code next} leaves this walk as it is. */
        boolean keeps(Bound next) {
            return schedule.equals(next.schedule()) && speed == speed(next.definition(), next.schedule()) && declared == NpcAppearance.of(next.definition()).pose();
        }
        void reset() { phase = Phase.IDLE; location = -1; slot = -1; leg++; posed = false; signal.set(NONE); }
    }

    private final Supplier<Map<String, NpcDefinition>> definitions;
    private final ScheduleWorld world;
    private final Supplier<List<String>> commandNamespaces;
    private final RandomGenerator random;
    private final Consumer<String> log;
    private final Map<UUID, Walker> walkers = new ConcurrentHashMap<>();
    private Map<String, NpcDefinition> source;
    private Map<UUID, Bound> bound = Map.of();
    private long ticks, seconds;

    public ScheduleService(Supplier<Map<String, NpcDefinition>> definitions, ScheduleWorld world, Supplier<List<String>> commandNamespaces,
                           RandomGenerator random, Consumer<String> log) {
        this.definitions = Objects.requireNonNull(definitions); this.world = Objects.requireNonNull(world);
        this.commandNamespaces = Objects.requireNonNull(commandNamespaces); this.random = Objects.requireNonNull(random); this.log = Objects.requireNonNull(log);
    }

    /** Every owner tick; steps each scheduled NPC once a second. */
    public void tick() {
        if (++ticks % STEP_TICKS != 0) return;
        long now = ++seconds;
        Map<UUID, Bound> current = bindings();
        walkers.keySet().removeIf((UUID npc) -> !current.containsKey(npc));
        for (Map.Entry<UUID, Bound> entry : current.entrySet()) {
            Bound wanted = entry.getValue();
            Walker walker = walkers.compute(entry.getKey(), (UUID npc, Walker known) -> {
                if (known == null || (known.definition != wanted.definition() && !known.keeps(wanted))) return new Walker(wanted.definition(), wanted.schedule());
                known.definition = wanted.definition();
                return known;
            });
            if (!walker.queued.compareAndSet(false, true)) continue;
            boolean placed = world.onOwner(entry.getKey(), () -> {
                try { step(walker, now); }
                finally { walker.queued.set(false); }
            }, () -> walker.queued.set(false));
            if (!placed) { walker.queued.set(false); walker.reset(); }
        }
    }

    private Map<UUID, Bound> bindings() {
        Map<String, NpcDefinition> snapshot = definitions.get();
        if (snapshot != source) {
            Map<UUID, Bound> next = new HashMap<>();
            for (NpcDefinition definition : snapshot.values()) {
                if (!definition.spawned()) continue;
                try { ScheduleSettings.of(definition).ifPresent((ScheduleSettings schedule) -> next.put(definition.uuid(), new Bound(definition, schedule))); }
                catch (IllegalArgumentException invalid) { /* Definitions are validated on load; an invalid module schedules nothing. */ }
            }
            bound = Map.copyOf(next);
            source = snapshot;
        }
        return bound;
    }

    private void step(Walker walker, long now) {
        String id = walker.definition.id();
        Optional<WorldPosition> at = world.position(walker.definition.uuid());
        if (at.isEmpty()) { walker.reset(); return; }
        String worldName = at.get().world();
        Optional<ScheduleWorld.Clock> clock = world.clock(worldName);
        if (clock.isEmpty()) return;
        try {
            int signal = walker.signal.getAndSet(NONE);
            if (signal == ARRIVED) arrived(walker, worldName, now);
            else if (signal == FAILED) failed(walker, now);
            List<ScheduleSettings.Location> locations = walker.schedule.locations();
            boolean storm = clock.get().storm();
            OptionalInt slot = SchedulePlan.slot(locations, clock.get().time(), storm);
            if (slot.isEmpty()) return;
            List<Integer> candidates = SchedulePlan.candidates(locations, slot.getAsInt(), storm);
            boolean changed = slot.getAsInt() != walker.slot || !candidates.contains(walker.location);
            boolean restless = walker.phase == Phase.AT && now >= walker.stayUntil && candidates.size() > 1;
            if (changed || restless || walker.phase == Phase.IDLE) {
                int choice = SchedulePlan.pick(locations, candidates, random);
                walker.slot = slot.getAsInt();
                if (choice != walker.location || walker.phase == Phase.IDLE) { depart(walker, choice, worldName, now); return; }
                walker.stayUntil = stay(locations.get(choice), now);
            }
            if (walker.phase == Phase.RETRY && now >= walker.retryAt) { depart(walker, walker.location, worldName, now); return; }
            if (walker.phase == Phase.AT) wander(walker, worldName, now);
        } catch (RuntimeException refused) {
            walker.phase = Phase.RETRY; walker.retryAt = now + RETRY_SECONDS;
            AnimusLog.shared().routine("NPC schedules refused a step", id, "NPC " + id + "'s schedule: " + reason(refused) + "; trying again in " + RETRY_SECONDS + " seconds", log);
        }
    }

    private void depart(Walker walker, int index, String worldName, long now) {
        String id = walker.definition.id();
        ScheduleSettings.Location location = walker.schedule.locations().get(index);
        walker.location = index;
        stand(walker);
        if (location.skinOnDeparture()) location.skin().ifPresent((String skin) -> world.skin(id, skin));
        leg(walker, new WorldPosition(worldName, SchedulePlan.goal(location)));
        walker.phase = Phase.TRAVELING;
    }

    private void wander(Walker walker, String worldName, long now) {
        ScheduleSettings.Location location = walker.schedule.locations().get(walker.location);
        if (location.wander().isEmpty() || now < walker.nextWander) return;
        stand(walker);
        leg(walker, new WorldPosition(worldName, SchedulePlan.wander(location, location.wander().get(), random)));
        walker.phase = Phase.WANDERING;
    }

    private void leg(Walker walker, WorldPosition goal) {
        String id = walker.definition.id();
        world.walk(id, goal, walker.speed);
        int leg = ++walker.leg;
        world.arrival(id).whenComplete((Void done, Throwable failure) -> {
            if (walker.leg == leg) walker.signal.set(failure == null ? ARRIVED : FAILED);
        });
    }

    /** The NPC stands up in its own pose before it moves. */
    private void stand(Walker walker) {
        if (!walker.posed) return;
        walker.posed = false;
        world.pose(walker.definition.id(), walker.declared);
    }

    private void arrived(Walker walker, String worldName, long now) {
        ScheduleSettings.Location location = walker.schedule.locations().get(walker.location);
        if (walker.phase == Phase.WANDERING) { walker.phase = Phase.AT; walker.nextWander = next(location, now); return; }
        if (walker.phase != Phase.TRAVELING) return;
        String id = walker.definition.id();
        if (location.yaw().isPresent()) {
            Vec3 goal = SchedulePlan.goal(location), eye = new Vec3(goal.x(), goal.y() + EYE, goal.z());
            world.look(id, new WorldPosition(worldName, SchedulePlan.facing(eye, location.yaw().getAsDouble(), location.pitch().orElse(0), LOOK_DISTANCE)));
        }
        if (location.clearEquipment()) for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (location.equipment().containsKey(slot)) continue;
            try { world.equip(id, slot, Optional.empty()); }
            catch (IllegalArgumentException bodyHasNoSlot) { /* Only the slots this body shows are cleared. */ }
        }
        for (Map.Entry<EquipmentSlot, NpcItem> item : location.equipment().entrySet()) world.equip(id, item.getKey(), Optional.of(item.getValue()));
        if (!location.skinOnDeparture()) location.skin().ifPresent((String skin) -> world.skin(id, skin));
        location.pose().ifPresent((NpcPose pose) -> {
            world.pose(id, pose);
            walker.posed = pose != walker.declared;
        });
        if (!location.commands().isEmpty()) {
            if (commandsAllowed(walker.definition)) location.commands().forEach(world::command);
            else AnimusLog.shared().routine("imported NPC schedules skipped arrival commands", id, "NPC " + id + " skipped the arrival commands of "
                    + location.label(walker.location) + ": imported commands run once " + IMPORTED_COMMANDS + " is in [behaviors.actions] commandNamespaces", log);
        }
        walker.phase = Phase.AT;
        walker.stayUntil = stay(location, now);
        walker.nextWander = next(location, now);
    }

    private void failed(Walker walker, long now) {
        if (walker.phase == Phase.WANDERING) {
            walker.phase = Phase.AT;
            walker.nextWander = next(walker.schedule.locations().get(walker.location), now);
            return;
        }
        if (walker.phase != Phase.TRAVELING) return;
        walker.phase = Phase.RETRY; walker.retryAt = now + RETRY_SECONDS;
        String id = walker.definition.id();
        AnimusLog.shared().routine("NPC schedules missed a location", id, "NPC " + id + " did not reach schedule location "
                + walker.schedule.locations().get(walker.location).label(walker.location) + "; trying again in " + RETRY_SECONDS + " seconds", log);
    }

    private boolean commandsAllowed(NpcDefinition definition) {
        return !definition.json().has("migration") || commandNamespaces.get().contains(IMPORTED_COMMANDS);
    }

    private long stay(ScheduleSettings.Location location, long now) {
        return location.stay().map((ScheduleSettings.Seconds seconds) -> now + seconds.draw(random)).orElse(Long.MAX_VALUE);
    }

    private long next(ScheduleSettings.Location location, long now) {
        return location.wander().map((ScheduleSettings.Wander wander) -> now + wander.pause().draw(random)).orElse(Long.MAX_VALUE);
    }

    private static String reason(RuntimeException failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank() ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
