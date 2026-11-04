package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.builtin.QuestStateStore.Key;
import art.arcane.animus.core.behavior.builtin.QuestStateStore.Kind;
import art.arcane.animus.core.behavior.builtin.QuestStateStore.Scope;
import art.arcane.animus.core.extension.CapabilityRefusal;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InMemoryQuestStateStoreTest {
    private static final UUID NPC = UUID.randomUUID(), PLAYER = UUID.randomUUID(), OTHER = UUID.randomUUID();
    private static Key quest(Scope scope, UUID npc, UUID player) {
        return new Key(Kind.QUEST, scope, Optional.ofNullable(npc), Optional.ofNullable(player), "main");
    }
    private static <T> T join(java.util.concurrent.CompletionStage<T> stage) { return stage.toCompletableFuture().join(); }

    @Test void scopesAndKindsAreIndependent() {
        InMemoryQuestStateStore store = new InMemoryQuestStateStore(16);
        join(store.set(quest(Scope.PLAYER, null, PLAYER), 3));
        assertEquals(OptionalLong.of(3), join(store.read(quest(Scope.PLAYER, null, PLAYER))));
        assertEquals(OptionalLong.empty(), join(store.read(quest(Scope.PLAYER, null, OTHER))));
        assertEquals(OptionalLong.empty(), join(store.read(quest(Scope.GLOBAL, null, null))));
        assertEquals(OptionalLong.empty(), join(store.read(new Key(Kind.FLAG, Scope.PLAYER, Optional.empty(), Optional.of(PLAYER), "main"))));
    }

    @Test void advanceStartsFromZeroAndClampsAtItsMaximum() {
        InMemoryQuestStateStore store = new InMemoryQuestStateStore(16);
        Key key = quest(Scope.RELATIONSHIP, NPC, PLAYER);
        assertEquals(2L, join(store.advance(key, 2, 3)));
        assertEquals(3L, join(store.advance(key, 2, 3)));
        assertEquals(OptionalLong.of(3), join(store.read(key)));
    }

    @Test void keyRequiresTheIdentitiesItsScopeNames() {
        assertThrows(IllegalArgumentException.class, () -> quest(Scope.PLAYER, null, null));
        assertThrows(IllegalArgumentException.class, () -> quest(Scope.NPC, null, PLAYER));
        assertThrows(IllegalArgumentException.class, () -> quest(Scope.RELATIONSHIP, NPC, null));
        assertThrows(IllegalArgumentException.class, () -> quest(Scope.GLOBAL, NPC, null));
    }

    @Test void newKeysAreBoundedAndExistingKeysStayWritable() {
        InMemoryQuestStateStore store = new InMemoryQuestStateStore(1);
        Key key = quest(Scope.GLOBAL, null, null);
        join(store.set(key, 1));
        assertEquals(2L, join(store.set(key, 2)));
        CompletableFuture<Long> full = store.set(new Key(Kind.QUEST, Scope.GLOBAL, Optional.empty(), Optional.empty(), "other"), 1).toCompletableFuture();
        assertTrue(full.isCompletedExceptionally());
        Throwable failure = full.handle((value, thrown) -> thrown).join();
        assertEquals(Outcome.Status.BUSY, assertInstanceOf(CapabilityRefusal.class, failure).status());
    }
}
