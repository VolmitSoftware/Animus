package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.admin.Administration;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.RichText;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code /animus undo}: a player steps back through their own changes, each needing its own node; another player's change needs an admin. */
class UndoCommandTest {
    private static final String TX = "0f1e2d3c-4b5a-4968-8776-655443322110";
    @TempDir Path root;

    @Test void withoutAnIdTheNewestOwnChangeIsUndoneAndNothingIsSaidWhenThereIsNone() {
        RecentChanges changes = new RecentChanges();
        AnimusDirectorTest.Actor builder = new AnimusDirectorTest.Actor(true, PermissionNodes.NPC_EDIT);
        assertEquals(Optional.empty(), AnimusCommandTree.undoing(builder, changes, ""));
        assertEquals("[Animus]: Nothing to undo", builder.lines.getLast());
        changes.record(builder.player().orElseThrow(), TX, "Made guard", PermissionNodes.NPC_CREATE);
        assertEquals(Optional.of(new RecentChanges.Change(TX, "Made guard", PermissionNodes.NPC_CREATE)), AnimusCommandTree.undoing(builder, changes, ""));
        assertEquals(Optional.of(new RecentChanges.Change(TX, "Made guard", PermissionNodes.NPC_CREATE)), AnimusCommandTree.undoing(builder, changes, "0f1e2d3c"), "the id an [Undo] carries");
    }

    @Test void someoneElsesChangeNeedsAnAdminAndTheConsoleNamesTheChange() {
        RecentChanges changes = new RecentChanges();
        AnimusDirectorTest.Actor builder = new AnimusDirectorTest.Actor(true, PermissionNodes.NPC_EDIT);
        assertEquals(Optional.empty(), AnimusCommandTree.undoing(builder, changes, "0f1e2d3c"));
        assertEquals("[Animus]: Change 0f1e2d3c is not one of your recent changes; an admin can undo it with /animus rollback 0f1e2d3c",
                builder.lines.getLast());
        AnimusDirectorTest.Actor admin = new AnimusDirectorTest.Actor(true, AnimusDirector.ADMIN);
        assertEquals(Optional.of(new RecentChanges.Change("0f1e2d3c", "", "")), AnimusCommandTree.undoing(admin, changes, "0f1e2d3c"));
        AnimusDirectorTest.Actor console = new AnimusDirectorTest.Actor(false, AnimusDirector.ADMIN);
        assertEquals(Optional.empty(), AnimusCommandTree.undoing(console, changes, ""));
        assertEquals("[Animus]: The console has no last change; /animus rollback <id> undoes a change by its id", console.lines.getLast());
        assertEquals(Optional.of(new RecentChanges.Change("0f1e2d3c", "", "")), AnimusCommandTree.undoing(console, changes, "0f1e2d3c"));
    }

    @Test void undoRevertsTheChangeAndSaysWhatItWas() throws Exception {
        try (AnimusRuntime runtime = new AnimusRuntime(RuntimeEnvironment.bukkit("26.3", root))) {
            runtime.start();
            Administration admin = new Administration(runtime);
            String created = admin.create("guard", "minecraft:player", "world", 0, 64, 0, "", "Guard");
            String tx = created.substring(created.indexOf("tx ") + 3);
            List<String> lines = AnimusCommandTree.undone(new RecentChanges.Change(tx, "Made guard", ""), admin.rollback(tx));
            assertEquals(List.of("[Animus]: Undid: Made guard"), lines.stream().map(RichText::plain).toList());
            assertTrue(runtime.npcs().find("guard").isEmpty(), "the NPC it made is gone");
            assertEquals("[Animus]: Undid: change 0f1e2d3c", RichText.plain(AnimusCommandTree.undone(new RecentChanges.Change(TX, "", ""), List.of("Rolled back")).getFirst()));
        }
    }

    @Test void aChangeIsUndoneOnlyByAPlayerWhoStillHoldsTheNodeItNeeded() throws Exception {
        try (AnimusRuntime runtime = new AnimusRuntime(RuntimeEnvironment.bukkit("26.3", root))) {
            runtime.start();
            Administration admin = new Administration(runtime);
            String created = admin.create("guard", "minecraft:player", "world", 0, 64, 0, "", "Guard");
            RecentChanges.Change made = new RecentChanges.Change(created.substring(created.indexOf("tx ") + 3), "Made guard", "");
            List<String> refused = AnimusCommandTree.undo(made, Set.of(PermissionNodes.TOOLS, PermissionNodes.NPC_EDIT)::contains, runtime.documents(), admin::rollback);
            assertEquals(List.of("[Animus]: No permission: animus.npc.create; ask an operator to grant it"), refused.stream().map(RichText::plain).toList());
            assertTrue(runtime.npcs().find("guard").isPresent(), "nothing was undone");
            List<String> undone = AnimusCommandTree.undo(made, Set.of(PermissionNodes.TOOLS, PermissionNodes.NPC_CREATE)::contains, runtime.documents(), admin::rollback);
            assertEquals(List.of("[Animus]: Undid: Made guard"), undone.stream().map(RichText::plain).toList());
            assertTrue(runtime.npcs().find("guard").isEmpty());
        }
    }

    @Test void undoNeedsTheMenuNodeAndIsNotAPlayerOnlyCommand() {
        AnimusDirector director = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        DirectorRuntimeNode undo = director.engine().getRoot().getChildren().stream().filter((DirectorRuntimeNode node) -> node.getDescriptor().getName().equals("undo"))
                .findFirst().orElseThrow();
        assertEquals(PermissionNodes.TOOLS, AnimusDirector.permission(undo), "each change then needs the node it needed");
        assertEquals("/animus undo [change=]", AnimusDirector.usage(undo));
    }

    @Test void theCreateReplyOffersEditAndUndo() {
        ChatLine made = AnimusCommandTree.made(true, "guard", "guard", "", true, TX);
        assertEquals("[Animus]: Made guard; tx 0f1e2d3c [Edit] [Undo]", made.visible());
        assertEquals("[Animus]: Made guard [Edit] [Undo]", made.withoutTransactionIds().visible());
        assertEquals(List.of("/animus tools menu guard", "/animus undo 0f1e2d3c"), runs(made.component()), "[Edit] opens the new NPC's screen");
        assertEquals("[Animus]: Made guard; tx " + TX, RichText.plain(AnimusCommandTree.made(false, "guard", "guard", "", true, TX).console()), "the console cannot open the menu");
        ChatLine despawned = AnimusCommandTree.made(true, "guard", "guard", "", false, TX);
        assertEquals("[Animus]: Made guard, despawned; tx 0f1e2d3c [Edit] [Undo]; Next: /animus spawn guard", despawned.visible());
        assertEquals(UUID.fromString(TX).toString(), made.transactions().getFirst());
    }

    /** What each chip runs. */
    private static List<String> runs(Component root) {
        List<String> found = new ArrayList<>();
        ClickEvent click = root.clickEvent();
        if (click != null && click.action() == ClickEvent.Action.RUN_COMMAND) found.add(((ClickEvent.Payload.Text) click.payload()).value());
        for (Component child : root.children()) found.addAll(runs(child));
        return found;
    }
}
