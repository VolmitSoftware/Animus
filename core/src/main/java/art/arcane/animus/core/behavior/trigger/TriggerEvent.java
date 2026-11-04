package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Capabilities;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable platform-independent event. Publishing grants no world authority: a resulting execution
 * only carries the NPC handle and the optional player UUID into its capabilities.
 */
public sealed interface TriggerEvent {
    enum Action { INTERACT, ATTACK }
    UUID npc();
    TriggerKind kind();
    default Optional<UUID> player() { return Optional.empty(); }

    record Interaction(UUID npc, UUID viewer, Action action) implements TriggerEvent {
        public Interaction { Objects.requireNonNull(npc); Objects.requireNonNull(viewer); Objects.requireNonNull(action); }
        @Override public TriggerKind kind() { return TriggerKind.INTERACTION; }
        @Override public Optional<UUID> player() { return Optional.of(viewer); }
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
    record Damage(UUID npc, double amount) implements TriggerEvent {
        public Damage { Objects.requireNonNull(npc); }
        @Override public TriggerKind kind() { return TriggerKind.DAMAGE; }
    }
    record Death(UUID npc) implements TriggerEvent {
        public Death { Objects.requireNonNull(npc); }
        @Override public TriggerKind kind() { return TriggerKind.DEATH; }
    }
    /** waypoint is the route index, or -1 for a manually requested destination. */
    record Waypoint(UUID npc, int waypoint) implements TriggerEvent {
        public Waypoint { Objects.requireNonNull(npc); }
        @Override public TriggerKind kind() { return TriggerKind.WAYPOINT; }
    }
    /** Contract for whatever presents dialogue: publish this after a viewer picks a choice. */
    record DialogueChoice(UUID npc, UUID viewer, String dialogue, String choice) implements TriggerEvent {
        public DialogueChoice { Objects.requireNonNull(npc); Objects.requireNonNull(viewer); Objects.requireNonNull(dialogue); Objects.requireNonNull(choice); }
        @Override public TriggerKind kind() { return TriggerKind.DIALOGUE_CHOICE; }
    }
    record Module(UUID npc, Optional<UUID> player, Capabilities.Key key) implements TriggerEvent {
        public Module { Objects.requireNonNull(npc); Objects.requireNonNull(player); Objects.requireNonNull(key); }
        @Override public TriggerKind kind() { return TriggerKind.MODULE; }
    }
}
