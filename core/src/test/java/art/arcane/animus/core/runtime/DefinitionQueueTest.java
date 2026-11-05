package art.arcane.animus.core.runtime;

import art.arcane.animus.core.npc.NpcDefinition;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DefinitionQueueTest {
    @Test void largePublicationConsumesBoundedWorkAndIdleTicksDoNotRescan() throws Exception {
        Map<String, NpcDefinition> published = new LinkedHashMap<>();
        for (int i = 0; i < 257; i++) published.put("npc" + i, definition("npc" + i, "Citizen", 1));
        published = Collections.unmodifiableMap(published);
        DefinitionQueue queue = new DefinitionQueue(); queue.publish(published, false);
        int changed = 0, examined = 0;
        for (int tick = 0; tick < 9; tick++) {
            var batch = queue.poll(32);
            assertTrue(batch.examined() <= 32); assertTrue(batch.changes().size() <= 32);
            examined += batch.examined(); changed += batch.changes().size();
        }
        assertEquals(257, examined); assertEquals(257, changed);
        for (int tick = 0; tick < 100; tick++) {
            queue.publish(published, false);
            assertEquals(new DefinitionQueue.Batch(List.of(), 0, false), queue.poll(32));
        }
    }

    @Test void deletionsPrecedeReplacementAndNewDefinitions() throws Exception {
        DefinitionQueue queue = new DefinitionQueue();
        var old = definition("old", "Old", 1); var keep = definition("keep", "Keep", 1);
        queue.publish(Map.of("old", old, "keep", keep), false); queue.poll(32);
        var next = definition("next", "Next", 1);
        queue.publish(Map.of("keep", keep, "next", next), false);
        var batch = queue.poll(32);
        assertEquals(List.of(new DefinitionQueue.Change("old", null), new DefinitionQueue.Change("next", next)), batch.changes());
        assertTrue(queue.current(batch.changes().getFirst()));
        assertEquals(4, batch.examined());
    }

    @Test void sameRevisionContentEditsStillProduceChangesAndStaleCompletionsAreDetectable() throws Exception {
        DefinitionQueue queue = new DefinitionQueue();
        var first = definition("one", "First", 1); var edited = definition("one", "Edited", 1);
        queue.publish(Map.of("one", first), false);
        var pending = queue.poll(1).changes().getFirst();
        queue.publish(Map.of("one", edited), false);
        assertFalse(queue.current(pending));
        var replacement = queue.poll(8).changes().getFirst();
        assertSame(edited, replacement.definition()); assertTrue(queue.current(replacement));
        queue.publish(Map.of(), false); assertFalse(queue.current(replacement));
        var deletion = queue.poll(8).changes().getFirst();
        queue.publish(Map.of("one", first), false); assertFalse(queue.current(deletion));
    }

    @Test void forcedRefreshRetriesPublishedDefinitionsAndClearDropsPendingWork() throws Exception {
        DefinitionQueue queue = new DefinitionQueue();
        var definition = definition("one", "Citizen", 1); var snapshot = Map.of("one", definition);
        queue.publish(snapshot, false); queue.poll(8);
        queue.publish(snapshot, true); assertEquals(1, queue.poll(8).changes().size());
        queue.publish(snapshot, true); queue.clear();
        assertEquals(new DefinitionQueue.Batch(List.of(), 0, false), queue.poll(8));
        assertNull(queue.current("one"));
    }

    @Test void publicationSupersessionDuringIncrementalRemovalDoesNotLoseChanges() throws Exception {
        DefinitionQueue queue = new DefinitionQueue();
        Map<String, NpcDefinition> first = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) first.put("n" + i, definition("n" + i, "Citizen", 1));
        queue.publish(Map.copyOf(first), false); queue.poll(8);
        queue.publish(Map.of(), false); assertEquals(2, queue.poll(2).changes().size());
        queue.publish(Map.copyOf(first), false);
        int restored = 0;
        for (int i = 0; i < 8; i++) restored += queue.poll(2).changes().size();
        assertEquals(2, restored);
    }

    static NpcDefinition definition(String id, String name, long revision) throws Exception {
        String uuid = UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return NpcDefinition.parse("""
                {"schemaVersion":1,"revision":%d,"uuid":"%s","id":"%s","group":"test","name":"%s",
                 "type":"minecraft:player","spawned":true,
                 "position":{"world":"world","x":1,"y":64,"z":2,"yaw":0,"pitch":0}}
                """.formatted(revision, uuid, id, name));
    }
}
