package art.arcane.animus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.command.CommandWords;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AnimusLogTest {
    private final AtomicLong nanos = new AtomicLong();
    private final AnimusLog log = new AnimusLog(nanos::get, Duration.ofMinutes(5));
    private final List<String> lines = new ArrayList<>();

    private void minutes(long count) { nanos.addAndGet(Duration.ofMinutes(count).toNanos()); }

    @Test void debugModeLogsEveryRoutineLine() {
        log.debug(true);
        log.routine("NPCs hit blocked route legs", "guard", "NPC guard stopped: terrain blocks its route leg", lines::add);
        log.routine("NPCs hit blocked route legs", "guard", "NPC guard teleported: terrain blocks its route leg", lines::add);
        assertEquals(2, lines.size());
        assertTrue(lines.getFirst().startsWith("NPC guard stopped"));
    }

    @Test void aRepeatedFailurePrintsOneTraceThenOneLine() {
        List<String> traces = new ArrayList<>();
        for (int attempt = 0; attempt < 3; attempt++)
            log.failure("Virtual attack failed", new IllegalStateException("wrapper", new IllegalArgumentException("no target")), lines::add, (String message, Throwable failure) -> traces.add(message));
        log.failure("Virtual attack failed", new IllegalArgumentException("other target"), lines::add, (String message, Throwable failure) -> traces.add(message));
        assertEquals(List.of("Virtual attack failed", "Virtual attack failed"), traces, "one trace per distinct cause");
        assertEquals(List.of("Virtual attack failed: IllegalArgumentException: no target", "Virtual attack failed: IllegalArgumentException: no target"), lines);
    }

    @Test void quietModeSummarizesAtMostOncePerWindow() {
        assertTrue(!log.debug(), "debug mode starts off");
        for (int npc = 0; npc < 12; npc++) log.routine("NPCs hit blocked route legs", "citizens-" + npc, "detail " + npc, lines::add);
        log.routine("NPCs hit blocked route legs", "citizens-3", "repeat", lines::add);
        log.routine("NPCs could not activate", "smith", "detail", lines::add);
        assertTrue(lines.isEmpty(), "nothing before the window passes: " + lines);
        minutes(5);
        log.routine("NPCs hit blocked route legs", "citizens-12", "detail", lines::add);
        assertEquals(List.of("13 NPCs hit blocked route legs, 1 NPCs could not activate in the last 5m; /animus debug mode for details"), lines);
        minutes(1);
        log.routine("NPCs hit blocked route legs", "citizens-1", "detail", lines::add);
        assertEquals(1, lines.size(), "the next summary waits for the next window");
        minutes(5);
        log.routine("NPCs hit blocked route legs", "citizens-2", "detail", lines::add);
        assertEquals("2 NPCs hit blocked route legs in the last 5m; /animus debug mode for details", lines.getLast());
    }

    @Test void theSummaryNamesTheLatestCaseAndPrintsWhenTheWindowEnds() {
        log.routine("NPCs hit route legs blocked by terrain", "guard", "detail", java.util.Optional.of("guard at waypoint 3, failed: UNREACHABLE"), lines::add);
        log.routine("NPCs hit route legs blocked by terrain", "smith", "detail", java.util.Optional.of("smith at waypoint 1, failed: UNREACHABLE"), lines::add);
        log.poll();
        assertTrue(lines.isEmpty(), "nothing before the window ends: " + lines);
        minutes(5);
        log.poll();
        assertEquals(List.of("2 NPCs hit route legs blocked by terrain (last: smith at waypoint 1, failed: UNREACHABLE) in the last 5m; /animus debug mode for details"), lines,
                "the window end prints without another routine line");
        log.poll();
        assertEquals(1, lines.size());
    }

    @Test void shutdownFlushesTheLastBatch() {
        log.routine("NPCs hit route legs the planner could not path", "guard", "detail", java.util.Optional.of("guard, failed: PARTIAL"), lines::add);
        minutes(1);
        log.flush();
        assertEquals(List.of("1 NPCs hit route legs the planner could not path (last: guard, failed: PARTIAL) in the last 1m; /animus debug mode for details"), lines);
        log.flush();
        assertEquals(1, lines.size(), "a flush with nothing pending prints nothing");
    }

    @Test void switchingModesDropsPendingCounts() {
        log.routine("kind", "a", "detail", lines::add);
        log.debug(true);
        log.debug(false);
        minutes(6);
        log.routine("kind", "b", "detail", lines::add);
        assertEquals(List.of("1 kind in the last 5m; /animus debug mode for details"), lines, "counts from before the switch are dropped");
    }

    @Test void activationWarningsGoThroughTheSharedGate() {
        AnimusLog shared = AnimusLog.shared();
        try {
            shared.debug(false);
            art.arcane.animus.core.runtime.ActivationWarnings warnings = new art.arcane.animus.core.runtime.ActivationWarnings(lines::add);
            warnings.failed("guard", "world missing");
            assertTrue(lines.isEmpty(), "quiet while debug mode is off: " + lines);
            shared.debug(true);
            warnings.failed("guard", "world missing");
            warnings.failed("guard", "world missing");
            assertEquals(List.of("NPC guard cannot activate: world missing"), lines, "once per NPC and failure with debug mode on");
        } finally { shared.debug(false); }
    }

    @Test void wordsKeepQuotesAndJsonTogether() {
        assertEquals(List.of("create", "guard", "Town Guard", "group=North Gate"), CommandWords.tokens("create guard \"Town Guard\" group=\"North Gate\""));
        assertEquals(List.of("edit", "guard", "{\"name\": \"A } B\", \"nested\": {\"x\": 1}}", "extra"), CommandWords.tokens("edit guard {\"name\": \"A } B\", \"nested\": {\"x\": 1}} extra"));
        assertEquals(List.of("say", "a \"quoted\" word"), CommandWords.tokens("say \"a \\\"quoted\\\" word\""));
        assertEquals(List.of("a", "b"), CommandWords.tokens("  a   b  "));
        assertEquals(List.of("x", ""), CommandWords.tokens("x \"\""));
        assertEquals(List.of("open", "{\"unclosed\": 1"), CommandWords.tokens("open {\"unclosed\": 1"));
    }
}
