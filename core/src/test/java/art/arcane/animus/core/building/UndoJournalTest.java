package art.arcane.animus.core.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.navigation.BlockPos;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UndoJournalTest {
    @Test void stopsRecordingAtCapacityAndSaysSo() {
        UndoJournal journal = new UndoJournal(2);
        assertTrue(journal.record(new BlockPos(0, 64, 0), "minecraft:air", "minecraft:stone"));
        assertTrue(journal.record(new BlockPos(1, 64, 0), "minecraft:dirt", "minecraft:stone"));
        assertFalse(journal.truncated());
        assertFalse(journal.record(new BlockPos(2, 64, 0), "minecraft:air", "minecraft:stone"));
        assertTrue(journal.truncated());
        assertEquals(2, journal.size());
    }

    @Test void aGroupIsRecordedWholeOrNotAtAllAndKeepsItsLinksAndScaffoldMarksWhenSaved() {
        UndoJournal journal = new UndoJournal(4);
        journal.record(new BlockPos(5, 64, 0), "minecraft:air", "minecraft:stone");
        assertTrue(journal.group(List.of(new UndoJournal.Change(new BlockPos(0, 64, 0), "minecraft:air", "minecraft:oak_door[half=lower]"),
                new UndoJournal.Change(new BlockPos(0, 65, 0), "minecraft:air", "minecraft:oak_door[half=upper]"))));
        assertFalse(journal.group(List.of(new UndoJournal.Change(new BlockPos(1, 64, 0), "minecraft:air", "minecraft:red_bed[part=foot]"),
                new UndoJournal.Change(new BlockPos(1, 64, 1), "minecraft:air", "minecraft:red_bed[part=head]"))));
        assertEquals(3, journal.size());
        assertTrue(journal.truncated());
        assertEquals(1, journal.groupStart(2));
        assertEquals(1, journal.groupStart(1));
        assertEquals(0, journal.groupStart(0));
        UndoJournal scaffolds = new UndoJournal(10);
        scaffolds.scaffold(new BlockPos(0, 64, 0), "minecraft:air", "minecraft:scaffolding");
        scaffolds.group(List.of(new UndoJournal.Change(new BlockPos(0, 64, 0), "a:a", "b:b"), new UndoJournal.Change(new BlockPos(0, 65, 0), "a:a", "b:b")));
        UndoJournal restored = UndoJournal.restore(10, false, List.of(scaffolds.chunk(0)));
        assertTrue(restored.get(0).scaffold());
        assertFalse(restored.get(1).linked());
        assertTrue(restored.get(2).linked());
        assertEquals(1, restored.groupStart(2));
    }

    @Test void chunksRoundTripInOrder() {
        UndoJournal journal = new UndoJournal(10_000);
        for (int index = 0; index < UndoJournal.CHUNK + 10; index++)
            journal.record(new BlockPos(index, 64, -index), index % 2 == 0 ? "minecraft:air" : "minecraft:dirt", "minecraft:stone");
        assertEquals(2, journal.chunks());
        assertEquals(List.of(0, 1), journal.dirtyChunks());
        List<JsonObject> chunks = new ArrayList<>();
        for (int chunk = 0; chunk < journal.chunks(); chunk++) chunks.add(journal.chunk(chunk));
        assertEquals(3, chunks.get(0).getAsJsonArray("states").size());
        UndoJournal restored = UndoJournal.restore(10_000, false, chunks);
        assertEquals(journal.size(), restored.size());
        assertEquals(journal.get(4097), restored.get(4097));
        assertEquals(List.of(), restored.dirtyChunks());
        restored.record(new BlockPos(0, 0, 0), "minecraft:air", "minecraft:glass");
        assertEquals(List.of(1), restored.dirtyChunks());
        journal.saved();
        assertEquals(List.of(), journal.dirtyChunks());
    }

    @Test void restoreRejectsDamagedChunks() {
        UndoJournal journal = new UndoJournal(10);
        journal.record(new BlockPos(0, 64, 0), "minecraft:air", "minecraft:stone");
        JsonObject chunk = journal.chunk(0);
        chunk.getAsJsonArray("entries").get(0).getAsJsonArray().set(3, new com.google.gson.JsonPrimitive(9));
        assertThrows(IllegalArgumentException.class, () -> UndoJournal.restore(10, false, List.of(chunk)));
        assertThrows(IllegalArgumentException.class, () -> UndoJournal.restore(0, false, List.of(journal.chunk(0))));
        assertThrows(IllegalArgumentException.class, () -> UndoJournal.restore(10, false, List.of(new JsonObject())));
    }
}
