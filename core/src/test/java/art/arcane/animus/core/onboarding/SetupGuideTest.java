package art.arcane.animus.core.onboarding;

import art.arcane.animus.core.migration.CitizensDropIn;
import art.arcane.animus.core.text.ChatLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SetupGuideTest {
    private static final CitizensDropIn.Progress NOTHING = new CitizensDropIn.Progress(CitizensDropIn.Phase.NONE, Optional.empty(), 0);
    private static final CitizensDropIn.Progress FOUND = new CitizensDropIn.Progress(CitizensDropIn.Phase.FOUND, Optional.of("plugins/Citizens/saves.yml"), 0);

    @Test void aFreshServerGetsCreateToolkitAndEditorInOrderWithTheDocsLast() {
        List<ChatLine> lines = SetupGuide.player(new SetupState(0, NOTHING, true, 0));
        assertEquals(List.of(
                "[Animus]: Setup | no NPCs | editor not paired",
                "1. Create an NPC where you stand [Create]",
                "2. Set a skin and placement [Toolkit]",
                "3. Edit NPCs in your browser [Pair editor]",
                "Every step in the wiki [Docs]"), plain(lines));
        assertEquals(List.of("suggest_command /animus create guard", "run_command /animus tools", "run_command /animus editor pair",
                "open_url https://docs.volmitsoftware.com/animus/20-first-ten-minutes"), clicks(lines));
    }

    @Test void foundCitizensDataPutsTheImportFirstAndKeepsSixLines() {
        List<ChatLine> lines = SetupGuide.player(new SetupState(0, FOUND, true, 0));
        assertTrue(lines.size() <= SetupGuide.MAXIMUM_LINES, plain(lines).toString());
        assertEquals("1. Import plugins/Citizens/saves.yml [Preview]", plain(lines).get(1));
        assertEquals("run_command /animus import citizens preview", clicks(List.of(lines.get(1))).getFirst());
        assertEquals("2. Create an NPC where you stand [Create]", plain(lines).get(2));
        assertEquals(6, lines.size());
    }

    @Test void eachImportPhaseNamesItsOwnStep() {
        ChatLine staged = SetupGuide.player(new SetupState(0, new CitizensDropIn.Progress(CitizensDropIn.Phase.STAGED, Optional.of("imports/saves.yml"), 0), true, 0)).get(1);
        assertEquals("1. Install the staged import [Apply]", plain(List.of(staged)).getFirst());
        assertEquals(List.of("suggest_command /animus import citizens apply"), clicks(List.of(staged)));
        ChatLine applied = SetupGuide.player(new SetupState(3, new CitizensDropIn.Progress(CitizensDropIn.Phase.APPLIED, Optional.of("imports/saves.yml"), 4), true, 0)).get(1);
        assertEquals("1. Turn on imported settings, 4 still off [Review]", plain(List.of(applied)).getFirst());
        assertEquals(List.of("run_command /animus import citizens review"), clicks(List.of(applied)));
        ChatLine done = SetupGuide.player(new SetupState(3, new CitizensDropIn.Progress(CitizensDropIn.Phase.DONE, Optional.empty(), 0), true, 0)).get(1);
        assertEquals("1. Citizens import done", plain(List.of(done)).getFirst());
        assertFalse(done.interactive());
    }

    @Test void doneStepsShowAsDoneWithoutAClick() {
        List<ChatLine> lines = SetupGuide.player(new SetupState(3, NOTHING, true, 2));
        assertEquals(List.of(
                "[Animus]: Setup | 3 NPCs | editor paired",
                "1. 3 NPCs ready",
                "2. Set a skin and placement [Toolkit]",
                "3. Web editor paired, 2 tokens",
                "Every step in the wiki [Docs]"), plain(lines));
        assertFalse(lines.get(1).interactive());
        assertFalse(lines.get(3).interactive());
        assertEquals("1. 1 NPC ready", plain(SetupGuide.player(new SetupState(1, NOTHING, true, 1))).get(1));
    }

    @Test void aDisabledEditorNamesTheConfigKeyInsteadOfPairing() {
        List<ChatLine> lines = SetupGuide.player(new SetupState(0, NOTHING, false, 0));
        assertEquals("[Animus]: Setup | no NPCs | editor off", plain(lines).getFirst());
        assertEquals("3. Web editor is off; set editor.enabled = true in config.toml", plain(lines).get(3));
        assertFalse(lines.get(3).interactive());
    }

    @Test void theConsoleGetsTheSameStepsWithTheCommandsWrittenOut() {
        assertEquals(List.of(
                "Setup | no NPCs | editor not paired",
                "1. Import plugins/Citizens/saves.yml: /animus import citizens preview",
                "2. Create an NPC where you stand: /animus create guard, in game",
                "3. Set a skin and placement: /animus tools, in game",
                "4. Edit NPCs in your browser: /animus editor pair",
                "Every step in the wiki: https://docs.volmitsoftware.com/animus/20-first-ten-minutes"), SetupGuide.console(new SetupState(0, FOUND, true, 0)));
        assertEquals("3. Web editor is off; set editor.enabled = true in config.toml", SetupGuide.console(new SetupState(2, NOTHING, false, 0)).get(3));
    }

    @Test void noLineHasADash() {
        for (SetupState state : List.of(new SetupState(0, FOUND, true, 0), new SetupState(5, NOTHING, false, 3))) {
            for (String line : plain(SetupGuide.player(state))) assertFalse(line.contains("—") || line.contains("–"), line);
            for (String line : SetupGuide.console(state)) assertFalse(line.contains("—") || line.contains("–"), line);
        }
    }

    static List<String> plain(List<ChatLine> lines) {
        return lines.stream().map((ChatLine line) -> PlainTextComponentSerializer.plainText().serialize(line.component())).toList();
    }

    static List<String> clicks(List<ChatLine> lines) {
        List<String> found = new ArrayList<>();
        for (ChatLine line : lines) clicks(line.component(), found);
        return found;
    }

    private static void clicks(Component root, List<String> found) {
        ClickEvent click = root.clickEvent();
        if (click != null) found.add(click.action().toString() + " " + ((ClickEvent.Payload.Text) click.payload()).value());
        for (Component child : root.children()) clicks(child, found);
    }
}
