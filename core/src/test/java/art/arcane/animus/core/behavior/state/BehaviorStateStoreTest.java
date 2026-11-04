package art.arcane.animus.core.behavior.state;

import static org.junit.jupiter.api.Assertions.*;

import art.arcane.animus.core.behavior.BehaviorProgram.Lifetime;
import art.arcane.animus.core.behavior.BehaviorProgram.Scope;
import art.arcane.animus.core.behavior.BehaviorProgram.ScopedVariable;
import art.arcane.animus.core.behavior.BehaviorProgram.Type;
import art.arcane.animus.core.behavior.BehaviorProgram.Value;
import art.arcane.animus.core.behavior.BehaviorState;
import art.arcane.animus.core.behavior.state.BehaviorStateStore.Limits;
import art.arcane.animus.core.behavior.state.BehaviorStateStore.Purge;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BehaviorStateStoreTest {
    private static final UUID NPC = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OTHER_NPC = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final String BEHAVIOR = "example:quests/town";
    private static final ScopedVariable NPC_PERSISTENT = new ScopedVariable(Scope.NPC, Lifetime.PERSISTENT);
    private static final ScopedVariable RELATIONSHIP_PERSISTENT = new ScopedVariable(Scope.RELATIONSHIP, Lifetime.PERSISTENT);
    private static final ScopedVariable PLAYER_PERSISTENT = new ScopedVariable(Scope.PLAYER, Lifetime.PERSISTENT);
    private static final ScopedVariable GLOBAL_PERSISTENT = new ScopedVariable(Scope.GLOBAL, Lifetime.PERSISTENT);
    private static final ScopedVariable NPC_TRANSIENT = new ScopedVariable(Scope.NPC, Lifetime.TRANSIENT);
    private static final Value ZERO = integer(0);
    private static final BehaviorState.Subject SUBJECT = new BehaviorState.Subject(BEHAVIOR, NPC, Optional.of(PLAYER));
    private static final String NPC_PATH = "state/behavior-state/npc/" + NPC + "/example/quests/town.json";

    @TempDir Path root;

    private static Value integer(long value) { return new Value(Type.INTEGER, value); }
    private static Value text(String value) { return new Value(Type.STRING, value); }

    private static void set(BehaviorStateStore state, String name, Value value, ScopedVariable declaration) {
        state.write(SUBJECT, Map.of(name, value), Map.of(name, declaration));
    }

    private static Value get(BehaviorStateStore state, String name, ScopedVariable declaration) {
        return state.read(SUBJECT, name, declaration, ZERO);
    }

    private static final class Lane implements Executor {
        final List<Runnable> queued = new ArrayList<>();
        boolean reject;
        @Override public void execute(Runnable command) {
            if (reject) throw new RejectedExecutionException("full");
            queued.add(command);
        }
        void drain() {
            List<Runnable> copy = List.copyOf(queued);
            queued.clear();
            copy.forEach(Runnable::run);
        }
    }

    @Test
    void persistentStateSurvivesCleanRestartAcrossEveryScopeAndType() throws Exception {
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults());
            set(state, "n", integer(Long.MAX_VALUE), NPC_PERSISTENT);
            set(state, "d", new Value(Type.DECIMAL, 0.1d), NPC_PERSISTENT);
            set(state, "b", new Value(Type.BOOLEAN, true), RELATIONSHIP_PERSISTENT);
            set(state, "s", text("line\n\"quoted\" é"), PLAYER_PERSISTENT);
            set(state, "g", integer(3), GLOBAL_PERSISTENT);
            set(state, "t", integer(9), NPC_TRANSIENT);
            state.close();
        }
        assertTrue(Files.exists(root.resolve(NPC_PATH)));
        assertTrue(Files.exists(root.resolve("state/behavior-state/relationship/" + NPC + "/" + PLAYER + "/example/quests/town.json")));
        assertTrue(Files.exists(root.resolve("state/behavior-state/player/" + PLAYER + "/example/quests/town.json")));
        assertTrue(Files.exists(root.resolve("state/behavior-state/global/example/quests/town.json")));
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults());
            assertEquals(integer(Long.MAX_VALUE), get(state, "n", NPC_PERSISTENT));
            assertEquals(new Value(Type.DECIMAL, 0.1d), state.read(SUBJECT, "d", NPC_PERSISTENT, new Value(Type.DECIMAL, 0d)));
            assertEquals(new Value(Type.BOOLEAN, true), state.read(SUBJECT, "b", RELATIONSHIP_PERSISTENT, new Value(Type.BOOLEAN, false)));
            assertEquals(text("line\n\"quoted\" é"), state.read(SUBJECT, "s", PLAYER_PERSISTENT, text("")));
            assertEquals(integer(3), get(state, "g", GLOBAL_PERSISTENT));
            assertEquals(ZERO, get(state, "t", NPC_TRANSIENT), "Transient state does not survive restart");
            assertEquals(5, state.status().persistentKeys());
            assertEquals(0, state.status().transientKeys());
        }
    }

    @Test
    void unsetKeysReturnTheDeclaredInitialValueAndConsumeNoBudget() throws Exception {
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults());
            assertEquals(ZERO, get(state, "missing", NPC_PERSISTENT));
            assertEquals(0, state.status().persistentKeys());
        }
    }

    @Test
    void writesAreCoalescedUntilTheFlushIntervalAndOneTaskIsQueued() throws Exception {
        AtomicLong clock = new AtomicLong();
        Lane lane = new Lane();
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, new Limits(16, 16, 4096, 65536, 1000), clock::get);
            state.attach(lane);
            state.maintain();
            assertTrue(lane.queued.isEmpty(), "No flush without dirty state");
            for (int value = 0; value < 50; value++) {
                set(state, "v", integer(value), NPC_PERSISTENT);
                state.maintain();
                clock.addAndGet(1_000_000);
            }
            assertTrue(lane.queued.isEmpty(), "No flush before the interval");
            assertFalse(Files.exists(root.resolve(NPC_PATH)), "A set does not write through");
            clock.addAndGet(1_000_000_000L);
            state.maintain();
            state.maintain();
            assertEquals(1, lane.queued.size(), "Repeated maintenance queues one flush");
            lane.drain();
            assertEquals(1, state.status().flushes());
            assertEquals(0, state.status().dirtyDocuments());
            assertEquals(49L, values(NPC_PATH).getAsJsonObject("v").get("value").getAsLong());
            state.maintain();
            assertTrue(lane.queued.isEmpty(), "Clean state stays quiet");
            set(state, "v", integer(49), NPC_PERSISTENT);
            assertEquals(0, state.status().dirtyDocuments(), "An unchanged value is not dirty");
        }
    }

    @Test
    void writesDuringAFlushRemainDirtyForTheNextOne() throws Exception {
        AtomicLong clock = new AtomicLong();
        Lane lane = new Lane();
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, new Limits(16, 16, 4096, 65536, 1000), clock::get);
            state.attach(lane);
            set(state, "v", integer(1), NPC_PERSISTENT);
            clock.addAndGet(2_000_000_000L);
            state.maintain();
            Runnable queued = lane.queued.getFirst();
            lane.queued.clear();
            state.write(SUBJECT, Map.of("w", integer(2)), Map.of("w", NPC_PERSISTENT));
            queued.run();
            assertEquals(0, state.status().dirtyDocuments(), "The queued flush snapshots at run time");
            clock.addAndGet(2_000_000_000L);
            set(state, "v", integer(3), NPC_PERSISTENT);
            assertEquals(1, state.status().dirtyDocuments());
            state.flushNow();
            assertEquals(3L, values(NPC_PATH).getAsJsonObject("v").get("value").getAsLong());
            assertEquals(2L, values(NPC_PATH).getAsJsonObject("w").get("value").getAsLong());
        }
    }

    @Test
    void stateFlushesLeaveNoBackupReceiptsOrPendingJournal() throws Exception {
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults());
            for (int value = 1; value < 5; value++) {
                set(state, "v", integer(value), NPC_PERSISTENT);
                state.flushNow();
            }
            assertEquals(4, state.status().flushes());
        }
        assertFalse(Files.exists(root.resolve("backups")));
        assertFalse(Files.exists(root.resolve("state/pending.json")));
    }

    @Test
    void rejectedLaneKeepsStateDirtyAndRetriesLater() throws Exception {
        AtomicLong clock = new AtomicLong();
        Lane lane = new Lane();
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, new Limits(16, 16, 4096, 65536, 1000), clock::get);
            state.attach(lane);
            set(state, "v", integer(1), NPC_PERSISTENT);
            clock.addAndGet(2_000_000_000L);
            lane.reject = true;
            state.maintain();
            assertEquals(1, state.status().dirtyDocuments());
            lane.reject = false;
            state.maintain();
            assertTrue(lane.queued.isEmpty(), "Retry waits for another interval");
            clock.addAndGet(2_000_000_000L);
            state.maintain();
            lane.drain();
            assertEquals(0, state.status().dirtyDocuments());
        }
    }

    @Test
    void failedFlushKeepsStateDirtyReportsFailureAndRecovers() throws Exception {
        Path blocker = root.resolve("state/behavior-state/npc");
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults());
            set(state, "v", integer(1), NPC_PERSISTENT);
            Files.createDirectories(blocker.getParent());
            Files.writeString(blocker, "not a directory");
            assertThrows(IOException.class, state::flushNow);
            assertEquals(1, state.status().dirtyDocuments());
            assertEquals(1, state.status().flushFailures());
            assertFalse(state.status().lastFailure().isEmpty());
            assertEquals(integer(1), get(state, "v", NPC_PERSISTENT));
            Files.delete(blocker);
            state.flushNow();
            assertEquals(0, state.status().dirtyDocuments());
            assertEquals(1L, values(NPC_PATH).getAsJsonObject("v").get("value").getAsLong());
        }
    }

    @Test
    void crashAfterJournalPublicationRollsForwardOnNextOpen() throws Exception {
        String accepted;
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults());
            set(state, "v", integer(1), NPC_PERSISTENT);
            state.flushNow();
            accepted = Files.readString(root.resolve(NPC_PATH));
        }
        // A process dies after publishing its journal and before replacing the document.
        String after = accepted.replace("\"value\": 1", "\"value\": 2");
        assertNotEquals(accepted, after);
        JsonObject entry = new JsonObject();
        entry.addProperty("path", NPC_PATH);
        entry.addProperty("before", accepted);
        entry.addProperty("after", after);
        JsonArray entries = new JsonArray();
        entries.add(entry);
        JsonObject journal = new JsonObject();
        journal.addProperty("id", UUID.randomUUID().toString());
        journal.addProperty("receipt", false);
        journal.add("entries", entries);
        Files.writeString(root.resolve("state/pending.json"), JsonDocuments.write(journal));
        Files.writeString(root.resolve(NPC_PATH).resolveSibling(".animus-crashed.tmp"), "{\"torn");
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults());
            assertEquals(integer(2), get(state, "v", NPC_PERSISTENT));
            assertEquals(0, state.status().invalidDocuments());
        }
        assertFalse(Files.exists(root.resolve("state/pending.json")));
        assertFalse(Files.exists(root.resolve("backups")), "A rolled-forward state journal keeps no receipt");
    }

    @Test
    void crashBeforeJournalPublicationKeepsTheLastFlushedValue() throws Exception {
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults());
            set(state, "v", integer(1), NPC_PERSISTENT);
            state.flushNow();
            set(state, "v", integer(2), NPC_PERSISTENT);
            // No flush and no close: the unflushed value is lost, the file stays whole.
        }
        Files.writeString(root.resolve("state/.animus-journal.tmp"), "{\"torn");
        try (DocumentStore documents = new DocumentStore(root)) {
            assertEquals(integer(1), get(BehaviorStateStore.open(documents, Limits.defaults()), "v", NPC_PERSISTENT));
        }
    }

    @Test
    void corruptDocumentIsPreservedAndBlocksOnlyItsOwnSubjectWithAnExactError() throws Exception {
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults());
            set(state, "v", integer(1), NPC_PERSISTENT);
            set(state, "g", integer(2), GLOBAL_PERSISTENT);
            state.close();
        }
        Files.writeString(root.resolve(NPC_PATH), "{\"schemaVersion\":1,");
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults());
            assertEquals(1, state.status().invalidDocuments());
            assertEquals(NPC_PATH, state.problems().getFirst().path());
            assertEquals(integer(2), get(state, "g", GLOBAL_PERSISTENT));
            BehaviorState.Rejected read = assertThrows(BehaviorState.Rejected.class, () -> get(state, "v", NPC_PERSISTENT));
            assertEquals("behavior-state-document-invalid", read.diagnostic().code());
            assertEquals(NPC_PATH, read.diagnostic().field());
            assertThrows(BehaviorState.Rejected.class, () -> set(state, "v", integer(3), NPC_PERSISTENT));
            state.close();
        }
        assertEquals("{\"schemaVersion\":1,", Files.readString(root.resolve(NPC_PATH)), "Invalid bytes stay in place");
    }

    @Test
    void semanticallyTamperedDocumentsAreRejected() throws Exception {
        Path file = root.resolve(NPC_PATH);
        Files.createDirectories(file.getParent());
        String good = "{\"schemaVersion\":1,\"behavior\":\"" + BEHAVIOR + "\",\"scope\":\"npc\",\"npc\":\"" + NPC + "\",\"values\":{\"v\":{\"type\":\"integer\",\"value\":1}}}";
        for (String bad : List.of(
                good.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                good.replace(BEHAVIOR, "example:other"),
                good.replace("\"scope\":\"npc\"", "\"scope\":\"global\""),
                good.replace(NPC.toString(), OTHER_NPC.toString()),
                good.replace("\"value\":1", "\"value\":\"1\""),
                good.replace("\"value\":1", "\"value\":1.5"),
                good.replace("\"type\":\"integer\"", "\"type\":\"list\""),
                good.replace("{\"v\":", "{\"BAD NAME\":"))) {
            Files.writeString(file, bad);
            try (DocumentStore documents = new DocumentStore(root)) {
                assertEquals(1, BehaviorStateStore.open(documents, Limits.defaults()).status().invalidDocuments(), bad);
            }
        }
        Files.writeString(file, good);
        try (DocumentStore documents = new DocumentStore(root)) {
            assertEquals(0, BehaviorStateStore.open(documents, Limits.defaults()).status().invalidDocuments());
        }
    }

    @Test
    void keyBudgetIsEnforcedWhileOverwritesStayAllowed() throws Exception {
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, new Limits(2, 1, 4096, 65536, 1000));
            set(state, "a", integer(1), NPC_PERSISTENT);
            set(state, "b", integer(1), NPC_PERSISTENT);
            BehaviorState.Rejected over = assertThrows(BehaviorState.Rejected.class, () -> set(state, "c", integer(1), NPC_PERSISTENT));
            assertEquals("behavior-state-key-budget", over.diagnostic().code());
            assertEquals("$.variables.c", over.diagnostic().field());
            set(state, "a", integer(2), NPC_PERSISTENT);
            assertEquals(integer(2), get(state, "a", NPC_PERSISTENT));
            set(state, "t", integer(1), NPC_TRANSIENT);
            BehaviorState.Rejected transientOver = assertThrows(BehaviorState.Rejected.class, () -> set(state, "u", integer(1), NPC_TRANSIENT));
            assertEquals("behavior-state-key-budget", transientOver.diagnostic().code());
            assertEquals(2, state.status().persistentKeys());
            assertEquals(1, state.status().transientKeys());
        }
    }

    @Test
    void valueAndTotalByteBudgetsRejectWithoutPartialWrites() throws Exception {
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, new Limits(16, 16, 8, 32, 1000));
            BehaviorState.Rejected size = assertThrows(BehaviorState.Rejected.class, () -> set(state, "s", text("123456789"), NPC_PERSISTENT));
            assertEquals("behavior-state-value-size", size.diagnostic().code());
            assertEquals("$.variables.s", size.diagnostic().field());
            set(state, "s", text("12345678"), NPC_PERSISTENT);
            set(state, "x", text("12345678"), NPC_PERSISTENT);
            BehaviorState.Rejected total = assertThrows(BehaviorState.Rejected.class, () -> state.write(SUBJECT,
                    Map.of("y", text("12345678"), "z", text("12345678")), Map.of("y", NPC_PERSISTENT, "z", NPC_PERSISTENT)));
            assertEquals("behavior-state-byte-budget", total.diagnostic().code());
            assertEquals(2, state.status().persistentKeys());
            assertEquals(ZERO, get(state, "y", NPC_PERSISTENT), "A rejected batch writes nothing");
        }
    }

    @Test
    void loweredBudgetsPreserveLoadedDataAndRejectOnlyGrowth() throws Exception {
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults());
            set(state, "a", integer(1), NPC_PERSISTENT);
            set(state, "b", integer(2), NPC_PERSISTENT);
            state.close();
        }
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, new Limits(1, 1, 4096, 65536, 1000));
            assertEquals(integer(1), get(state, "a", NPC_PERSISTENT));
            assertEquals(integer(2), get(state, "b", NPC_PERSISTENT));
            assertThrows(BehaviorState.Rejected.class, () -> set(state, "c", integer(1), NPC_PERSISTENT));
            set(state, "a", integer(5), NPC_PERSISTENT);
            assertEquals(integer(5), get(state, "a", NPC_PERSISTENT));
        }
    }

    @Test
    void oneSubjectOfOneBehaviorHoldsABoundedKeyCount() throws Exception {
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults());
            for (int index = 0; index < 128; index++) set(state, "k" + index, integer(index), NPC_PERSISTENT);
            BehaviorState.Rejected over = assertThrows(BehaviorState.Rejected.class, () -> set(state, "more", integer(1), NPC_PERSISTENT));
            assertEquals("behavior-state-key-budget", over.diagnostic().code());
            set(state, "k0", integer(5), NPC_PERSISTENT);
            state.flushNow();
            assertEquals(128, values(NPC_PATH).size());
        }
    }

    @Test
    void invalidLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Limits(0, 1, 1, 1, 1000));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 0, 1, 1, 1000));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 4097, 1, 1000));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 1, 0, 1000));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 1, 1, 99));
    }

    @Test
    void largeDirtySetsFlushInBoundedBatchesThatStayInline() throws Exception {
        String large = "\u0001".repeat(4096);
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults());
            for (int npc = 0; npc < 200; npc++) {
                BehaviorState.Subject subject = new BehaviorState.Subject(BEHAVIOR, new UUID(1, npc), Optional.empty());
                state.write(subject, Map.of("s", text(large)), Map.of("s", NPC_PERSISTENT));
            }
            state.flushNow();
            assertEquals(0, state.status().dirtyDocuments());
            assertTrue(state.status().flushes() > 1, "Expected several bounded commits");
            state.close();
        }
        assertFalse(Files.exists(root.resolve("state/objects/transaction-data")), "State journals stay inline");
        try (DocumentStore documents = new DocumentStore(root)) {
            assertEquals(200, BehaviorStateStore.open(documents, Limits.defaults()).status().persistentKeys());
        }
    }

    @Test
    void closeSealsWritesThenFlushesAndIsIdempotent() throws Exception {
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults());
            set(state, "v", integer(1), NPC_PERSISTENT);
            state.close();
            state.close();
            assertTrue(state.status().closed());
            assertEquals(0, state.status().dirtyDocuments());
            BehaviorState.Rejected sealed = assertThrows(BehaviorState.Rejected.class, () -> set(state, "v", integer(2), NPC_PERSISTENT));
            assertEquals("behavior-state-closed", sealed.diagnostic().code());
        }
        assertEquals(1L, values(NPC_PATH).getAsJsonObject("v").get("value").getAsLong());
    }

    @Test
    void stateForMissingBehaviorsAndNpcsIsPreservedUntilPurged() throws Exception {
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults());
            set(state, "v", integer(1), NPC_PERSISTENT);
            state.close();
        }
        // Nothing here knows about behaviors or NPC definitions: no source exists for either subject.
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults());
            assertEquals(1, state.status().persistentKeys());
            state.close();
        }
        assertTrue(Files.exists(root.resolve(NPC_PATH)));
    }

    @Test
    void purgeSelectorsRemoveExactlyTheirSubjectsDurably() throws Exception {
        BehaviorState.Subject otherNpc = new BehaviorState.Subject(BEHAVIOR, OTHER_NPC, Optional.of(PLAYER));
        BehaviorState.Subject otherBehavior = new BehaviorState.Subject("example:other", NPC, Optional.of(PLAYER));
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults());
            for (BehaviorState.Subject subject : List.of(SUBJECT, otherNpc, otherBehavior)) {
                state.write(subject, Map.of("n", integer(1), "r", integer(1), "p", integer(1), "g", integer(1), "t", integer(1)),
                        Map.of("n", NPC_PERSISTENT, "r", RELATIONSHIP_PERSISTENT, "p", PLAYER_PERSISTENT, "g", GLOBAL_PERSISTENT, "t", NPC_TRANSIENT));
            }
            state.flushNow();
            int before = state.status().persistentKeys();
            BehaviorStateStore.PurgeResult relationship = state.purge(Purge.relationship(OTHER_NPC, PLAYER));
            assertEquals(1, relationship.keys());
            assertEquals(ZERO, state.read(otherNpc, "r", RELATIONSHIP_PERSISTENT, ZERO));
            assertEquals(before - 1, state.status().persistentKeys());
            BehaviorStateStore.PurgeResult npc = state.purge(Purge.npc(NPC));
            assertEquals(6, npc.keys(), "NPC, relationship and transient state of both behaviors");
            assertEquals(ZERO, state.read(SUBJECT, "n", NPC_PERSISTENT, ZERO));
            assertEquals(integer(1), state.read(otherNpc, "n", NPC_PERSISTENT, ZERO));
            assertEquals(integer(1), state.read(SUBJECT, "g", GLOBAL_PERSISTENT, ZERO));
            assertFalse(Files.exists(root.resolve(NPC_PATH)));
            assertFalse(Files.exists(root.resolve("state/behavior-state/npc/" + NPC)), "Empty directories do not accumulate");
            state.purge(Purge.player(PLAYER));
            assertEquals(ZERO, state.read(SUBJECT, "p", PLAYER_PERSISTENT, ZERO));
            state.purge(Purge.behavior(BEHAVIOR));
            assertEquals(ZERO, state.read(SUBJECT, "g", GLOBAL_PERSISTENT, ZERO));
            assertEquals(integer(1), state.read(otherBehavior, "g", GLOBAL_PERSISTENT, ZERO));
            assertEquals(1, state.purge(Purge.all()).keys());
            assertEquals(0, state.status().persistentKeys());
            assertEquals(0, state.status().transientKeys());
        }
        if (Files.exists(root.resolve("state/behavior-state"))) {
            try (Stream<Path> files = Files.walk(root.resolve("state/behavior-state"))) {
                assertEquals(0, files.filter(Files::isRegularFile).count());
            }
        }
        try (DocumentStore documents = new DocumentStore(root)) {
            assertEquals(0, BehaviorStateStore.open(documents, Limits.defaults()).status().persistentKeys());
        }
    }

    @Test
    void purgeRemovesInvalidDocumentsIncludingUndecodableBytes() throws Exception {
        Path file = root.resolve(NPC_PATH);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {(byte) 0xc3, (byte) 0x28});
        Path stray = root.resolve("state/behavior-state/unknown/file.json");
        Files.createDirectories(stray.getParent());
        Files.writeString(stray, "{}");
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults());
            assertEquals(2, state.status().invalidDocuments());
            assertEquals(1, state.purge(Purge.npc(NPC)).documents());
            assertFalse(Files.exists(file));
            set(state, "v", integer(1), NPC_PERSISTENT);
            assertEquals(1, state.status().invalidDocuments());
            assertTrue(Files.exists(stray), "An unattributable file only leaves through a full purge");
            assertEquals(2, state.purge(Purge.all()).documents());
            assertFalse(Files.exists(stray));
            assertEquals(0, state.status().invalidDocuments());
        }
    }

    @Test
    void generatedCommitsAreConfinedToTheStateTree() throws Exception {
        try (DocumentStore documents = new DocumentStore(root)) {
            assertThrows(IOException.class, () -> documents.commitGenerated(List.of(new DocumentStore.Generated("npcs/a/b.json", "{}"))));
            assertThrows(IOException.class, () -> documents.commitGenerated(List.of(new DocumentStore.Generated("state/behavior-state/../pending.json", "{}"))));
            assertThrows(IOException.class, () -> documents.deleteGenerated("config.toml"));
        }
    }

    private JsonObject values(String relative) throws Exception {
        return JsonDocuments.object(Files.readString(root.resolve(relative))).getAsJsonObject("values");
    }
}
