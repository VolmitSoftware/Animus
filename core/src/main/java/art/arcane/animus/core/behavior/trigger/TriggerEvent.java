package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Capabilities;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable platform-independent event. Publishing grants no world authority: a resulting execution
 * only carries the NPC handle and the optional player UUID into its capabilities.
 */
public sealed interface TriggerEvent {
    /** A click on the NPC, or the drop or swap-hands key pressed while looking at it. */
    enum Action {
        INTERACT, ATTACK, DROP, SWAP_HANDS;
        /** A key press rather than a hit: there is a vanilla drop or swap to cancel. */
        public boolean key() { return this == DROP || this == SWAP_HANDS; }
        /** The name runs read as {@code context.action}. */
        public String jsonName() { return name().toLowerCase(Locale.ROOT); }
    }
    enum Hand { MAIN_HAND, OFF_HAND }
    UUID npc();
    TriggerKind kind();
    default Optional<UUID> player() { return Optional.empty(); }
    /** What a run reads as {@code context}. */
    default Map<String, Object> context() { return Map.of(); }

    /** Who hurt an NPC: {@code kind} is {@code player}, {@code mob} or {@code npc}. */
    record Attacker(String kind, UUID id) {
        public Attacker { Objects.requireNonNull(kind); Objects.requireNonNull(id); }
        Optional<UUID> player() { return kind.equals("player") ? Optional.of(id) : Optional.empty(); }
    }

    /** {@code sneaking} is the client's secondary-action flag on a right click, and the player's sneaking state for other actions. */
    record Interaction(UUID npc, UUID viewer, Action action, boolean sneaking, Hand hand) implements TriggerEvent {
        public Interaction { Objects.requireNonNull(npc); Objects.requireNonNull(viewer); Objects.requireNonNull(action); Objects.requireNonNull(hand); }
        /** A main-hand click without sneaking. */
        public Interaction(UUID npc, UUID viewer, Action action) { this(npc, viewer, action, false, Hand.MAIN_HAND); }
        @Override public TriggerKind kind() { return TriggerKind.INTERACTION; }
        @Override public Optional<UUID> player() { return Optional.of(viewer); }
        /** What a run and a consume condition read as {@code context.action}, {@code context.sneaking} and {@code context.hand} ({@code main_hand} or {@code off_hand}). */
        @Override public Map<String, Object> context() { return Map.of("action", action.jsonName(), "sneaking", sneaking, "hand", hand.name().toLowerCase(Locale.ROOT)); }
        /** The platform's hand name: {@code OFF_HAND} is the off hand, anything else the main hand. */
        public static Hand hand(String name) { return "OFF_HAND".equals(name) ? Hand.OFF_HAND : Hand.MAIN_HAND; }
    }
    /** Names the single binding whose radius was crossed; phase and radius belong to that binding. */
    record Proximity(UUID npc, UUID viewer, String binding) implements TriggerEvent {
        public Proximity { Objects.requireNonNull(npc); Objects.requireNonNull(viewer); Objects.requireNonNull(binding); }
        @Override public TriggerKind kind() { return TriggerKind.PROXIMITY; }
        @Override public Optional<UUID> player() { return Optional.of(viewer); }
    }
    record Spawn(UUID npc) implements TriggerEvent {
        public Spawn { Objects.requireNonNull(npc); }
        @Override public TriggerKind kind() { return TriggerKind.SPAWN; }
    }
    record Despawn(UUID npc) implements TriggerEvent {
        public Despawn { Objects.requireNonNull(npc); }
        @Override public TriggerKind kind() { return TriggerKind.DESPAWN; }
    }
    /** A player attacker is the run's player; runs read {@code context.damage}, {@code context.attacker} and {@code context.attacker_type}. */
    record Damage(UUID npc, double amount, Optional<Attacker> attacker) implements TriggerEvent {
        public Damage { Objects.requireNonNull(npc); Objects.requireNonNull(attacker); }
        @Override public TriggerKind kind() { return TriggerKind.DAMAGE; }
        @Override public Optional<UUID> player() { return attacker.flatMap(Attacker::player); }
        @Override public Map<String, Object> context() {
            return Map.of("damage", amount, "attacker", attacker.map((Attacker who) -> who.id().toString()).orElse(""), "attacker_type", attacker.map(Attacker::kind).orElse(""));
        }
    }
    /** A player killer is the run's player; runs read {@code context.killer} and {@code context.killer_type}. */
    record Death(UUID npc, Optional<Attacker> killer) implements TriggerEvent {
        public Death { Objects.requireNonNull(npc); Objects.requireNonNull(killer); }
        @Override public TriggerKind kind() { return TriggerKind.DEATH; }
        @Override public Optional<UUID> player() { return killer.flatMap(Attacker::player); }
        @Override public Map<String, Object> context() {
            return Map.of("killer", killer.map((Attacker who) -> who.id().toString()).orElse(""), "killer_type", killer.map(Attacker::kind).orElse(""));
        }
    }
    /** waypoint is the route index, or -1 for a manually requested destination. */
    record Waypoint(UUID npc, int waypoint) implements TriggerEvent {
        public Waypoint { Objects.requireNonNull(npc); }
        @Override public TriggerKind kind() { return TriggerKind.WAYPOINT; }
    }
    /** Published after a viewer's click passes validation. */
    record DialogueChoice(UUID npc, UUID viewer, String dialogue, String choice) implements TriggerEvent {
        public DialogueChoice { Objects.requireNonNull(npc); Objects.requireNonNull(viewer); Objects.requireNonNull(dialogue); Objects.requireNonNull(choice); }
        @Override public TriggerKind kind() { return TriggerKind.DIALOGUE_CHOICE; }
        @Override public Optional<UUID> player() { return Optional.of(viewer); }
    }
    /** Published to every NPC on a build's team. */
    record BuildFinished(UUID npc) implements TriggerEvent {
        public BuildFinished { Objects.requireNonNull(npc); }
        @Override public TriggerKind kind() { return TriggerKind.BUILD_FINISHED; }
    }
    record BuildPaused(UUID npc) implements TriggerEvent {
        public BuildPaused { Objects.requireNonNull(npc); }
        @Override public TriggerKind kind() { return TriggerKind.BUILD_PAUSED; }
    }
    /** A played clip ended on a layer; looping clips never finish. */
    record AnimationFinished(UUID npc, String animation, int layer) implements TriggerEvent {
        public AnimationFinished { Objects.requireNonNull(npc); Objects.requireNonNull(animation); }
        @Override public TriggerKind kind() { return TriggerKind.ANIMATION_FINISHED; }
    }
    /** The entity a sensed event is about: its UUID, type key such as {@code minecraft:sheep}, custom or player name, and scoreboard tags. */
    record Subject(UUID id, String type, String name, java.util.Set<String> tags) {
        public Subject { Objects.requireNonNull(id); Objects.requireNonNull(type); Objects.requireNonNull(name); tags = java.util.Set.copyOf(tags); }
    }
    /**
     * A sensed event for one binding, already matched against that binding's filters by the sensor. {@code subject} is the
     * entity the filters tested and {@code player} the player involved, which is the run's player.
     */
    record Entity(UUID npc, TriggerKind kind, String binding, Optional<Subject> subject, Optional<UUID> player, Map<String, Object> context) implements TriggerEvent {
        public Entity {
            Objects.requireNonNull(npc); Objects.requireNonNull(kind); Objects.requireNonNull(binding); Objects.requireNonNull(subject); Objects.requireNonNull(player);
            context = readable(context);
        }
        /** A run reads only booleans, longs, doubles and strings; one value of any other type would stop the whole dispatcher. */
        private static Map<String, Object> readable(Map<String, Object> context) {
            Map<String, Object> values = new java.util.HashMap<>();
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                Object value = switch (entry.getValue()) {
                    case Integer whole -> whole.longValue();
                    case Float decimal -> decimal.doubleValue();
                    case Boolean flag -> flag;
                    case Long whole -> whole;
                    case Double decimal -> decimal;
                    case String text -> text;
                    default -> throw new IllegalArgumentException("A run cannot read context." + entry.getKey());
                };
                values.put(entry.getKey(), value);
            }
            return Map.copyOf(values);
        }
    }
    record Module(UUID npc, Optional<UUID> player, Capabilities.Key key) implements TriggerEvent {
        public Module { Objects.requireNonNull(npc); Objects.requireNonNull(player); Objects.requireNonNull(key); }
        @Override public TriggerKind kind() { return TriggerKind.MODULE; }
    }
}
