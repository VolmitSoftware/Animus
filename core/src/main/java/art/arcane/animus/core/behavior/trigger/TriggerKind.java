package art.arcane.animus.core.behavior.trigger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Event names accepted by the {@code event} field of a behavior trigger, with the fields each accepts. */
public enum TriggerKind {
    INTERACTION("interaction", true, Reach.NPC, "A viewer's validated hit on the NPC. Runs read context.sneaking and context.hand (main_hand or off_hand).", "action", "consume"),
    PROXIMITY("proximity", true, Reach.NPC, "A viewer crosses a radius around the NPC.", "phase", "radius"),
    SPAWN("spawn", false, Reach.NPC, "The runtime first presents the NPC."),
    DESPAWN("despawn", false, Reach.NPC, "A spawned NPC is despawned."),
    DAMAGE("damage", false, Reach.NPC, "Authoritative NPC health falls."),
    DEATH("death", false, Reach.NPC, "Authoritative NPC health reaches zero."),
    WAYPOINT("waypoint", false, Reach.NPC, "The NPC arrives at a route point.", "waypoint"),
    TIMER("timer", false, Reach.BOTH, "An interval passes while the NPC is active, or while the server runs in a server behavior.", "intervalTicks"),
    DIALOGUE_CHOICE("dialogue_choice", true, Reach.NPC, "A viewer clicks an option of an animus:choices prompt from this NPC.", "dialogue", "choice"),
    MODULE("module", true, Reach.BOTH, "The owning module publishes a trigger.", "key"),
    BUILD_FINISHED("build_finished", false, Reach.NPC, "A build this NPC works on places its last block."),
    BUILD_PAUSED("build_paused", false, Reach.NPC, "A build this NPC works on is paused by command or starts waiting for terrain, materials or builders."),
    ANIMATION_FINISHED("animation_finished", false, Reach.NPC, "An animation started by animus:play-animation reaches its end: a once clip stops, a hold clip starts holding.", "animation"),
    PLAYER_JOIN("player_join", true, Reach.SERVER, "A player joins the server. first: true matches only first joins, false only returning players.", "first"),
    PLAYER_QUIT("player_quit", true, Reach.SERVER, "A player leaves the server."),
    PLAYER_CHAT("player_chat", true, Reach.SERVER, "A player sends a chat message, optionally matching a case-insensitive pattern with * wildcards.", "pattern"),
    COMMAND("command", true, Reach.SERVER, "A player or the console runs the Animus-managed command this trigger registers.", "name", "aliases", "permission", "description", "usage"),
    SERVER_START("server_start", false, Reach.SERVER, "The server behavior is first enabled after the server starts."),
    PLAYER_DEATH("player_death", true, Reach.SERVER, "A player dies."),
    PLAYER_RESPAWN("player_respawn", true, Reach.SERVER, "A player respawns."),
    PLAYER_WORLD_CHANGE("player_world_change", true, Reach.SERVER, "A player moves to another world, optionally only from or to named worlds.", "from", "to");

    /** Which documents may use an event: NPC behaviors, server behaviors ({@code "server": true}) or both. */
    public enum Reach { NPC, SERVER, BOTH }
    /** Fields every trigger accepts. */
    public static final List<String> COMMON_FIELDS = List.of("id", "event", "routine", "cooldownTicks");
    private final String jsonName;
    private final boolean player;
    private final Reach reach;
    private final String description;
    private final List<String> fields;
    TriggerKind(String jsonName, boolean player, Reach reach, String description, String... fields) {
        this.jsonName = jsonName;
        this.player = player;
        this.reach = reach;
        this.description = description;
        this.fields = List.of(fields);
    }
    public String jsonName() { return jsonName; }
    /** Whether firings can carry a player. A module decides for itself. */
    public boolean carriesPlayer() { return player; }
    public String description() { return description; }
    public Reach reach() { return reach; }
    public boolean server() { return reach != Reach.NPC; }
    public boolean npc() { return reach != Reach.SERVER; }
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
