package art.arcane.animus.core.hotload;

import art.arcane.animus.core.text.RichText;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HotloadMessagesTest {
    @Test void appliedLinesMatchAdaptsFormatWithAnimusBranding() {
        List<String> lines = HotloadMessages.applied("npcs/town/guard.json",
                List.of(new HotloadDiff.Change("name", "\"Guard\"", "\"Captain\"")), (String key) -> false);
        assertEquals(List.of(RichText.colorize("&7[&5Animus&7]: &aAnimus Hotloaded: &f[npcs/town/guard.json] &b[name] &7[\"Guard\" -> \"Captain\"]")), lines);
        assertEquals("[Animus]: Animus Hotloaded: [npcs/town/guard.json] [name] [\"Guard\" -> \"Captain\"]",
                RichText.sectionRuns(lines.getFirst()).stream().map(RichText.Run::text).reduce("", String::concat));
    }

    @Test void moreThanTwelveChangesAreTruncatedWithACount() {
        List<HotloadDiff.Change> changes = new ArrayList<>();
        for (int index = 0; index < 15; index++) changes.add(new HotloadDiff.Change("key" + index, "1", "2"));
        List<String> lines = HotloadMessages.applied("config.toml", changes, (String key) -> false);
        assertEquals(13, lines.size());
        assertEquals(RichText.colorize("&7[&5Animus&7]: &aAnimus Hotloaded: &f[config.toml] &7[3 additional changes omitted]"), lines.getLast());
    }

    @Test void restartOnlyKeysSaySoAndLiveKeysDoNot() {
        List<String> lines = HotloadMessages.applied("config.toml", List.of(
                new HotloadDiff.Change("npcs.enabled", "true", "false"),
                new HotloadDiff.Change("statusMessage", "\"a\"", "\"b\"")), (String key) -> SettingsLiveness.restartRequired(key, false));
        assertTrue(RichText.plain(lines.get(0)).endsWith("[true -> false] [restart required]"), lines.get(0));
        assertFalse(RichText.plain(lines.get(1)).contains("restart"), lines.get(1));
    }

    @Test void valuesAreShownAsWrittenNotAsColourCodes() {
        String line = HotloadMessages.applied("config.toml", List.of(new HotloadDiff.Change("shops.messages.bought", "\"&aBought\"", "\"<red>§lSold\"")),
                (String key) -> false).getFirst();
        String shown = RichText.sectionRuns(line).stream().map(RichText.Run::text).reduce("", String::concat);
        assertTrue(shown.endsWith("[\"&aBought\" -> \"<red>?lSold\"]"), shown);
    }

    @Test void refusalCarriesTheFieldError() {
        String line = HotloadMessages.refused("npcs/town/guard.json", "$.position.y: expected number", HotloadMessages.KEPT);
        assertEquals("[Animus]: Animus Hotload refused: [npcs/town/guard.json] $.position.y: expected number [kept the last valid version]",
                RichText.sectionRuns(line).stream().map(RichText.Run::text).reduce("", String::concat));
    }
}
