package art.arcane.animus.core.behavior.state;

import static org.junit.jupiter.api.Assertions.*;

import art.arcane.animus.core.behavior.BehaviorProgram.Lifetime;
import art.arcane.animus.core.behavior.BehaviorProgram.Scope;
import art.arcane.animus.core.behavior.BehaviorProgram.ScopedVariable;
import art.arcane.animus.core.behavior.BehaviorProgram.Type;
import art.arcane.animus.core.behavior.BehaviorProgram.Value;
import art.arcane.animus.core.behavior.BehaviorState;
import art.arcane.animus.core.behavior.state.BehaviorStateStore.Limits;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BehaviorStateExpiryTest {
    private static final UUID NPC = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final ScopedVariable NPC_PERSISTENT = new ScopedVariable(Scope.NPC, Lifetime.PERSISTENT);
    private static final ScopedVariable NPC_TRANSIENT = new ScopedVariable(Scope.NPC, Lifetime.TRANSIENT);
    private static final BehaviorState.Subject SUBJECT = new BehaviorState.Subject("example:town", NPC, Optional.of(PLAYER));
    private static final String PATH = "state/behavior-state/npc/" + NPC + "/example/town.json";
    private static final Value UNSET = new Value(Type.BOOLEAN, false);
    @TempDir Path root;

    private static Value list(Value... items) { return new Value(Type.LIST, List.of(items)); }
    private static Value integer(long value) { return new Value(Type.INTEGER, value); }

    @Test
    void listMapAndAnyValuesPersistAsCanonicalJsonWithExpiry() throws Exception {
        AtomicLong millis = new AtomicLong(1_000);
        Map<String, Value> entries = new LinkedHashMap<>();
        entries.put("z", new Value(Type.DECIMAL, 2.0));
        entries.put("a", list(integer(1), new Value(Type.STRING, "x")));
        Value map = new Value(Type.MAP, entries);
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults(), System::nanoTime, millis::get);
            state.write(SUBJECT, Map.of("stats", map, "names", list(integer(3))), Map.of("stats", NPC_PERSISTENT, "names", NPC_PERSISTENT), Map.of("names", 5_000L));
            state.close();
        }
        JsonObject stored = JsonDocuments.object(Files.readString(root.resolve(PATH))).getAsJsonObject("values");
        assertEquals("map", stored.getAsJsonObject("stats").get("type").getAsString());
        assertFalse(stored.getAsJsonObject("stats").has("expires"));
        assertEquals(5_000L, stored.getAsJsonObject("names").get("expires").getAsLong());
        assertEquals("list", stored.getAsJsonObject("names").get("type").getAsString());
        assertTrue(Files.readString(root.resolve(PATH)).contains("2.0"), "decimals keep their point");
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults(), System::nanoTime, millis::get);
            assertEquals(map, state.read(SUBJECT, "stats", NPC_PERSISTENT, UNSET, Type.ANY));
            assertEquals(list(integer(3)), state.read(SUBJECT, "names", NPC_PERSISTENT, UNSET, Type.LIST));
            millis.set(5_000);
            assertSame(UNSET, state.read(SUBJECT, "names", NPC_PERSISTENT, UNSET, Type.ANY), "an expired entry reads as unset");
        }
    }

    @Test
    void declaredTypeDecidesWhetherAnotherStoredTypeIsAccepted() throws Exception {
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults());
            state.write(SUBJECT, Map.of("flag", integer(4)), Map.of("flag", NPC_TRANSIENT));
            assertEquals(integer(4), state.read(SUBJECT, "flag", NPC_TRANSIENT, UNSET, Type.ANY));
            BehaviorState.Rejected mismatch = assertThrows(BehaviorState.Rejected.class, () -> state.read(SUBJECT, "flag", NPC_TRANSIENT, UNSET));
            assertEquals("behavior-state-type-mismatch", mismatch.diagnostic().code());
        }
    }

    @Test
    void maintenanceRemovesAtMost256ExpiredKeysPerTurnAndFreesBudget() throws Exception {
        AtomicLong millis = new AtomicLong(0);
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, new Limits(400, 400, 4096, 1 << 20, 1000), System::nanoTime, millis::get);
            for (int subject = 0; subject < 3; subject++) {
                BehaviorState.Subject owner = new BehaviorState.Subject("example:town", new UUID(0, subject + 1), Optional.empty());
                Map<String, Value> writes = new HashMap<>();
                Map<String, ScopedVariable> declarations = new HashMap<>();
                Map<String, Long> expiry = new HashMap<>();
                for (int key = 0; key < 100; key++) { writes.put("k" + key, integer(key)); declarations.put("k" + key, NPC_TRANSIENT); expiry.put("k" + key, 10L); }
                state.write(owner, writes, declarations, expiry);
            }
            assertEquals(300, state.status().transientKeys());
            state.maintain();
            assertEquals(300, state.status().transientKeys(), "nothing has expired yet");
            millis.set(10);
            state.maintain();
            assertEquals(44, state.status().transientKeys());
            state.maintain();
            assertEquals(0, state.status().transientKeys());
            assertEquals(0, state.status().transientBytes());
        }
    }

    @Test
    void rewritingAKeyDropsItsExpiryAndAPastExpiryRemovesIt() throws Exception {
        AtomicLong millis = new AtomicLong(100);
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults(), System::nanoTime, millis::get);
            state.write(SUBJECT, Map.of("a", integer(1)), Map.of("a", NPC_PERSISTENT), Map.of("a", 200L));
            state.write(SUBJECT, Map.of("a", integer(2)), Map.of("a", NPC_PERSISTENT));
            millis.set(500);
            assertEquals(integer(2), state.read(SUBJECT, "a", NPC_PERSISTENT, integer(0)));
            state.write(SUBJECT, Map.of("a", integer(3)), Map.of("a", NPC_PERSISTENT), Map.of("a", 500L));
            assertEquals(integer(0), state.read(SUBJECT, "a", NPC_PERSISTENT, integer(0)));
            assertEquals(0, state.status().persistentKeys(), "a write that is already expired clears the key");
            assertThrows(BehaviorState.Rejected.class, () -> state.write(SUBJECT, Map.of("a", integer(1)), Map.of("a", NPC_PERSISTENT), Map.of("other", 900L)));
        }
    }

    @Test
    void invalidStoredListsAndExpiriesQuarantineTheDocument() throws Exception {
        Files.createDirectories(root.resolve(PATH).getParent());
        Files.writeString(root.resolve(PATH), "{\"schemaVersion\":1,\"behavior\":\"example:town\",\"scope\":\"npc\",\"npc\":\"" + NPC + "\",\"values\":{\"a\":{\"type\":\"list\",\"value\":[null]}}}");
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults());
            assertEquals(1, state.problems().size());
        }
        Files.writeString(root.resolve(PATH), "{\"schemaVersion\":1,\"behavior\":\"example:town\",\"scope\":\"npc\",\"npc\":\"" + NPC + "\",\"values\":{\"a\":{\"type\":\"integer\",\"value\":1,\"expires\":\"soon\"}}}");
        try (DocumentStore documents = new DocumentStore(root)) {
            BehaviorStateStore state = BehaviorStateStore.open(documents, Limits.defaults());
            assertEquals(1, state.problems().size());
        }
    }
}
