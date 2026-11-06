package art.arcane.animus.core.editor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EditorTaskFailuresTest {
    private Instant now = Instant.parse("2026-11-06T12:00:00Z");

    private Clock clock() {
        return new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now; }
        };
    }

    @Test
    void aRepeatingFailureWarnsOnceThenAtMostOncePerInterval() {
        List<String> warnings = new ArrayList<>();
        EditorTaskFailures failures = new EditorTaskFailures(warnings::add, clock(), Duration.ofMinutes(10));
        IllegalStateException failure = new IllegalStateException("NPC runtime requires the owner thread");

        failures.failed(failure);
        for (int minute = 1; minute < 10; minute++) { now = now.plus(Duration.ofMinutes(1)); failures.failed(failure); }
        assertEquals(List.of("Editor task failed: IllegalStateException: NPC runtime requires the owner thread"), warnings);

        now = now.plus(Duration.ofMinutes(1));
        failures.failed(failure);
        assertEquals("Editor task failed 10 more times in the last 10m: IllegalStateException: NPC runtime requires the owner thread", warnings.getLast());
        assertEquals(2, warnings.size());
    }

    @Test
    void aDifferentFailureWarnsAtOnce() {
        List<String> warnings = new ArrayList<>();
        EditorTaskFailures failures = new EditorTaskFailures(warnings::add, clock(), Duration.ofMinutes(10));
        failures.failed(new IllegalStateException("one"));
        failures.failed(new IllegalArgumentException("two"));
        assertEquals(List.of("Editor task failed: IllegalStateException: one", "Editor task failed: IllegalArgumentException: two"), warnings);
    }
}
