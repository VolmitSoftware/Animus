package art.arcane.animus.core.editor;

import art.arcane.animus.core.config.EditorSettings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Console lines for refusals the browser sees but the console did not: one summary per token per minute, other refusals once. */
class EditorWarningsTest {
    private final EditorHarness.TestClock clock = new EditorHarness.TestClock();
    private final List<String> lines = new ArrayList<>();
    private final EditorWarnings warnings = new EditorWarnings(lines::add, clock);

    @Test
    void rateLimit_aBurstIsOneLinePerTokenOnceItQuietsDown() {
        for (int index = 0; index < 1062; index++) warnings.limited("tok_a");
        warnings.limited("tok_a");
        warnings.limited("tok_b");
        warnings.tick(EditorSettings.DEFAULT);
        assertEquals(List.of(), lines, "nothing while the burst still runs");
        clock.advance(Duration.ofSeconds(6));
        warnings.tick(EditorSettings.DEFAULT);
        assertEquals(2, lines.size(), lines::toString);
        assertTrue(lines.contains("Editor tok_a: 1063 requests refused by the rate limit in the last 6 s ([editor] requestsPerSecond = 20, concurrentRequestsPerToken = 8)"), lines::toString);
        assertTrue(lines.contains("Editor tok_b: 1 request refused by the rate limit in the last 6 s ([editor] requestsPerSecond = 20, concurrentRequestsPerToken = 8)"), lines::toString);
        warnings.limited("tok_a");
        clock.advance(Duration.ofSeconds(10));
        warnings.tick(EditorSettings.DEFAULT);
        assertEquals(2, lines.size(), "at most one line per token per minute");
        clock.advance(Duration.ofSeconds(50));
        warnings.tick(EditorSettings.DEFAULT);
        assertEquals(3, lines.size());
        clock.advance(Duration.ofMinutes(5));
        warnings.tick(EditorSettings.DEFAULT);
        assertEquals(3, lines.size(), "a quiet token logs nothing");
    }

    @Test
    void rateLimit_aSteadyStreamStillLogsEveryMinute() {
        for (int second = 0; second < 61; second++) {
            warnings.limited("tok_a");
            clock.advance(Duration.ofSeconds(1));
            warnings.tick(EditorSettings.DEFAULT);
        }
        assertEquals(1, lines.size(), lines::toString);
        assertTrue(lines.getFirst().startsWith("Editor tok_a: 60 requests refused"), lines::toString);
    }

    @Test
    void once_logsAKeyAgainOnlyAfterItsQuietTime() {
        warnings.once("origin http://x", Duration.ofHours(1), "Editor refused origin http://x");
        warnings.once("origin http://x", Duration.ofHours(1), "Editor refused origin http://x");
        warnings.once("origin http://y", Duration.ofHours(1), "Editor refused origin http://y");
        assertEquals(List.of("Editor refused origin http://x", "Editor refused origin http://y"), lines);
        clock.advance(Duration.ofMinutes(61));
        warnings.once("origin http://x", Duration.ofHours(1), "Editor refused origin http://x");
        assertEquals(3, lines.size());
    }
}
