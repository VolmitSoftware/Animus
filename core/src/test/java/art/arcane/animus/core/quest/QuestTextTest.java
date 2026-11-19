package art.arcane.animus.core.quest;

import art.arcane.animus.core.text.RichText;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuestTextTest {
    @Test void aColourSetInTheTextCarriesOntoWrappedLines() {
        List<String> lines = QuestText.wrap("&#FF77AAThe herbalist needs fresh herbs from the river bank before dusk", "&7");
        assertTrue(lines.size() > 1, lines.toString());
        for (String line : lines) {
            String coloured = RichText.colorize(line);
            assertFalse(RichText.plain(coloured).contains("&#"), line);
            for (RichText.Run run : RichText.sectionRuns(coloured))
                assertEquals(OptionalInt.of(0xFF77AA), run.rgb(), line);
        }
    }

    @Test void plainTextWrapsInTheGivenColour() {
        List<String> lines = QuestText.wrap("Bring  three\nloaves of bread to the baker in the market square", "&f");
        assertEquals(List.of("Bring three loaves of bread to the", "baker in the market square"), lines.stream().map(RichText::plain).toList());
        for (String line : lines) for (RichText.Run run : RichText.runs(line)) assertEquals(OptionalInt.of(0xFFFFFF), run.rgb(), line);
        assertEquals(List.of(), QuestText.wrap("  ", "&7"));
    }
}
