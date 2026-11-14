package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Builders get limited rights: each group needs its own node under {@code animus.admin}, which still grants all of them. */
class PermissionNodesTest {
    private final AnimusDirector director = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());

    private DirectorRuntimeNode node(String... path) {
        DirectorRuntimeNode cursor = director.engine().getRoot();
        for (String name : path)
            cursor = cursor.getChildren().stream().filter((DirectorRuntimeNode child) -> child.getDescriptor().getName().equals(name)).findFirst()
                    .orElseThrow(() -> new AssertionError("no " + String.join(" ", path)));
        return cursor;
    }

    @Test void adminGrantsEveryChildAndNothingElse() {
        Set<String> admin = Set.of(PermissionNodes.ADMIN);
        for (String child : PermissionNodes.CHILDREN) assertTrue(PermissionNodes.granted(admin::contains, child), child);
        assertFalse(PermissionNodes.granted(admin::contains, "animus.debug"), "diagnostics stay their own node");
        assertFalse(PermissionNodes.granted(Set.of(PermissionNodes.NPC_EDIT)::contains, PermissionNodes.NPC_DELETE));
    }

    @Test void eachGroupNeedsItsOwnNode() {
        assertEquals(PermissionNodes.NPC_CREATE, AnimusDirector.permission(node("create")));
        assertEquals(PermissionNodes.NPC_CREATE, AnimusDirector.permission(node("clone")));
        assertEquals(PermissionNodes.NPC_EDIT, AnimusDirector.permission(node("edit")));
        assertEquals(PermissionNodes.NPC_EDIT, AnimusDirector.permission(node("spawn")));
        assertEquals(PermissionNodes.NPC_EDIT, AnimusDirector.permission(node("npc", "attribute")));
        assertEquals(PermissionNodes.NPC_DELETE, AnimusDirector.permission(node("delete")));
        assertEquals(PermissionNodes.NPC_VIEW, AnimusDirector.permission(node("list")));
        assertEquals(PermissionNodes.NPC_VIEW, AnimusDirector.permission(node("select")));
        assertEquals(PermissionNodes.NPC_MOVE, AnimusDirector.permission(node("navigation", "go")));
        assertEquals(PermissionNodes.COMBAT, AnimusDirector.permission(node("combat", "attack")));
        assertEquals(PermissionNodes.BEHAVIORS, AnimusDirector.permission(node("behaviors", "run")));
        assertEquals(PermissionNodes.BEHAVIOR_STATE, AnimusDirector.permission(node("behaviors", "state", "delete", "all", "confirm")));
        assertEquals(PermissionNodes.IMPORT, AnimusDirector.permission(node("import", "citizens", "rollback")));
        assertEquals(PermissionNodes.IMPORT, AnimusDirector.permission(node("import", "shops", "apply")));
        assertEquals(PermissionNodes.IMPORT, AnimusDirector.permission(node("import", "denizen", "release")));
        assertEquals(PermissionNodes.IMPORT, AnimusDirector.permission(node("import", "denizen", "held")));
        assertEquals(PermissionNodes.IMPORT, AnimusDirector.permission(node("import", "gloss", "live")));
        assertEquals(PermissionNodes.SHOPS, AnimusDirector.permission(node("shop", "reset")));
        assertEquals(PermissionNodes.GUI, AnimusDirector.permission(node("gui", "open")));
        assertEquals(PermissionNodes.QUESTS, AnimusDirector.permission(node("quest", "reset")));
        assertEquals(PermissionNodes.QUESTS_USE, AnimusDirector.permission(node("quest", "journal")), "players open their journal without staff rights");
        assertEquals(PermissionNodes.EDITOR_PAIR, AnimusDirector.permission(node("editor", "revoke")));
        assertEquals(PermissionNodes.TOOLS, AnimusDirector.permission(node("tools", "wand")));
        assertEquals(PermissionNodes.TOOLS, AnimusDirector.permission(node("setup")));
        assertEquals(PermissionNodes.ADMIN, AnimusDirector.permission(node("reload")), "reload and rollback stay with animus.admin");
    }

    @Test void aBuilderCanCreateButNotHurtPlayersOrWipeData() {
        AnimusDirectorTest.Actor builder = new AnimusDirectorTest.Actor(true, PermissionNodes.NPC_VIEW, PermissionNodes.NPC_CREATE, PermissionNodes.NPC_EDIT,
                PermissionNodes.NPC_SKIN, PermissionNodes.NPC_MOVE, PermissionNodes.TOOLS);
        assertTrue(AnimusDirector.allowed(builder, node("create")));
        assertTrue(AnimusDirector.allowed(builder, node("tools", "menu")));
        for (String[] refused : List.of(new String[]{"combat", "attack"}, new String[]{"behaviors", "state", "delete", "all", "confirm"},
                new String[]{"import", "citizens", "rollback"}, new String[]{"shop", "reset"}, new String[]{"editor", "pair"}, new String[]{"delete"}))
            assertFalse(AnimusDirector.allowed(builder, node(refused)), String.join(" ", refused));
        director.execute(builder, new String[]{"combat", "attack", "guard", "Steve"});
        assertTrue(builder.text().contains("No permission: animus.combat"), builder.text());
        AnimusDirectorTest.Actor admin = new AnimusDirectorTest.Actor(true, PermissionNodes.ADMIN);
        assertTrue(AnimusDirector.allowed(admin, node("combat", "attack")), "existing admin grants keep working");
    }

    @Test void everyManagementNodeIsUsedAndEveryCommandNodeIsKnown() {
        List<DirectorRuntimeNode> commands = new ArrayList<>();
        collect(director.engine().getRoot(), commands);
        Set<String> used = new TreeSet<>();
        for (DirectorRuntimeNode command : commands) used.add(AnimusDirector.permission(command));
        Set<String> known = new TreeSet<>(PermissionNodes.CHILDREN);
        known.addAll(List.of(PermissionNodes.ADMIN, "", "animus.status", "animus.debug", "animus.debug.mode", "animus.build.donate", PermissionNodes.QUESTS_USE));
        assertTrue(known.containsAll(used), "unknown nodes: " + used);
        Set<String> unused = new TreeSet<>(PermissionNodes.CHILDREN);
        unused.removeAll(used);
        assertTrue(Set.of(PermissionNodes.NPC_SKIN).containsAll(unused), "only the skin node may be the toolkit's alone: " + unused);
    }

    private static void collect(DirectorRuntimeNode node, List<DirectorRuntimeNode> out) {
        if (node.isInvocable()) out.add(node);
        for (DirectorRuntimeNode child : node.getChildren()) collect(child, out);
    }
}
