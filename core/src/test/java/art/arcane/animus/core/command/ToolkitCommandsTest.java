package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.gui.FakeNpcRuntime;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.toolkit.Toolkit;
import art.arcane.animus.core.toolkit.ToolkitMode;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolkitCommandsTest {
    @TempDir Path root;

    @Test void toolsAloneOpensTheMenu() {
        assertEquals(List.of("tools", "menu"), ToolkitCommands.expand(List.of("tools")));
        assertEquals(List.of("tools", "menu"), ToolkitCommands.expand(List.of("tool")), "the alias opens it too");
        assertEquals(List.of("tools", "wand"), ToolkitCommands.expand(List.of("tools", "wand")));
        assertEquals(List.of("tools", "help"), ToolkitCommands.expand(List.of("tools", "help")));
        assertEquals(List.of("list"), ToolkitCommands.expand(List.of("list")));
    }

    @Test void theToolsArePlayerCommandsUnderTheirOwnNode() {
        AnimusDirector director = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        DirectorRuntimeNode tools = director.engine().getRoot().getChildren().stream().filter((DirectorRuntimeNode node) -> node.getDescriptor().getName().equals("tools")).findFirst().orElseThrow();
        assertEquals(List.of("capture", "home", "menu", "mode", "wand"), tools.getChildren().stream().map((DirectorRuntimeNode node) -> node.getDescriptor().getName()).sorted().toList());
        assertEquals(List.of("mode", "wand"), director.complete(new AnimusDirectorTest.Actor(true, PermissionNodes.TOOLS), new String[]{"tools", ""}).stream().sorted().toList(),
                "the commands /animus tools and its chips run stay out of completion");
        for (DirectorRuntimeNode command : tools.getChildren()) assertEquals(PermissionNodes.TOOLS, AnimusDirector.permission(command));

        AnimusDirectorTest.Actor console = new AnimusDirectorTest.Actor(false, AnimusDirector.ADMIN);
        director.execute(console, new String[]{"tools"});
        assertTrue(console.text().contains("Players only"), console.text());
        AnimusDirectorTest.Actor player = new AnimusDirectorTest.Actor(true);
        director.execute(player, new String[]{"tools", "wand"});
        assertTrue(player.text().contains("No permission: animus.tools"), player.text());
    }

    @Test void theConsoleHasNoMenuMode() {
        AnimusDirector director = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        AnimusDirectorTest.Actor console = new AnimusDirectorTest.Actor(false, AnimusDirector.ADMIN);
        director.execute(console, new String[]{"tools", "mode", "advanced"});
        assertTrue(console.text().contains("Players only"), console.text());
        AnimusDirectorTest.Actor player = new AnimusDirectorTest.Actor(true);
        director.execute(player, new String[]{"tools", "mode", "advanced"});
        assertTrue(player.text().contains("No permission: animus.tools"), player.text());
    }

    @Test void theModeCompletesBothWords() {
        AnimusCommandTree tree = new AnimusCommandTree(null);
        assertEquals(List.of("simple", "advanced"), List.copyOf(tree.values(ToolkitCommands.MENU_MODE, new AnimusDirectorTest.Actor(true), java.util.Map.of())));
    }

    @Test void theModeIsShownSetAndRefused() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository npcs = new NpcRepository(store);
            ScreenManager<MockScreenHost.Player, Stack> screens = new ScreenManager<>(new MockScreenHost(), (UUID npc) -> true, (String warning) -> {});
            FakeNpcRuntime<MockScreenHost.Player> living = new FakeNpcRuntime<>((UUID id) -> Optional.empty());
            Toolkit<MockScreenHost.Player, Stack> toolkit = Toolkit.install(new Toolkit.Parts<>(() -> npcs, () -> store, screens, living, Runnable::run,
                    (MockScreenHost.Player player) -> Optional.empty(), Optional.empty(), () -> 0L));
            try {
                AnimusDirectorTest.Actor player = new AnimusDirectorTest.Actor(true, PermissionNodes.TOOLS);
                UUID id = player.player().orElseThrow();
                ToolkitCommands.menuMode(player, toolkit, "");
                assertEquals("[Animus]: Mode: Simple", player.lines.getLast(), "no word shows the current mode");
                ToolkitCommands.menuMode(player, toolkit, "advanced");
                assertEquals("[Animus]: Mode: Advanced", player.lines.getLast());
                assertEquals(ToolkitMode.ADVANCED, toolkit.mode(id));
                ToolkitCommands.menuMode(player, toolkit, "");
                assertEquals("[Animus]: Mode: Advanced", player.lines.getLast());
                ToolkitCommands.menuMode(player, toolkit, "Simple");
                assertEquals("[Animus]: Mode: Simple", player.lines.getLast());
                assertEquals(ToolkitMode.SIMPLE, toolkit.mode(id));
                ToolkitCommands.menuMode(player, toolkit, "expert");
                assertEquals("[Animus]: Mode is simple or advanced", player.lines.getLast());
                assertEquals(ToolkitMode.SIMPLE, toolkit.mode(id), "a bad word changes nothing");
            } finally { toolkit.close(); }
        }
    }
}
