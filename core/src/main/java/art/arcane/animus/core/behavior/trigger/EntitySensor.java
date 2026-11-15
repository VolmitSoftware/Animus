package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Sensed events of one NPC. NPC bodies are not entities, so the server never reports what real entities do to them: the
 * platform looks around each awake NPC every few ticks and hands over what it saw, and this finds who came near, touched
 * the body, dropped an item, ran into it with a vehicle or led a mob past. It also matches the event-driven ones (a
 * projectile, a leash tie, speech). Filters are tested here, while the platform's permission check is still valid.
 * One instance per NPC, used by one thread at a time. Not thread-safe.
 */
public final class EntitySensor {
    public enum Role { LIVING, ITEM, VEHICLE, OTHER }
    /** Someone tied to a seen entity: the player or mob riding it, the one who threw it or the one holding its lead. */
    public record Party(TriggerEvent.Subject subject, boolean player) {
        public Party { Objects.requireNonNull(subject); }
    }
    /** One real entity the platform saw near the NPC. {@code velocity} is per tick; {@code age} is ticks lived, which only items use. */
    public record Seen(TriggerEvent.Subject self, Role role, boolean player, Vec3 position, Hitbox bounds, Vec3 velocity,
                       Optional<Party> rider, Optional<Party> thrower, Optional<Party> holder, Optional<String> item, int amount, int age) {
        public Seen {
            Objects.requireNonNull(self); Objects.requireNonNull(role); Objects.requireNonNull(position); Objects.requireNonNull(bounds); Objects.requireNonNull(velocity);
            Objects.requireNonNull(rider); Objects.requireNonNull(thrower); Objects.requireNonNull(holder); Objects.requireNonNull(item);
        }
    }
    /** One binding this NPC serves: its {@code behavior#trigger} key and spec. */
    public record Watch(String key, TriggerBinding.EntityEvent spec) {
        public Watch { Objects.requireNonNull(key); Objects.requireNonNull(spec); }
    }
    /** Another active NPC near this one, with its definition id. */
    public record Neighbour(UUID uuid, String id, Vec3 position) {}
    /** {@code tracked} bounds the entities one NPC remembers; {@code intervalTicks} is how long ago the previous look was. */
    public record Limits(int tracked, int intervalTicks) {}
    /** A physical fact other plugins may hear about as an API event, independent of any trigger. */
    public record Fact(Kind kind, UUID entity, Map<String, Object> context) {
        public enum Kind { CONTACT, VEHICLE, ITEM, LEASH }
        public Fact { context = Map.copyOf(context); }
    }
    public record Result(List<TriggerEvent.Entity> events, List<Fact> facts) {
        public Result { events = List.copyOf(events); facts = List.copyOf(facts); }
    }

    /** Leaving a radius needs this much more distance, so an entity standing on it does not refire. */
    private static final double LEAVE_MARGIN = 0.5;
    /** A touch ends only once the entity is this far clear of the body. */
    private static final double CONTACT_RELEASE = 0.3;
    /** An entity whose feet are within this depth of the body's top stands on it. */
    private static final double TOP_DEPTH = 0.25;
    /** A vehicle slower than this, in blocks a tick, rests against the NPC rather than bumping it. */
    static final double BUMP_SPEED = 0.05;
    /** The longest sweep between two looks; anything longer was a teleport. */
    private static final double MAXIMUM_SWEEP = 32;
    /** Other plugins hear of items dropped and mobs led within this many blocks. */
    public static final double PLUGIN_RADIUS = 3;
    private static final String PLUGIN_ITEMS = "#items", PLUGIN_LEASH = "#leash";

    private final UUID npc;
    /** Entities inside each radius watch, keyed by watch key, with who they were when they came in. */
    private final Map<String, Map<UUID, Party>> inside = new HashMap<>();
    private final Set<UUID> touching = new HashSet<>(), bumping = new HashSet<>();
    private long overflow;

    public EntitySensor(UUID npc) { this.npc = Objects.requireNonNull(npc); }

    public long overflow() { return overflow; }
    public int tracked() {
        int total = touching.size() + bumping.size();
        for (Map<UUID, Party> entries : inside.values()) total += entries.size();
        return total;
    }

    /**
     * One look around. {@code attached} are the entities that move with the NPC, its own mount and the players riding or
     * seated on it: they still come near, but never touch, bump or are led past it. {@code complete} is false when the
     * platform cut the look short at its candidate budget, so an entity it did not report may still be near and is not
     * taken to have left. {@code holds} tests a permission node for a player.
     */
    public Result sense(VirtualNpcState.Snapshot body, List<Watch> watches, List<Seen> seen, Set<UUID> attached, boolean complete, List<Neighbour> neighbours,
                        BiPredicate<UUID, String> holds, Limits limits) {
        return look(body, watches, seen, attached, complete, neighbours, holds, limits, false);
    }
    /** {@link #sense}, also reporting the facts other plugins listen for. */
    public Result senseForPlugins(VirtualNpcState.Snapshot body, List<Watch> watches, List<Seen> seen, Set<UUID> attached, boolean complete, List<Neighbour> neighbours,
                                  BiPredicate<UUID, String> holds, Limits limits) {
        return look(body, watches, seen, attached, complete, neighbours, holds, limits, true);
    }

    private Result look(VirtualNpcState.Snapshot body, List<Watch> watches, List<Seen> seen, Set<UUID> attached, boolean complete, List<Neighbour> neighbours,
                        BiPredicate<UUID, String> holds, Limits limits, boolean plugins) {
        List<TriggerEvent.Entity> events = new ArrayList<>();
        List<Fact> facts = new ArrayList<>();
        Vec3 at = body.position().point();
        Hitbox hitbox = body.bounds();
        Map<UUID, Seen> present = new LinkedHashMap<>();
        for (Seen entity : seen) present.put(entity.self().id(), entity);
        Set<UUID> touchedNow = new HashSet<>(), heldNow = new HashSet<>(), bumpedNow = new HashSet<>();
        // Touches are remembered only for someone who asks, so they never use up room proximity needs.
        boolean touches = plugins || wants(watches, TriggerKind.ENTITY_COLLIDE), bumps = plugins || wants(watches, TriggerKind.VEHICLE_BUMP);
        if (!touches) touching.clear();
        if (!bumps) bumping.clear();
        for (Seen entity : touches || bumps ? present.values() : List.<Seen>of()) {
            if (entity.role() == Role.ITEM || attached.contains(entity.self().id())) continue;
            if (touches(hitbox, entity, limits.intervalTicks(), 0)) touchedNow.add(entity.self().id());
            else if (touching.contains(entity.self().id()) && touches(hitbox, entity, 0, CONTACT_RELEASE)) heldNow.add(entity.self().id());
            if (entity.role() == Role.VEHICLE && speed(entity) >= BUMP_SPEED && touchedNow.contains(entity.self().id())) bumpedNow.add(entity.self().id());
        }
        for (UUID id : touches ? touchedNow : Set.<UUID>of()) {
            if (touching.contains(id)) continue;
            if (!room(limits)) continue;
            touching.add(id);
            Seen entity = present.get(id);
            String contact = top(hitbox, entity) ? "top" : "side";
            if (plugins) facts.add(new Fact(Fact.Kind.CONTACT, id, Map.of("contact", contact)));
            for (Watch watch : watches) {
                if (watch.spec().kind() != TriggerKind.ENTITY_COLLIDE) continue;
                TriggerBinding.Contact wanted = watch.spec().contact();
                if (wanted == TriggerBinding.Contact.TOP && !contact.equals("top") || wanted == TriggerBinding.Contact.SIDE && !contact.equals("side")) continue;
                own(watch, entity, holds, Map.of("contact", contact), events);
            }
        }
        touching.removeIf((UUID id) -> !touchedNow.contains(id) && !heldNow.contains(id) && (complete || present.containsKey(id)));
        for (UUID id : bumps ? bumpedNow : Set.<UUID>of()) {
            if (bumping.contains(id) || !room(limits)) continue;
            bumping.add(id);
            Seen vehicle = present.get(id);
            Map<String, Object> extra = Map.of("speed", speed(vehicle), "rider", vehicle.rider().map((Party rider) -> rider.subject().id().toString()).orElse(""));
            if (plugins) facts.add(new Fact(Fact.Kind.VEHICLE, id, extra));
            for (Watch watch : watches) if (watch.spec().kind() == TriggerKind.VEHICLE_BUMP) matched(watch, Optional.of(vehicle.self()), party(vehicle.rider()), vehicle.position(), holds, extra, events);
        }
        bumping.removeIf((UUID id) -> !bumpedNow.contains(id) && (complete || present.containsKey(id)));
        for (Watch watch : watches) {
            switch (watch.spec().kind()) {
                case ENTITY_PROXIMITY -> radius(watch, at, present, complete, holds, limits, events);
                case ITEM_DROPPED -> items(watch.key(), watch.spec().radius(), at, hitbox, present, complete, limits,
                        (Seen item, boolean thrown) -> {
                            if (watch.spec().thrown() && !thrown || !watch.spec().passes("item", item.item().orElse(""))) return;
                            matched(watch, item.thrower().map(Party::subject), party(item.thrower()), item.position(), holds, itemContext(item, thrown), events);
                        });
                case LEASH_PASS -> leashed(watch.key(), watch.spec().radius(), at, present, attached, complete, limits,
                        (Seen mob) -> matched(watch, Optional.of(mob.self()), party(mob.holder()), mob.position(), holds, leashContext(mob.holder().orElseThrow()), events));
                case NPC_PROXIMITY -> neighbours(watch, at, neighbours, complete, limits, events);
                default -> {}
            }
        }
        if (plugins) {
            items(PLUGIN_ITEMS, PLUGIN_RADIUS, at, hitbox, present, complete, limits,
                    (Seen item, boolean thrown) -> facts.add(new Fact(Fact.Kind.ITEM, item.self().id(), itemContext(item, thrown))));
            leashed(PLUGIN_LEASH, PLUGIN_RADIUS, at, present, attached, complete, limits,
                    (Seen mob) -> facts.add(new Fact(Fact.Kind.LEASH, mob.self().id(), leashContext(mob.holder().orElseThrow()))));
        }
        Set<String> keys = new HashSet<>();
        for (Watch watch : watches) keys.add(watch.key());
        if (plugins) { keys.add(PLUGIN_ITEMS); keys.add(PLUGIN_LEASH); }
        inside.keySet().retainAll(keys);
        return new Result(events, facts);
    }

    private static boolean wants(List<Watch> watches, TriggerKind kind) {
        for (Watch watch : watches) if (watch.spec().kind() == kind) return true;
        return false;
    }
    private boolean room(Limits limits) {
        if (tracked() < limits.tracked()) return true;
        overflow++;
        return false;
    }

    /** entity_proximity: enter when within the radius, leave once half a block beyond it or no longer seen. */
    private void radius(Watch watch, Vec3 at, Map<UUID, Seen> present, boolean complete, BiPredicate<UUID, String> holds, Limits limits, List<TriggerEvent.Entity> events) {
        Map<UUID, Party> entries = inside.computeIfAbsent(watch.key(), (String key) -> new HashMap<>());
        TriggerBinding.Phase phase = watch.spec().phase().orElseThrow();
        double radius = watch.spec().radius();
        for (Iterator<Map.Entry<UUID, Party>> each = entries.entrySet().iterator(); each.hasNext();) {
            Map.Entry<UUID, Party> entry = each.next();
            Seen entity = present.get(entry.getKey());
            if (entity == null ? !complete : entity.position().distance(at) <= radius + LEAVE_MARGIN) continue;
            each.remove();
            // The filters held when it came in; a leave answers to that.
            Party who = entry.getValue();
            if (phase == TriggerBinding.Phase.LEAVE) events.add(unfiltered(npc, watch, Optional.of(who.subject()), party(Optional.of(who)),
                    entity == null ? at : entity.position(), Map.of("phase", "leave")));
        }
        for (Seen entity : present.values()) {
            UUID id = entity.self().id();
            if (entity.role() == Role.ITEM || entries.containsKey(id) || entity.position().distance(at) > radius) continue;
            if (!watch.spec().filter().accepts(Optional.of(entity.self()), self(entity), (String node) -> entity.player() && holds.test(id, node))) continue;
            if (!room(limits)) continue;
            entries.put(id, new Party(entity.self(), entity.player()));
            if (phase == TriggerBinding.Phase.ENTER) events.add(unfiltered(npc, watch, Optional.of(entity.self()), self(entity), entity.position(), Map.of("phase", "enter")));
        }
    }

    @FunctionalInterface private interface ItemSink { void dropped(Seen item, boolean thrown); }
    /**
     * A fresh item a player or mob threw, within the radius or flying into the body, once. An item lying there before the
     * NPC looked never counts, nor one nobody threw: loot, block drops and the fake item /give shows for its pickup.
     */
    private void items(String key, double radius, Vec3 at, Hitbox hitbox, Map<UUID, Seen> present, boolean complete, Limits limits, ItemSink sink) {
        Map<UUID, Party> entries = inside.computeIfAbsent(key, (String ignored) -> new HashMap<>());
        if (complete) entries.keySet().retainAll(present.keySet());
        int fresh = limits.intervalTicks() * 2 + 20;
        for (Seen item : present.values()) {
            if (item.role() != Role.ITEM || item.thrower().isEmpty() || entries.containsKey(item.self().id())) continue;
            boolean thrown = touches(hitbox, item, limits.intervalTicks(), 0);
            if (!thrown && item.position().distance(at) > radius) continue;
            if (!room(limits)) continue;
            entries.put(item.self().id(), new Party(item.self(), false));
            if (item.age() <= fresh) sink.dropped(item, thrown);
        }
    }

    /** A mob on a lead within the radius, once until it is led away again. */
    private void leashed(String key, double radius, Vec3 at, Map<UUID, Seen> present, Set<UUID> attached, boolean complete, Limits limits, LeashSink sink) {
        Map<UUID, Party> entries = inside.computeIfAbsent(key, (String ignored) -> new HashMap<>());
        entries.keySet().removeIf((UUID id) -> {
            Seen mob = present.get(id);
            return mob == null ? complete : mob.holder().isEmpty() || mob.position().distance(at) > radius + LEAVE_MARGIN;
        });
        for (Seen mob : present.values()) {
            if (mob.holder().isEmpty() || mob.role() == Role.ITEM || attached.contains(mob.self().id()) || entries.containsKey(mob.self().id()) || mob.position().distance(at) > radius) continue;
            if (!room(limits)) continue;
            entries.put(mob.self().id(), new Party(mob.self(), false));
            sink.led(mob);
        }
    }
    @FunctionalInterface private interface LeashSink { void led(Seen mob); }

    /** npc_proximity over the NPCs the spatial index holds near this one; an NPC never meets itself. */
    private void neighbours(Watch watch, Vec3 at, List<Neighbour> neighbours, boolean complete, Limits limits, List<TriggerEvent.Entity> events) {
        Map<UUID, Party> entries = inside.computeIfAbsent(watch.key(), (String key) -> new HashMap<>());
        TriggerBinding.Phase phase = watch.spec().phase().orElseThrow();
        double radius = watch.spec().radius();
        Map<UUID, Neighbour> near = new HashMap<>();
        for (Neighbour other : neighbours) if (!other.uuid().equals(npc)) near.put(other.uuid(), other);
        for (Iterator<Map.Entry<UUID, Party>> each = entries.entrySet().iterator(); each.hasNext();) {
            Map.Entry<UUID, Party> entry = each.next();
            Neighbour other = near.get(entry.getKey());
            if (other == null ? !complete : other.position().distance(at) <= radius + LEAVE_MARGIN) continue;
            each.remove();
            if (phase == TriggerBinding.Phase.LEAVE) events.add(npcEvent(watch, entry.getKey(), entry.getValue().subject().name(), other == null ? at : other.position(), "leave"));
        }
        for (Neighbour other : near.values()) {
            if (entries.containsKey(other.uuid()) || other.position().distance(at) > radius || !watch.spec().passes("npc", other.id())) continue;
            if (!room(limits)) continue;
            entries.put(other.uuid(), new Party(npcSubject(other.uuid(), other.id()), false));
            if (phase == TriggerBinding.Phase.ENTER) events.add(npcEvent(watch, other.uuid(), other.id(), other.position(), "enter"));
        }
    }
    private TriggerEvent.Entity npcEvent(Watch watch, UUID other, String id, Vec3 where, String phase) {
        Map<String, Object> context = place(where);
        context.put("npc", id); context.put("npc_uuid", other.toString()); context.put("phase", phase);
        return new TriggerEvent.Entity(npc, watch.spec().kind(), watch.key(), Optional.of(npcSubject(other, id)), Optional.empty(), context);
    }
    private static TriggerEvent.Subject npcSubject(UUID other, String id) { return new TriggerEvent.Subject(other, "animus:npc", id, Set.of()); }

    /** The entity is the subject and, when a player, the player. */
    private void own(Watch watch, Seen entity, BiPredicate<UUID, String> holds, Map<String, Object> extra, List<TriggerEvent.Entity> events) {
        matched(watch, Optional.of(entity.self()), self(entity), entity.position(), holds, extra, events);
    }
    private static Optional<UUID> self(Seen entity) { return entity.player() ? Optional.of(entity.self().id()) : Optional.empty(); }
    private static Optional<UUID> party(Optional<Party> party) { return party.filter(Party::player).map((Party who) -> who.subject().id()); }

    private void matched(Watch watch, Optional<TriggerEvent.Subject> subject, Optional<UUID> player, Vec3 where, BiPredicate<UUID, String> holds,
                         Map<String, Object> extra, List<TriggerEvent.Entity> events) {
        TriggerEvent.Entity event = event(npc, watch, subject, player, where, holds, extra);
        if (event != null) events.add(event);
    }
    /** The event for one watch, or null when its filters refuse it. */
    private static TriggerEvent.Entity event(UUID npc, Watch watch, Optional<TriggerEvent.Subject> subject, Optional<UUID> player, Vec3 where,
                                             BiPredicate<UUID, String> holds, Map<String, Object> extra) {
        if (!watch.spec().filter().accepts(subject, player, (String node) -> player.isPresent() && holds.test(player.get(), node))) return null;
        return unfiltered(npc, watch, subject, player, where, extra);
    }
    private static TriggerEvent.Entity unfiltered(UUID npc, Watch watch, Optional<TriggerEvent.Subject> subject, Optional<UUID> player, Vec3 where, Map<String, Object> extra) {
        Map<String, Object> context = place(where);
        context.put("entity", subject.map((TriggerEvent.Subject who) -> who.id().toString()).orElse(""));
        context.put("entity_type", subject.map(TriggerEvent.Subject::type).orElse(""));
        context.put("entity_name", subject.map(TriggerEvent.Subject::name).orElse(""));
        context.putAll(extra);
        return new TriggerEvent.Entity(npc, watch.spec().kind(), watch.key(), subject, player, context);
    }
    private static Map<String, Object> place(Vec3 where) {
        Map<String, Object> context = new HashMap<>();
        context.put("x", where.x()); context.put("y", where.y()); context.put("z", where.z());
        return context;
    }
    private static Map<String, Object> itemContext(Seen item, boolean thrown) {
        return Map.of("item", item.item().orElse(""), "amount", (long) item.amount(), "item_entity", item.self().id().toString(), "thrown", thrown);
    }
    private static Map<String, Object> leashContext(Party holder) { return Map.of("holder", holder.subject().id().toString(), "holder_type", holder.subject().type()); }

    /** A projectile crossed this NPC's hitbox at {@code point}; {@code stopped} when combat let it strike the body. */
    public static List<TriggerEvent.Entity> projectile(UUID npc, List<Watch> watches, Optional<Party> shooter, String projectile, UUID projectileId, Vec3 point,
                                                       boolean stopped, BiPredicate<UUID, String> holds) {
        List<TriggerEvent.Entity> events = new ArrayList<>();
        for (Watch watch : watches) {
            if (watch.spec().kind() != TriggerKind.HIT_BY_PROJECTILE || !watch.spec().passes("projectile", projectile)) continue;
            TriggerEvent.Entity event = event(npc, watch, shooter.map(Party::subject), party(shooter), point, holds,
                    Map.of("projectile", projectile, "projectile_uuid", projectileId.toString(), "stopped", stopped));
            if (event != null) events.add(event);
        }
        return List.copyOf(events);
    }
    /** A player leading {@code mob} right-clicked the NPC. */
    public static List<TriggerEvent.Entity> leashTie(UUID npc, List<Watch> watches, TriggerEvent.Subject mob, Vec3 at, Party holder, BiPredicate<UUID, String> holds) {
        List<TriggerEvent.Entity> events = new ArrayList<>();
        for (Watch watch : watches) {
            if (watch.spec().kind() != TriggerKind.LEASH_TIE) continue;
            TriggerEvent.Entity event = event(npc, watch, Optional.of(mob), party(Optional.of(holder)), at, holds, leashContext(holder));
            if (event != null) events.add(event);
        }
        return List.copyOf(events);
    }
    /** Another NPC said {@code text} {@code distance} blocks from this one. */
    public static List<TriggerEvent.Entity> speech(UUID npc, List<Watch> watches, UUID speaker, String speakerId, String text, double distance) {
        if (speaker.equals(npc)) return List.of();
        List<TriggerEvent.Entity> events = new ArrayList<>();
        for (Watch watch : watches) {
            if (watch.spec().kind() != TriggerKind.NPC_SPEECH || distance > watch.spec().radius() || !watch.spec().passes("npc", speakerId) || !watch.spec().passes("pattern", text)) continue;
            Map<String, Object> context = new HashMap<>();
            context.put("npc", speakerId); context.put("npc_uuid", speaker.toString()); context.put("text", text);
            events.add(new TriggerEvent.Entity(npc, TriggerKind.NPC_SPEECH, watch.key(), Optional.of(npcSubject(speaker, speakerId)), Optional.empty(), context));
        }
        return List.copyOf(events);
    }

    /** The entity overlaps the body, grown by {@code margin}, or swept through it since the look {@code sweepTicks} ago. */
    private static boolean touches(Hitbox hitbox, Seen entity, int sweepTicks, double margin) {
        Hitbox grown = margin > 0 ? hitbox.inflated(margin) : hitbox;
        if (entity.bounds().touches(grown)) return true;
        if (sweepTicks <= 0) return false;
        Vec3 travelled = entity.velocity().multiply(sweepTicks);
        double length = Math.hypot(Math.hypot(travelled.x(), travelled.y()), travelled.z());
        if (length < 1e-4 || length > MAXIMUM_SWEEP) return false;
        Vec3 now = entity.bounds().center();
        double half = Math.max(entity.bounds().max().x() - entity.bounds().min().x(), entity.bounds().max().z() - entity.bounds().min().z()) / 2;
        return grown.inflated(half).rayDistance(now.subtract(travelled), travelled, length).isPresent();
    }
    private static boolean top(Hitbox hitbox, Seen entity) { return entity.bounds().min().y() >= hitbox.max().y() - TOP_DEPTH; }
    /** Across the ground: an entity standing still still falls a little every tick. */
    private static double speed(Seen entity) { return Math.hypot(entity.velocity().x(), entity.velocity().z()); }
}
