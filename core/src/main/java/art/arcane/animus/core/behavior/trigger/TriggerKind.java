package art.arcane.animus.core.behavior.trigger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Event names accepted by the {@code event} field of a behavior trigger, with the fields each accepts. */
public enum TriggerKind {
    INTERACTION("interaction", true, "A viewer's validated hit on the NPC.", "action"),
    PROXIMITY("proximity", true, "A viewer crosses a radius around the NPC.", "phase", "radius"),
    SPAWN("spawn", false, "The runtime first presents the NPC."),
    DESPAWN("despawn", false, "A spawned NPC is despawned."),
    DAMAGE("damage", false, "Authoritative NPC health falls."),
    DEATH("death", false, "Authoritative NPC health reaches zero."),
    WAYPOINT("waypoint", false, "The NPC arrives at a route point.", "waypoint"),
    TIMER("timer", false, "An interval passes while the NPC is active.", "intervalTicks"),
    DIALOGUE_CHOICE("dialogue_choice", true, "A viewer clicks an option of an animus:choices prompt from this NPC.", "dialogue", "choice"),
    MODULE("module", true, "The owning module publishes a trigger.", "key"),
    BUILD_FINISHED("build_finished", false, "A build this NPC works on places its last block."),
    BUILD_PAUSED("build_paused", false, "A build this NPC works on is paused by command or starts waiting for terrain, materials or builders."),
    ANIMATION_FINISHED("animation_finished", false, "An animation started by animus:play-animation reaches its end: a once clip stops, a hold clip starts holding.", "animation");

    /** Fields every trigger accepts. */
    public static final List<String> COMMON_FIELDS = List.of("id", "event", "routine", "cooldownTicks");
    private final String jsonName;
    private final boolean player;
    private final String description;
    private final List<String> fields;
    TriggerKind(String jsonName, boolean player, String description, String... fields) {
        this.jsonName = jsonName;
        this.player = player;
        this.description = description;
        this.fields = List.of(fields);
    }
    public String jsonName() { return jsonName; }
    /** Whether firings can carry a player. A module decides for itself. */
    public boolean carriesPlayer() { return player; }
    public String description() { return description; }
    /** Fields specific to this event. */
    public List<String> fields() { return fields; }
    public List<String> keys() {
        List<String> keys = new ArrayList<>(COMMON_FIELDS);
        keys.addAll(fields);
        return List.copyOf(keys);
    }
    public static Optional<TriggerKind> find(String name) {
        for (TriggerKind kind : values()) if (kind.jsonName.equals(name)) return Optional.of(kind);
        return Optional.empty();
    }
    public static List<String> names() {
        List<String> names = new ArrayList<>();
        for (TriggerKind kind : values()) names.add(kind.jsonName);
        return List.copyOf(names);
    }
}
