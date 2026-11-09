package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolkitCommandsTest {
    @Test void toolsAloneOpensThePalette() {
        assertEquals(List.of("tools", "open"), ToolkitCommands.expand(List.of("tools")));
        assertEquals(List.of("tools", "wand"), ToolkitCommands.expand(List.of("tools", "wand")));
        assertEquals(List.of("tools", "help"), ToolkitCommands.expand(List.of("tools", "help")));
        assertEquals(List.of("list"), ToolkitCommands.expand(List.of("list")));
    }

    @Test void theToolsArePlayerCommandsUnderTheirOwnNode() {
        AnimusDirector director = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        DirectorRuntimeNode tools = director.engine().getRoot().getChildren().stream().filter((DirectorRuntimeNode node) -> node.getDescriptor().getName().equals("tools")).findFirst().orElseThrow();
        assertEquals(List.of("manage", "open", "wand"), tools.getChildren().stream().map((DirectorRuntimeNode node) -> node.getDescriptor().getName()).sorted().toList());
        for (DirectorRuntimeNode command : tools.getChildren()) assertEquals(PermissionNodes.TOOLS, AnimusDirector.permission(command));

        AnimusDirectorTest.Actor console = new AnimusDirectorTest.Actor(false, AnimusDirector.ADMIN);
        director.execute(console, new String[]{"tools"});
        assertTrue(console.text().contains("Players only"), console.text());
        AnimusDirectorTest.Actor player = new AnimusDirectorTest.Actor(true);
        director.execute(player, new String[]{"tools", "wand"});
        assertTrue(player.text().contains("No permission: animus.tools"), player.text());
    }
}
