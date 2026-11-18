package art.arcane.animus.core.command;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandSyntaxTest {
    private static String command(String text) {
        int start = text.indexOf("/animus");
        return text.substring(start, CommandSyntax.animus().end(text, start));
    }

    @Test void threeCoordinatesAfterTheRequiredValuesBelongToTheCommand() {
        assertEquals("/animus navigation go guard 10 64 -3", command("Try /animus navigation go guard 10 64 -3 then wait"));
        assertEquals("/animus navigation go guard ~ ~1 ~", command("/animus navigation go guard ~ ~1 ~"));
    }

    @Test void aValueOfTheWrongKindEndsTheCommand() {
        assertEquals("/animus proof control start 12", command("/animus proof control start 12 now"));
        assertEquals("/animus proof control start", command("/animus proof control start now"));
        assertEquals("/animus list 2", command("/animus list 2 shows more"));
    }

    @Test void keysAndQuotedValuesStayWhole() {
        assertEquals("/animus create guard name=\"Town Guard\"", command("/animus create guard name=\"Town Guard\" adds it"));
        assertEquals("/animus speak guard \"Hello there\"", command("/animus speak guard \"Hello there\" says it"));
        assertEquals("/animus list", command("/animus list colour=red"), "a key the command lacks is text");
    }

    @Test void helpTakesAPageAllOrATopic() {
        assertEquals("/animus help 2", command("/animus help 2 lists more"));
        assertEquals("/animus help all", command("/animus help all lists every command"));
        assertEquals("/animus help npc skin", command("/animus help npc skin explains it"));
        assertEquals("/animus npc help", command("/animus npc help shows the NPC commands"));
    }

    @Test void aGroupWithoutACommandEndsAtTheGroup() {
        assertEquals("/animus import citizens", command("/animus import citizens accept all"));
        assertEquals("/animus", command("Type /animus for help"));
    }

    @Test void aColourCodeOrPunctuationEndsTheCommand() {
        assertEquals("/animus debug network", command("/animus debug network§7, §fperformance"));
        assertEquals("/animus spawn guard", command("(/animus spawn guard)"));
        assertEquals("/animus spawn guard", command("/animus spawn guard."));
    }
}
