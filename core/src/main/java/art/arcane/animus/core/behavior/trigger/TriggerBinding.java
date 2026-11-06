package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.behavior.BehaviorExpression;
import java.util.List;
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
    /** {@code consume}, when present, is checked at click time: when it holds, the click is this behavior's and Animus's default handling is skipped. */
    public record Interaction(InteractionFilter action, Optional<BehaviorExpression> consume) implements Spec {
        public Interaction { Objects.requireNonNull(action); Objects.requireNonNull(consume); }
        public Interaction(InteractionFilter action) { this(action, Optional.empty()); }
        @Override public TriggerKind kind() { return TriggerKind.INTERACTION; }
        boolean accepts(TriggerEvent.Action input) { return action == InteractionFilter.ANY || action.name().equals(input.name()); }
    }
    public record Proximity(Phase phase, double radius) implements Spec {
        @Override public TriggerKind kind() { return TriggerKind.PROXIMITY; }
    }
    /** Events with no further filter: spawn, despawn, damage, death, build, player quit, death and respawn, server start. */
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

    /** key is the {@code behavior#trigger} name proximity events use to select one binding. */
    boolean matches(TriggerEvent event, String key) {
        return switch (spec) {
            case Interaction interaction -> event instanceof TriggerEvent.Interaction input && interaction.accepts(input.action());
            case Proximity ignored -> event instanceof TriggerEvent.Proximity input && input.binding().equals(key);
            case Lifecycle lifecycle -> event.kind() == lifecycle.kind();
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
        };
    }
}
