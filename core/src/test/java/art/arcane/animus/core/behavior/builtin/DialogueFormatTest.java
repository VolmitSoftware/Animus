package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.ChoiceOption;
import art.arcane.animus.core.text.TextPart;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DialogueFormatTest {
    static final String TOKEN = "a".repeat(32);

    @Test void speechColorizesTheSpeakerAndEveryNotationInTheText() {
        assertEquals("§r§6Greeter§8 » §fWelcome §ahome", DialogueFormat.speech("Greeter", "Welcome &ahome"));
        assertEquals("§r§x§1§2§3§4§5§6Bob§8 » §f§c❤ §x§a§b§c§d§e§fhi", DialogueFormat.speech("&#123456Bob", "<red>❤ &#ABCDEFhi"));
    }

    @Test void unstyledAnswersGetBracketsAPaletteHoverAndARedDecline() {
        List<TextPart> parts = DialogueFormat.buttons("animus", TOKEN, List.of(new ChoiceOption("yes", "Yes"), new ChoiceOption("rumours", "Rumours"), new ChoiceOption("no", "No")));
        List<TextPart> buttons = parts.stream().filter((TextPart part) -> part.command().isPresent()).toList();
        assertEquals(3, buttons.size());
        assertEquals("§8[ §aYes §8]", buttons.get(0).text());
        assertEquals("§8[ §bRumours §8]", buttons.get(1).text());
        assertEquals("§8[ §cNo §8]", buttons.get(2).text());
        assertEquals(Optional.of("/animus choice " + TOKEN + " yes"), buttons.get(0).command());
        assertEquals(Optional.of("§a§lYes\n§7Click to choose this answer"), buttons.get(0).hover());
    }

    @Test void definedColourHoverAndStyleWin() {
        ChoiceOption hire = new ChoiceOption("hire", "&lHire", Optional.of("#ffaa00"), Optional.of("&7Costs &e5 emeralds"), Optional.of("underlined"));
        TextPart button = DialogueFormat.buttons("animus", TOKEN, List.of(hire)).get(1);
        assertEquals("§8[ §x§f§f§a§a§0§0§n§lHire §8]", button.text());
        assertEquals(Optional.of("§7Costs §e5 emeralds"), button.hover());
        assertEquals("§c", DialogueFormat.colour(new ChoiceOption("no", "No", Optional.of("chartreuse"), Optional.empty(), Optional.empty()), 0, 2));
    }
}
