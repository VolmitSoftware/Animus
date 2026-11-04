package art.arcane.animus.core.behavior.trigger;

import java.util.Optional;

/** Event names accepted by the {@code event} field of a behavior trigger. */
public enum TriggerKind {
    INTERACTION("interaction"),
    PROXIMITY("proximity"),
    SPAWN("spawn"),
    DESPAWN("despawn"),
    DAMAGE("damage"),
    DEATH("death"),
    WAYPOINT("waypoint"),
    TIMER("timer"),
    DIALOGUE_CHOICE("dialogue_choice"),
    MODULE("module");

    private final String jsonName;
    TriggerKind(String jsonName) { this.jsonName = jsonName; }
    public String jsonName() { return jsonName; }
    public static Optional<TriggerKind> find(String name) {
        for (TriggerKind kind : values()) if (kind.jsonName.equals(name)) return Optional.of(kind);
        return Optional.empty();
    }
}
