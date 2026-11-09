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
            NPC_SKIN = "animus.npc.skin", NPC_MOVE = "animus.npc.move", TOOLS = "animus.tools", BEHAVIORS = "animus.behaviors",
            BEHAVIOR_STATE = "animus.behaviors.state", COMBAT = "animus.combat", IMPORT = "animus.import", EDITOR_PAIR = "animus.editor.pair",
            SHOPS = "animus.shops", GUI = "animus.gui", BUILD = "animus.build", RECORD = "animus.record", RIGS = "animus.rigs";
    /** Pairing the web editor with {@code apply:commands} and accepting imported commands; not under {@link #ADMIN}. */
    public static final String EDITOR_COMMANDS = "animus.editor.commands";
    /** Every node {@link #ADMIN} grants, in the order plugin.yml lists them. */
    public static final List<String> CHILDREN = List.of(NPC_VIEW, NPC_CREATE, NPC_EDIT, NPC_DELETE, NPC_SKIN, NPC_MOVE, TOOLS, BEHAVIORS, BEHAVIOR_STATE,
            COMBAT, IMPORT, EDITOR_PAIR, SHOPS, GUI, BUILD, RECORD, RIGS);

    private PermissionNodes() {}

    /** Whether {@code has} grants {@code node}, either itself or through {@link #ADMIN} for a node under it. */
    public static boolean granted(Predicate<String> has, String node) {
        return has.test(node) || CHILDREN.contains(node) && has.test(ADMIN);
    }
}
