package art.arcane.animus.core.command;

import java.util.List;
import java.util.function.Predicate;

/**
 * The management nodes under {@code animus.admin}. Each command group and toolkit screen needs one of them, so an owner
 * can give a builder {@code animus.npc.create} without combat or imports. {@code animus.admin} grants every one: Bukkit
 * through the children plugin.yml declares, mod servers through {@link #granted}.
 */
public final class PermissionNodes {
    public static final String ADMIN = "animus.admin";
    public static final String NPC_VIEW = "animus.npc.view", NPC_CREATE = "animus.npc.create", NPC_EDIT = "animus.npc.edit", NPC_DELETE = "animus.npc.delete",
            NPC_SKIN = "animus.npc.skin", NPC_MOVE = "animus.npc.move", NPC_SPEAK = "animus.npc.speak", TOOLS = "animus.tools", BEHAVIORS = "animus.behaviors",
            BEHAVIOR_STATE = "animus.behaviors.state", COMBAT = "animus.combat", IMPORT = "animus.import", EDITOR_PAIR = "animus.editor.pair",
            SHOPS = "animus.shops", GUI = "animus.gui", BUILD = "animus.build", RECORD = "animus.record", RIGS = "animus.rigs", QUESTS = "animus.quests";
    /** The quest journal and offer buttons, for players; not under {@link #ADMIN}, and granted by default. */
    public static final String QUESTS_USE = "animus.quests.use";
    /** Sitting on NPCs and {@code /animus seat toggle}, for players; not under {@link #ADMIN}, and granted by default. */
    public static final String SEAT = "animus.seat";
    /** Acting on NPCs the player does not own, server NPCs and other players' alike; their other nodes still decide what they may do. */
    public static final String NPC_OTHERS = "animus.npc.others";
    /** Pairing the web editor with {@code apply:commands} and accepting imported commands; not under {@link #ADMIN}. */
    public static final String EDITOR_COMMANDS = "animus.editor.commands";
    /**
     * What an NPC owned by a player may do only while its owner holds the node: run commands, run them as an operator,
     * reach past its own NPC (server behaviors, other NPCs, builds, teleports, other plugins' modules), reach other players,
     * and create items or money.
     */
    public static final String OWNED_COMMANDS = "animus.owned.commands", OWNED_OPERATOR = "animus.owned.operator", OWNED_SERVER = "animus.owned.server",
            OWNED_PLAYERS = "animus.owned.players", OWNED_ITEMS = "animus.owned.items";
    /**
     * Nodes whose commands change NPCs whoever owns them: imports write server NPCs and a paired editor writes any file.
     * A player needs {@link #ADMIN} as well to use them.
     */
    public static final java.util.Set<String> EVERY_NPC = java.util.Set.of(IMPORT, EDITOR_PAIR);
    /** {@code animus.npc.limit.<n>}: how many NPCs a player may own; the highest held wins. */
    public static final String LIMIT_PREFIX = "animus.npc.limit.";
    /** Making and running your own shops on chests you can open, each with its own NPC. */
    public static final String SHOPS_CREATE = "animus.shops.create";
    /** {@code animus.shops.limit.<n>}: how many shops a player may own; the highest held wins. */
    public static final String SHOP_LIMIT_PREFIX = "animus.shops.limit.";
    /** Every node {@link #ADMIN} grants, in the order plugin.yml lists them. */
    public static final List<String> CHILDREN = List.of(NPC_VIEW, NPC_CREATE, NPC_EDIT, NPC_DELETE, NPC_SKIN, NPC_MOVE, NPC_OTHERS, NPC_SPEAK, TOOLS, BEHAVIORS, BEHAVIOR_STATE,
            COMBAT, IMPORT, EDITOR_PAIR, SHOPS, SHOPS_CREATE, GUI, BUILD, RECORD, RIGS, QUESTS, OWNED_COMMANDS, OWNED_OPERATOR, OWNED_SERVER, OWNED_PLAYERS, OWNED_ITEMS);

    private PermissionNodes() {}

    /** Whether {@code has} grants {@code node}, either itself or through {@link #ADMIN} for a node under it. */
    public static boolean granted(Predicate<String> has, String node) {
        return has.test(node) || CHILDREN.contains(node) && has.test(ADMIN);
    }
}
