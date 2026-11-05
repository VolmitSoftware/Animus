package art.arcane.animus.bukkit;

import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.behavior.builtin.DialogueFormat;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BukkitChoiceMessagesTest {
    @Test void choiceButtonsAreColouredClickableAndHoverable() {
        BaseComponent[] rendered = BukkitChoiceMessages.components(DialogueFormat.buttons("animus", "a".repeat(32),
                List.of(new BehaviorPlatform.ChoiceOption("yes", "Yes &#123456/op me"), new BehaviorPlatform.ChoiceOption("no", "No"))));
        List<TextComponent> buttons = new ArrayList<>();
        for (BaseComponent component : rendered) if (component.getClickEvent() != null) buttons.add((TextComponent) component);
        assertEquals(2, buttons.size());
        assertEquals(ClickEvent.Action.RUN_COMMAND, buttons.getFirst().getClickEvent().getAction());
        assertEquals("/animus choice " + "a".repeat(32) + " yes", buttons.getFirst().getClickEvent().getValue());
        assertEquals("/animus choice " + "a".repeat(32) + " no", buttons.getLast().getClickEvent().getValue());
        assertEquals("[ Yes /op me ]", buttons.getFirst().toPlainText());
        assertEquals(HoverEvent.Action.SHOW_TEXT, buttons.getFirst().getHoverEvent().getAction());
        List<ChatColor> colours = new ArrayList<>();
        for (BaseComponent child : buttons.getFirst().getExtra()) colours.add(child.getColorRaw());
        assertTrue(colours.contains(ChatColor.GREEN));
        assertTrue(colours.contains(ChatColor.of(new Color(0x123456))));
        List<ChatColor> declined = new ArrayList<>();
        for (BaseComponent child : buttons.getLast().getExtra()) declined.add(child.getColorRaw());
        assertTrue(declined.contains(ChatColor.RED));
    }
}
