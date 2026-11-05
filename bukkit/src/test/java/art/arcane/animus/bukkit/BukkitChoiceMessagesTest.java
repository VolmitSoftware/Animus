package art.arcane.animus.bukkit;

import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import java.util.ArrayList;
import java.util.List;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BukkitChoiceMessagesTest {
    @Test void choiceOptionsAreClickableAndCarryTheTokenWithoutInterpretingText() {
        BaseComponent[] rendered = BukkitChoiceMessages.options("animus", "a".repeat(32), List.of(new BehaviorPlatform.ChoiceOption("yes", "Yes &c/op me"), new BehaviorPlatform.ChoiceOption("no", "No")));
        List<TextComponent> buttons = new ArrayList<>();
        for (BaseComponent component : rendered) if (component.getClickEvent() != null) buttons.add((TextComponent) component);
        assertEquals(2, buttons.size());
        assertEquals(ClickEvent.Action.RUN_COMMAND, buttons.getFirst().getClickEvent().getAction());
        assertEquals("/animus choice " + "a".repeat(32) + " yes", buttons.getFirst().getClickEvent().getValue());
        assertEquals("/animus choice " + "a".repeat(32) + " no", buttons.getLast().getClickEvent().getValue());
        assertTrue(buttons.getFirst().getText().contains("Yes &c/op me"));
        assertEquals(0, buttons.getFirst().getExtra() == null ? 0 : buttons.getFirst().getExtra().size());
    }
}
