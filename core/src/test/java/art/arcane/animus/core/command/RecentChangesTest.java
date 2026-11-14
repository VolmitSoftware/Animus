package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What {@code /animus undo} remembers: each player's own changes, newest first, a bounded number of them for a bounded time. */
class RecentChangesTest {
    private static final String FIRST = "0f1e2d3c-4b5a-4968-8776-655443322110", SECOND = "1f1e2d3c-4b5a-4968-8776-655443322110";

    /** A clock the test moves. */
    private static final class Moving extends Clock {
        Instant now = Instant.parse("2026-11-12T10:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test void eachPlayerUndoesTheirOwnNewestChange() {
        RecentChanges changes = new RecentChanges();
        UUID alex = UUID.randomUUID(), sam = UUID.randomUUID();
        assertTrue(changes.latest(alex).isEmpty());
        changes.record(alex, FIRST, "Made guard", PermissionNodes.NPC_CREATE);
        changes.record(alex, SECOND, "Deleted smith", PermissionNodes.NPC_DELETE);
        assertEquals(Optional.of(new RecentChanges.Change(SECOND, "Deleted smith", PermissionNodes.NPC_DELETE)), changes.latest(alex));
        assertTrue(changes.latest(sam).isEmpty(), "another player's changes are not theirs");
        changes.forget(alex, SECOND);
        assertEquals(Optional.of(new RecentChanges.Change(FIRST, "Made guard", PermissionNodes.NPC_CREATE)), changes.latest(alex));
    }

    @Test void aChangeIsFoundByTheFirstCharactersAChipCarries() {
        RecentChanges changes = new RecentChanges();
        UUID alex = UUID.randomUUID();
        changes.record(alex, FIRST, "Made guard", "");
        assertEquals(Optional.of(new RecentChanges.Change(FIRST, "Made guard", "")), changes.find(alex, "0f1e2d3c"));
        assertTrue(changes.find(alex, "1f1e2d3c").isEmpty());
        assertTrue(changes.find(UUID.randomUUID(), "0f1e2d3c").isEmpty());
        changes.record(alex, SECOND, "Deleted smith", "");
        assertEquals(List.of("1f1e2d3c", "0f1e2d3c"), changes.ids(alex), "completion offers the short ids, newest first");
    }

    @Test void theSameChangeShownTwiceIsKeptOnceAndOnlyTheNewestAreKept() {
        RecentChanges changes = new RecentChanges();
        UUID alex = UUID.randomUUID();
        changes.record(alex, FIRST, "Made guard", "");
        changes.record(alex, SECOND, "Deleted smith", "");
        changes.record(alex, FIRST, "Made guard", "");
        assertEquals(FIRST, changes.latest(alex).orElseThrow().transaction());
        changes.forget(alex, FIRST);
        assertEquals(SECOND, changes.latest(alex).orElseThrow().transaction());
        for (int index = 0; index < RecentChanges.KEPT + 5; index++) changes.record(alex, UUID.randomUUID().toString(), "Change " + index, "");
        assertTrue(changes.find(alex, SECOND).isEmpty(), "the oldest fall off");
        assertEquals("Change " + (RecentChanges.KEPT + 4), changes.latest(alex).orElseThrow().what());
    }

    @Test void onlyThePlayersWhoChangedSomethingMostRecentlyAreKept() {
        RecentChanges changes = new RecentChanges();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        changes.record(first, FIRST, "Made guard", "");
        changes.record(second, SECOND, "Made smith", "");
        for (int index = 0; index < RecentChanges.PLAYERS - 2; index++) changes.record(UUID.randomUUID(), UUID.randomUUID().toString(), "Change " + index, "");
        changes.record(first, SECOND, "Deleted smith", "");
        changes.record(UUID.randomUUID(), UUID.randomUUID().toString(), "One more", "");
        assertTrue(changes.latest(second).isEmpty(), "the player who changed something longest ago is forgotten first");
        assertEquals(SECOND, changes.latest(first).orElseThrow().transaction(), "a new change keeps a player's list");
    }

    @Test void aChangeOlderThanItsAgeIsNoLongerOffered() {
        Moving clock = new Moving();
        RecentChanges changes = new RecentChanges(clock);
        UUID alex = UUID.randomUUID();
        changes.record(alex, FIRST, "Made guard", "");
        clock.now = clock.now.plus(RecentChanges.AGE).minus(Duration.ofSeconds(1));
        changes.record(alex, SECOND, "Deleted smith", "");
        assertEquals(List.of("1f1e2d3c", "0f1e2d3c"), changes.ids(alex));
        clock.now = clock.now.plus(Duration.ofSeconds(2));
        assertEquals(List.of("1f1e2d3c"), changes.ids(alex));
        assertTrue(changes.find(alex, "0f1e2d3c").isEmpty());
        clock.now = clock.now.plus(RecentChanges.AGE);
        assertTrue(changes.latest(alex).isEmpty());
    }
}
