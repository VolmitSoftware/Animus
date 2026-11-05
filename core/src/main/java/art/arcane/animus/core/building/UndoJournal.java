package art.arcane.animus.core.building;

import art.arcane.animus.core.navigation.BlockPos;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Every block a build changed, with what was there before, in the order the changes happened. Bounded: past
 * {@code capacity} entries nothing more is recorded and the journal is marked incomplete, so an undo can only revert
 * the changes it holds. Changes made together, such as both halves of a door, form one group that is recorded whole or
 * not at all and undone as one. Scaffolding the build put up and took down again is marked, so undo leaves it out.
 * Saved in chunks of {@link #CHUNK} entries; only chunks changed since the last save are written.
 */
public final class UndoJournal {
    public static final int CHUNK = 4096;
    /** {@link Entry#flags} bits: the entry belongs to the group of the entry before it; the entry is scaffolding work. */
    public static final int LINKED = 1, SCAFFOLD = 2;
    public record Entry(int x, int y, int z, String before, String after, int flags) {
        public Entry { Objects.requireNonNull(before); Objects.requireNonNull(after); }
        public BlockPos position() { return new BlockPos(x, y, z); }
        public boolean linked() { return (flags & LINKED) != 0; }
        public boolean scaffold() { return (flags & SCAFFOLD) != 0; }
    }
    /** One change of a group. */
    public record Change(BlockPos at, String before, String after) {
        public Change { Objects.requireNonNull(at); Objects.requireNonNull(before); Objects.requireNonNull(after); }
    }

    private final int capacity;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, String> interned = new HashMap<>();
    private boolean truncated;
    private int savedEntries;

    public UndoJournal(int capacity) {
        if (capacity < 0) throw new IllegalArgumentException("Undo capacity must not be negative");
        this.capacity = capacity;
    }

    /** False when the journal is full; the change still happened but cannot be undone. */
    public boolean record(BlockPos at, String before, String after) { return record(at, before, after, 0); }
    /** Records a scaffolding change, which undo leaves out. */
    public boolean scaffold(BlockPos at, String before, String after) { return record(at, before, after, SCAFFOLD); }
    /** Records changes made together as one group; false, and nothing recorded, when the whole group does not fit. */
    public boolean group(List<Change> changes) {
        if (changes.isEmpty()) return true;
        if (entries.size() + changes.size() > capacity) { truncated = true; return false; }
        for (int index = 0; index < changes.size(); index++) {
            Change change = changes.get(index);
            entries.add(new Entry(change.at().x(), change.at().y(), change.at().z(), intern(change.before()), intern(change.after()), index == 0 ? 0 : LINKED));
        }
        return true;
    }
    /** The first entry of the group that holds this entry. */
    public int groupStart(int index) {
        int start = index;
        while (start > 0 && entries.get(start).linked()) start--;
        return start;
    }
    private boolean record(BlockPos at, String before, String after, int flags) {
        if (entries.size() >= capacity) { truncated = true; return false; }
        entries.add(new Entry(at.x(), at.y(), at.z(), intern(before), intern(after), flags));
        return true;
    }
    public int size() { return entries.size(); }
    public Entry get(int index) { return entries.get(index); }
    public boolean truncated() { return truncated; }
    public int capacity() { return capacity; }
    public int chunks() { return (entries.size() + CHUNK - 1) / CHUNK; }
    /** Chunk indices changed since {@link #saved()}. */
    public List<Integer> dirtyChunks() {
        List<Integer> dirty = new ArrayList<>();
        if (entries.size() == savedEntries) return dirty;
        for (int chunk = savedEntries / CHUNK; chunk < chunks(); chunk++) dirty.add(chunk);
        return dirty;
    }
    public void saved() { savedEntries = entries.size(); }
    /** After a failed save every chunk is written again next time. */
    public void forgetSaved() { savedEntries = 0; }

    /** One chunk as JSON: a table of block states and rows of {@code [x, y, z, before, after]} indices into it, with flags last when set. */
    public JsonObject chunk(int chunk) {
        List<String> states = new ArrayList<>();
        Map<String, Integer> indices = new HashMap<>();
        JsonArray rows = new JsonArray();
        for (int index = chunk * CHUNK; index < Math.min(entries.size(), (chunk + 1) * CHUNK); index++) {
            Entry entry = entries.get(index);
            JsonArray row = new JsonArray();
            row.add(entry.x); row.add(entry.y); row.add(entry.z);
            row.add(indices.computeIfAbsent(entry.before, (String state) -> { states.add(state); return states.size() - 1; }));
            row.add(indices.computeIfAbsent(entry.after, (String state) -> { states.add(state); return states.size() - 1; }));
            if (entry.flags != 0) row.add(entry.flags);
            rows.add(row);
        }
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 1);
        JsonArray table = new JsonArray();
        states.forEach(table::add);
        json.add("states", table);
        json.add("entries", rows);
        return json;
    }

    /** Restores the journal from its saved chunks in order. */
    public static UndoJournal restore(int capacity, boolean truncated, List<JsonObject> chunks) {
        UndoJournal journal = new UndoJournal(capacity);
        for (int chunk = 0; chunk < chunks.size(); chunk++) {
            JsonObject json = chunks.get(chunk);
            String path = "undo chunk " + chunk;
            if (!json.has("states") || !json.get("states").isJsonArray() || !json.has("entries") || !json.get("entries").isJsonArray())
                throw new IllegalArgumentException(path + ": expected states and entries");
            List<String> states = new ArrayList<>();
            for (JsonElement state : json.getAsJsonArray("states")) {
                if (!state.isJsonPrimitive() || !state.getAsJsonPrimitive().isString() || state.getAsString().length() > 1024)
                    throw new IllegalArgumentException(path + ": invalid block state");
                states.add(state.getAsString());
            }
            JsonArray rows = json.getAsJsonArray("entries");
            if (rows.size() > CHUNK || chunk < chunks.size() - 1 && rows.size() != CHUNK)
                throw new IllegalArgumentException(path + ": unexpected entry count " + rows.size());
            for (JsonElement element : rows) {
                if (!element.isJsonArray() || element.getAsJsonArray().size() < 5 || element.getAsJsonArray().size() > 6) throw new IllegalArgumentException(path + ": invalid entry");
                JsonArray row = element.getAsJsonArray();
                int before = row.get(3).getAsInt(), after = row.get(4).getAsInt(), flags = row.size() == 6 ? row.get(5).getAsInt() : 0;
                if (before < 0 || before >= states.size() || after < 0 || after >= states.size()) throw new IllegalArgumentException(path + ": state index out of range");
                if (flags < 0 || flags > (LINKED | SCAFFOLD) || flags == (LINKED | SCAFFOLD) || journal.entries.isEmpty() && (flags & LINKED) != 0)
                    throw new IllegalArgumentException(path + ": invalid entry flags");
                if (!journal.record(new BlockPos(row.get(0).getAsInt(), row.get(1).getAsInt(), row.get(2).getAsInt()), states.get(before), states.get(after), flags))
                    throw new IllegalArgumentException(path + ": more entries than the undo limit " + capacity);
            }
        }
        journal.truncated = truncated;
        journal.saved();
        return journal;
    }

    private String intern(String state) { return interned.computeIfAbsent(state, (String key) -> key); }
}
