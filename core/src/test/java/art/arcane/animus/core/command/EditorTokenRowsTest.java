package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

/** A row of {@code /animus editor tokens} fills in a revoke that runs. */
class EditorTokenRowsTest {
    @Test void aLabelledTokenRowFillsInTheRevokeOfItsIdAlone() {
        String row = "tok_3f2a9c \"p6web\" | read:npcs apply:npcs | expires 2026-11-20 10:00 | last used never | streams 0";
        assertEquals(List.of("/animus editor revoke tok_3f2a9c"), suggested(CommandGroup.entry(row, ServiceCommands.Editor::revoking)));
        assertEquals(List.of("/animus editor revoke tok_77aa01"), suggested(CommandGroup.entry("tok_77aa01 | read:npcs | expires never | last used never | streams 1",
                ServiceCommands.Editor::revoking)), "an unlabelled token too");
    }

    private static List<String> suggested(String miniMessage) {
        List<String> found = new ArrayList<>();
        collect(MiniMessage.miniMessage().deserialize(miniMessage), found);
        return found;
    }

    private static void collect(Component component, List<String> found) {
        ClickEvent click = component.clickEvent();
        if (click != null && click.action() == ClickEvent.Action.SUGGEST_COMMAND && !found.contains(((ClickEvent.Payload.Text) click.payload()).value()))
            found.add(((ClickEvent.Payload.Text) click.payload()).value());
        for (Component child : component.children()) collect(child, found);
    }
}
