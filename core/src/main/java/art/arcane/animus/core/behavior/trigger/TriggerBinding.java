package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Capabilities;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** One compiled entry of a behavior document's {@code triggers} array. */
public record TriggerBinding(String id, Spec spec, Optional<String> routine, OptionalInt cooldownTicks) {
    public enum InteractionFilter { INTERACT, ATTACK, ANY }
    public enum Phase { ENTER, LEAVE }

    public TriggerBinding {
        Objects.requireNonNull(id); Objects.requireNonNull(spec); Objects.requireNonNull(routine); Objects.requireNonNull(cooldownTicks);
    }

    public sealed interface Spec { TriggerKind kind(); }
    public record Interaction(InteractionFilter action) implements Spec {
        @Override public TriggerKind kind() { return TriggerKind.INTERACTION; }
    }
    public record Proximity(Phase phase, double radius) implements Spec {
        @Override public TriggerKind kind() { return TriggerKind.PROXIMITY; }
    }
    /** Spawn, despawn, damage, death and build events: events with no further filter. */
    public record Lifecycle(TriggerKind kind) implements Spec {}
    public record Waypoint(OptionalInt waypoint) implements Spec {
        @Override public TriggerKind kind() { return TriggerKind.WAYPOINT; }
    }
    public record Timer(int intervalTicks) implements Spec {
        @Override public TriggerKind kind() { return TriggerKind.TIMER; }
    }
    public record DialogueChoice(String dialogue, Optional<String> choice) implements Spec {
        @Override public TriggerKind kind() { return TriggerKind.DIALOGUE_CHOICE; }
    }
    public record ModuleEvent(Capabilities.Key key) implements Spec {
        @Override public TriggerKind kind() { return TriggerKind.MODULE; }
    }

    /** key is the {@code behavior#trigger} name proximity events use to select one binding. */
    boolean matches(TriggerEvent event, String key) {
        return switch (spec) {
            case Interaction interaction -> event instanceof TriggerEvent.Interaction input
                    && (interaction.action() == InteractionFilter.ANY || interaction.action().name().equals(input.action().name()));
            case Proximity ignored -> event instanceof TriggerEvent.Proximity input && input.binding().equals(key);
            case Lifecycle lifecycle -> event.kind() == lifecycle.kind();
            case Waypoint waypoint -> event instanceof TriggerEvent.Waypoint input
                    && (waypoint.waypoint().isEmpty() || waypoint.waypoint().getAsInt() == input.waypoint());
            case Timer ignored -> false;
            case DialogueChoice dialogue -> event instanceof TriggerEvent.DialogueChoice input && dialogue.dialogue().equals(input.dialogue())
                    && (dialogue.choice().isEmpty() || dialogue.choice().get().equals(input.choice()));
            case ModuleEvent module -> event instanceof TriggerEvent.Module input && module.key().equals(input.key());
        };
    }
}
