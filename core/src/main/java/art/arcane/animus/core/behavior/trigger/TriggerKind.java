package art.arcane.animus.core.behavior.trigger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Event names accepted by the {@code event} field of a behavior trigger, with the fields each accepts. */
public enum TriggerKind {
    INTERACTION("interaction", true, Reach.NPC, "A viewer's validated click on the NPC, or the drop or swap-hands key pressed while looking at it in reach. Runs read context.action, context.sneaking and context.hand (main_hand or off_hand).", "action", "consume", "playerCooldownTicks", "whileRunning"),
    PROXIMITY("proximity", true, Reach.NPC, "A viewer crosses a radius around the NPC.", "phase", "radius"),
    SPAWN("spawn", false, Reach.NPC, "The runtime first presents the NPC."),
    DESPAWN("despawn", false, Reach.NPC, "A spawned NPC is despawned."),
    DAMAGE("damage", false, Reach.NPC, "Authoritative NPC health falls. Runs read context.damage, context.attacker and context.attacker_type (player, mob, npc or empty); a player attacker is the run's player."),
    DEATH("death", false, Reach.NPC, "Authoritative NPC health reaches zero. Runs read context.killer and context.killer_type (player, mob, npc or empty); a player killer is the run's player."),
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
    PLAYER_WORLD_CHANGE("player_world_change", true, Reach.SERVER, "A player moves to another world, optionally only from or to named worlds.", "from", "to"),
    PLAYER_AREA("player_area", true, Reach.SERVER, "A player enters or leaves a cuboid or ellipsoid area, checked every half second and on join and quit. Runs read context.area, context.world and context.cause (join, move or quit).", "phase", "area", "world", "cuboid", "ellipsoid"),
    PLAYER_INVENTORY_OPEN("player_inventory_open", true, Reach.SERVER, "A player opens an inventory. Runs read context.inventory_type. cancel keeps it closed.", "inventory", "cancel"),
    PLAYER_INVENTORY_CLOSE("player_inventory_close", true, Reach.SERVER, "A player closes an inventory. Runs read context.inventory_type.", "inventory"),
    PLAYER_INVENTORY_CLICK("player_inventory_click", true, Reach.SERVER, "A player clicks a slot of an open inventory. Runs read context.inventory_type, context.click, context.slot and context.item. cancel undoes the click.", "inventory", "click", "item", "cancel"),
    PLAYER_ITEM_DROP("player_item_drop", true, Reach.SERVER, "A player drops an item. Runs read context.item and context.amount. cancel keeps it in the inventory.", "item", "cancel"),
    PLAYER_ITEM_PICKUP("player_item_pickup", true, Reach.SERVER, "A player picks up an item. Runs read context.item and context.amount. cancel leaves it on the ground.", "item", "cancel"),
    PLAYER_ITEM_CONSUME("player_item_consume", true, Reach.SERVER, "A player finishes eating or drinking an item. Runs read context.item. cancel keeps the item.", "item", "cancel"),
    PLAYER_BLOCK_CLICK("player_block_click", true, Reach.SERVER, "A player left or right clicks a block. Runs read context.block, context.click (left or right), context.hand, context.location, context.world, context.x, context.y and context.z. cancel stops the click.", "block", "click", "cancel"),
    PLAYER_BLOCK_PLACE("player_block_place", true, Reach.SERVER, "A player places a block. Runs read context.block and its location. cancel stops the placement.", "block", "cancel"),
    PLAYER_BLOCK_BREAK("player_block_break", true, Reach.SERVER, "A player breaks a block. Runs read context.block and its location. cancel keeps the block.", "block", "cancel"),
    PLAYER_DAMAGED("player_damaged", true, Reach.SERVER, "A player takes damage. Runs read context.cause, context.damage and context.damager (an entity type, empty for none). cancel prevents the damage.", "cause", "damager", "cancel"),
    PLAYER_DAMAGE_ENTITY("player_damage_entity", true, Reach.SERVER, "A player hits a living entity that is not an NPC. Runs read context.entity, context.entity_uuid, context.cause and context.damage. cancel prevents the damage.", "entity", "cancel"),
    PLAYER_KILL("player_kill", true, Reach.SERVER, "A player kills a living entity. Runs read context.entity and context.entity_uuid.", "entity"),
    PROJECTILE_LAUNCH("projectile_launch", true, Reach.SERVER, "A projectile such as an arrow, snowball or trident is launched; the player is the shooter when a player shot it. Runs read context.projectile, context.shooter (an entity type, empty for none), their UUIDs and the launch location. cancel stops the launch.", "projectile", "shooter", "cancel"),
    PROJECTILE_HIT("projectile_hit", true, Reach.SERVER, "A projectile hits an entity or a block; the player is the shooter when a player shot it. Runs read context.projectile, context.shooter, context.entity and context.block (each empty when absent), their UUIDs and the hit location. cancel lets the projectile pass as if nothing was hit.", "projectile", "shooter", "entity", "block", "cancel"),
    ENTITY_SHOOT_BOW("entity_shoot_bow", true, Reach.SERVER, "A player or mob shoots a bow or crossbow; the player is the shooter when a player shot. Runs read context.shooter, context.bow, context.projectile, context.force (a player's bow draw from 0 to 1, otherwise 1), context.hand and the shooter's location. cancel stops the shot.", "shooter", "bow", "projectile", "cancel");

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
    /** Trigger fields that filter on one context key of the event, with the context key each compares. */
    public static final java.util.Map<String, String> FILTERS = java.util.Map.of("inventory", "inventory_type", "click", "click", "item", "item",
            "block", "block", "cause", "cause", "damager", "damager", "entity", "entity", "projectile", "projectile", "shooter", "shooter", "bow", "bow");
    /** Whether a trigger of this event may name a {@code cancel} condition: the platform event can be cancelled. */
    public boolean cancellable() { return fields.contains("cancel"); }
    public static List<String> names() {
        List<String> names = new ArrayList<>();
        for (TriggerKind kind : values()) names.add(kind.jsonName);
        return List.copyOf(names);
    }
}
