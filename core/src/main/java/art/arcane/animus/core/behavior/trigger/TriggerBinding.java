package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.behavior.BehaviorExpression;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** One compiled entry of a behavior document's {@code triggers} array. */
public record TriggerBinding(String id, Spec spec, Optional<String> routine, OptionalInt cooldownTicks) {
    /**
     * The inputs an interaction trigger takes. {@code any} takes either click; the plain names take their input with or
     * without sneaking, the {@code sneak_} names only while sneaking.
     */
    public enum InteractionFilter {
        INTERACT(TriggerEvent.Action.INTERACT, false), ATTACK(TriggerEvent.Action.ATTACK, false), ANY(null, false),
        SNEAK_INTERACT(TriggerEvent.Action.INTERACT, true), SNEAK_ATTACK(TriggerEvent.Action.ATTACK, true),
        DROP(TriggerEvent.Action.DROP, false), SNEAK_DROP(TriggerEvent.Action.DROP, true),
        SWAP_HANDS(TriggerEvent.Action.SWAP_HANDS, false), SNEAK_SWAP_HANDS(TriggerEvent.Action.SWAP_HANDS, true);
        private final TriggerEvent.Action action;
        private final boolean sneaking;
        InteractionFilter(TriggerEvent.Action action, boolean sneaking) { this.action = action; this.sneaking = sneaking; }
        boolean accepts(TriggerEvent.Interaction input) {
            if (sneaking && !input.sneaking()) return false;
            return action == null ? !input.action().key() : action == input.action();
        }
    }
    public enum Phase { ENTER, LEAVE }

    public TriggerBinding {
        Objects.requireNonNull(id); Objects.requireNonNull(spec); Objects.requireNonNull(routine); Objects.requireNonNull(cooldownTicks);
    }

    public sealed interface Spec { TriggerKind kind(); }
    /**
     * {@code consume} is checked as the input arrives: when it holds, the input is this behavior's and its default handling
     * is skipped, Animus's for a click and the vanilla drop or swap for a key press. A key press without it is consumed.
     */
    /**
     * {@code playerCooldownTicks} spaces one player's runs of this trigger; empty uses the configured default unless
     * {@code cooldownTicks} is set. {@code queue} lets a player's input wait while their earlier run of this trigger is
     * queued or running; by default it is dropped.
     */
    public record Interaction(InteractionFilter action, Optional<BehaviorExpression> consume, OptionalInt playerCooldownTicks, boolean queue) implements Spec {
        public Interaction { Objects.requireNonNull(action); Objects.requireNonNull(consume); Objects.requireNonNull(playerCooldownTicks); }
        public Interaction(InteractionFilter action) { this(action, Optional.empty(), OptionalInt.empty(), false); }
        @Override public TriggerKind kind() { return TriggerKind.INTERACTION; }
        boolean accepts(TriggerEvent.Interaction input) { return action.accepts(input); }
    }
    /** {@code playerCooldownTicks} spaces one player's runs of this trigger; empty leaves the trigger's own cooldown. */
    public record Proximity(Phase phase, double radius, OptionalInt playerCooldownTicks) implements Spec {
        public Proximity { Objects.requireNonNull(playerCooldownTicks); }
        public Proximity(Phase phase, double radius) { this(phase, radius, OptionalInt.empty()); }
        @Override public TriggerKind kind() { return TriggerKind.PROXIMITY; }
    }
    /** Events with no further filter: spawn, despawn, death, build, player quit, death and respawn, server start. */
    public record Lifecycle(TriggerKind kind) implements Spec {}
    /** {@code players} takes only hits a player dealt (true), which then carry that player, or only hits no player dealt (false); empty takes every hit. */
    public record Damage(Optional<Boolean> players) implements Spec {
        public Damage { Objects.requireNonNull(players); }
        @Override public TriggerKind kind() { return TriggerKind.DAMAGE; }
        boolean accepts(TriggerEvent.Damage hit) { return players.isEmpty() || players.get() == hit.player().isPresent(); }
    }
    public record Waypoint(OptionalInt waypoint) implements Spec {
        @Override public TriggerKind kind() { return TriggerKind.WAYPOINT; }
    }
    public record Timer(int intervalTicks) implements Spec {
        @Override public TriggerKind kind() { return TriggerKind.TIMER; }
    }
    public record DialogueChoice(String dialogue, Optional<String> choice) implements Spec {
        @Override public TriggerKind kind() { return TriggerKind.DIALOGUE_CHOICE; }
    }
    /** An empty animation matches every clip. */
    public record AnimationFinished(Optional<String> animation) implements Spec {
        @Override public TriggerKind kind() { return TriggerKind.ANIMATION_FINISHED; }
    }
    public record ModuleEvent(Capabilities.Key key) implements Spec {
        @Override public TriggerKind kind() { return TriggerKind.MODULE; }
    }
    /** An empty first matches every join. */
    public record PlayerJoin(Optional<Boolean> first) implements Spec {
        public PlayerJoin { Objects.requireNonNull(first); }
        @Override public TriggerKind kind() { return TriggerKind.PLAYER_JOIN; }
    }
    /** An empty pattern matches every message. */
    public record PlayerChat(Optional<String> pattern) implements Spec {
        public PlayerChat { Objects.requireNonNull(pattern); }
        @Override public TriggerKind kind() { return TriggerKind.PLAYER_CHAT; }
    }
    public record Command(String name, List<String> aliases, Optional<String> permission, Optional<String> description, Optional<String> usage) implements Spec {
        public Command { Objects.requireNonNull(name); aliases = List.copyOf(aliases); Objects.requireNonNull(permission); Objects.requireNonNull(description); Objects.requireNonNull(usage); }
        @Override public TriggerKind kind() { return TriggerKind.COMMAND; }
    }
    /** An empty world matches every world. */
    public record WorldChange(Optional<String> from, Optional<String> to) implements Spec {
        public WorldChange { Objects.requireNonNull(from); Objects.requireNonNull(to); }
        @Override public TriggerKind kind() { return TriggerKind.PLAYER_WORLD_CHANGE; }
    }

    /**
     * A world event of a server behavior. {@code filters} holds trigger field to pattern; each pattern must match the
     * context value {@link TriggerKind#FILTERS} names, case-insensitively with {@code *} wildcards, and a pattern without
     * a colon matches the part of a namespaced key after it. {@code cancel}, when present, is checked as the event happens:
     * when it holds, the platform cancels the event.
     */
    public record WorldEvent(TriggerKind kind, java.util.Map<String, String> filters, Optional<BehaviorExpression> cancel) implements Spec {
        public WorldEvent { Objects.requireNonNull(kind); filters = java.util.Map.copyOf(filters); Objects.requireNonNull(cancel); }
    }
    /** Where an entity touches an NPC: {@code top} stands on its hitbox, {@code side} is any other touch. */
    public enum Contact { ANY, TOP, SIDE }
    /**
     * Who an entity trigger answers to. {@code entity}, {@code named} and {@code tag} test the event's entity: its type
     * key, its custom or player name, and its scoreboard tags. {@code players} needs a player in the event (true) or none
     * (false), and {@code permission} a player holding the node. Empty fields match everything.
     */
    public record EntityFilter(Optional<String> entity, Optional<String> named, Optional<String> tag, Optional<Boolean> players,
                               Optional<String> permission, OptionalInt entityCooldownTicks) {
        public static final EntityFilter ANY = new EntityFilter(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), OptionalInt.empty());
        public EntityFilter {
            Objects.requireNonNull(entity); Objects.requireNonNull(named); Objects.requireNonNull(tag); Objects.requireNonNull(players);
            Objects.requireNonNull(permission); Objects.requireNonNull(entityCooldownTicks);
        }
        /** {@code holds} answers whether the event's player holds a permission node; it is only asked when there is a player. */
        public boolean accepts(Optional<TriggerEvent.Subject> subject, Optional<java.util.UUID> player, java.util.function.Predicate<String> holds) {
            if (entity.isPresent() && (subject.isEmpty() || !ServerEvent.key(entity.get(), subject.get().type()))) return false;
            if (named.isPresent() && (subject.isEmpty() || !ServerEvent.glob(named.get(), subject.get().name()))) return false;
            if (tag.isPresent() && (subject.isEmpty() || !subject.get().tags().contains(tag.get()))) return false;
            if (players.isPresent() && players.get() != player.isPresent()) return false;
            return permission.isEmpty() || player.isPresent() && holds.test(permission.get());
        }
    }
    /**
     * A sensed event about real entities or other NPCs near the NPC. {@code radius} is 0 for events without one;
     * {@code patterns} holds the event's own filters ({@code projectile}, {@code item}, {@code npc}, {@code pattern}) keyed by
     * field, matched against the context value of the same meaning.
     */
    public record EntityEvent(TriggerKind kind, EntityFilter filter, Optional<Phase> phase, double radius, Contact contact,
                              java.util.Map<String, String> patterns, boolean thrown) implements Spec {
        public EntityEvent {
            Objects.requireNonNull(kind); Objects.requireNonNull(filter); Objects.requireNonNull(phase); Objects.requireNonNull(contact);
            patterns = java.util.Map.copyOf(patterns);
            if (!kind.sensed()) throw new IllegalArgumentException(kind.jsonName() + " is not a sensed event");
        }
        public EntityEvent(TriggerKind kind, EntityFilter filter) { this(kind, filter, Optional.empty(), 0, Contact.ANY, java.util.Map.of(), false); }
        /** Every firing has a player: a lead tie always does, and the filters can demand one. */
        public boolean alwaysPlayer() {
            return kind == TriggerKind.LEASH_TIE || filter.players().orElse(false) || filter.permission().isPresent();
        }
        /** Whether a context value passes this event's own filter for {@code field}; an absent filter passes everything. */
        public boolean passes(String field, String value) {
            String pattern = patterns.get(field);
            if (pattern == null) return true;
            return field.equals("pattern") ? ServerEvent.glob(pattern, value) : ServerEvent.key(pattern, value);
        }
    }
    public enum Shape { CUBOID, ELLIPSOID }
    /**
     * A named area of one world. A cuboid's bounds are block coordinates, both corners included; an ellipsoid is a centre
     * and three radii.
     */
    public record Area(Phase phase, String area, String world, Shape shape, double[] bounds) implements Spec {
        public Area { Objects.requireNonNull(phase); Objects.requireNonNull(area); Objects.requireNonNull(world); Objects.requireNonNull(shape); bounds = bounds.clone(); }
        @Override public TriggerKind kind() { return TriggerKind.PLAYER_AREA; }
        @Override public double[] bounds() { return bounds.clone(); }
        /** Whether the point lies in the area. */
        public boolean contains(double x, double y, double z) {
            if (shape == Shape.CUBOID) return x >= bounds[0] && x < bounds[3] + 1 && y >= bounds[1] && y < bounds[4] + 1 && z >= bounds[2] && z < bounds[5] + 1;
            double dx = (x - bounds[0]) / bounds[3], dy = (y - bounds[1]) / bounds[4], dz = (z - bounds[2]) / bounds[5];
            return dx * dx + dy * dy + dz * dz <= 1;
        }
        /** The area's identity across reloads: the same name, world and bounds are the same area. */
        public String key() { return area + "@" + world + "/" + shape + java.util.Arrays.toString(bounds); }
        @Override public boolean equals(Object other) {
            return other instanceof Area that && phase == that.phase && area.equals(that.area) && world.equals(that.world) && shape == that.shape && java.util.Arrays.equals(bounds, that.bounds);
        }
        @Override public int hashCode() { return Objects.hash(phase, area, world, shape, java.util.Arrays.hashCode(bounds)); }
    }

    /** key is the {@code behavior#trigger} name proximity events use to select one binding. */
    boolean matches(TriggerEvent event, String key) {
        return switch (spec) {
            case Interaction interaction -> event instanceof TriggerEvent.Interaction input && interaction.accepts(input);
            case Proximity ignored -> event instanceof TriggerEvent.Proximity input && input.binding().equals(key);
            case Lifecycle lifecycle -> event.kind() == lifecycle.kind();
            case Damage damage -> event instanceof TriggerEvent.Damage hit && damage.accepts(hit);
            case Waypoint waypoint -> event instanceof TriggerEvent.Waypoint input
                    && (waypoint.waypoint().isEmpty() || waypoint.waypoint().getAsInt() == input.waypoint());
            case Timer ignored -> false;
            case DialogueChoice dialogue -> event instanceof TriggerEvent.DialogueChoice input && dialogue.dialogue().equals(input.dialogue())
                    && (dialogue.choice().isEmpty() || dialogue.choice().get().equals(input.choice()));
            case ModuleEvent module -> event instanceof TriggerEvent.Module input && module.key().equals(input.key());
            case AnimationFinished finished -> event instanceof TriggerEvent.AnimationFinished input
                    && (finished.animation().isEmpty() || finished.animation().get().equals(input.animation()));
            case PlayerJoin ignored -> false;
            case PlayerChat ignored -> false;
            case Command ignored -> false;
            case WorldChange ignored -> false;
            case WorldEvent ignored -> false;
            case Area ignored -> false;
            case EntityEvent ignored -> event instanceof TriggerEvent.Entity input && input.binding().equals(key);
        };
    }
}
