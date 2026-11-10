package art.arcane.animus.core.quest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One player's quests: the active ones with their stage and objective counts, and how often and when each quest was
 * finished. Every method is synchronized, so the player's owner, a command and the file writer may all use it.
 */
public final class QuestRecord {
    public static final int SCHEMA_VERSION = 1;

    /**
     * An active quest. {@code stage} counts from zero; {@code ready} means every stage is done and the turn-in NPC waits.
     * {@code mirror} is the behavior stage value last written or seen for it: a different value means a behavior moved it.
     */
    public record Active(int stage, int[] progress, long started, boolean ready, long mirror) {
        public Active { progress = progress.clone(); }
        @Override public int[] progress() { return progress.clone(); }
        public int count(int objective) { return objective < progress.length ? progress[objective] : 0; }
        public Active at(int nextStage, int objectives, long seen) { return new Active(nextStage, new int[objectives], started, false, seen); }
        public Active counted(int objective, int count) {
            int[] next = Arrays.copyOf(progress, Math.max(progress.length, objective + 1));
            next[objective] = count;
            return new Active(stage, next, started, ready, mirror);
        }
        public Active readied() { return new Active(stage, progress, started, true, mirror); }
        @Override public boolean equals(Object other) {
            return other instanceof Active active && active.stage == stage && active.started == started && active.ready == ready && active.mirror == mirror
                    && Arrays.equals(active.progress, progress);
        }
        @Override public int hashCode() { return 31 * Integer.hashCode(stage) + Arrays.hashCode(progress); }
    }
    /** {@code count} completions, the last at epoch millisecond {@code last}; {@code mirror} is the stage value written then. */
    public record Finished(int count, long last, long mirror) {}

    private final Map<String, Active> active = new TreeMap<>();
    private final Map<String, Finished> finished = new TreeMap<>();

    public synchronized Optional<Active> active(String quest) { return Optional.ofNullable(active.get(quest)); }
    public synchronized Optional<Finished> finished(String quest) { return Optional.ofNullable(finished.get(quest)); }
    public synchronized Map<String, Active> active() { return Map.copyOf(active); }
    public synchronized Map<String, Finished> finished() { return Map.copyOf(finished); }
    public synchronized boolean empty() { return active.isEmpty() && finished.isEmpty(); }

    public synchronized void put(String quest, Active state) { active.put(quest, state); }
    public synchronized void drop(String quest) { active.remove(quest); }
    /** Ends the active quest and counts a completion at {@code now}; {@code mirror} is the stage value behaviors now see. */
    public synchronized void finish(String quest, long now, long mirror) {
        active.remove(quest);
        Finished before = finished.get(quest);
        finished.put(quest, new Finished(before == null ? 1 : before.count() + 1, now, mirror));
    }
    /** Forgets a quest entirely, as if the player never took it. */
    public synchronized void forget(String quest) { active.remove(quest); finished.remove(quest); }
    public synchronized void forgetAll() { active.clear(); finished.clear(); }

    public synchronized JsonObject write() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonObject activeJson = new JsonObject();
        for (Map.Entry<String, Active> entry : active.entrySet()) {
            JsonObject state = new JsonObject();
            state.addProperty("stage", entry.getValue().stage());
            JsonArray counts = new JsonArray();
            for (int count : entry.getValue().progress) counts.add(count);
            state.add("progress", counts);
            state.addProperty("started", entry.getValue().started());
            if (entry.getValue().ready()) state.addProperty("ready", true);
            state.addProperty("mirror", entry.getValue().mirror());
            activeJson.add(entry.getKey(), state);
        }
        root.add("active", activeJson);
        JsonObject finishedJson = new JsonObject();
        for (Map.Entry<String, Finished> entry : finished.entrySet()) {
            JsonObject done = new JsonObject();
            done.addProperty("count", entry.getValue().count());
            done.addProperty("last", entry.getValue().last());
            done.addProperty("mirror", entry.getValue().mirror());
            finishedJson.add(entry.getKey(), done);
        }
        root.add("finished", finishedJson);
        return root;
    }

    /** Reads a saved record; throws {@link IllegalArgumentException} for anything malformed. */
    public static QuestRecord read(JsonObject root) {
        QuestRecord record = new QuestRecord();
        try {
            JsonElement version = root.get("schemaVersion");
            if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber() || version.getAsInt() != SCHEMA_VERSION)
                throw new IllegalArgumentException("expected schemaVersion " + SCHEMA_VERSION);
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("active").entrySet()) {
                JsonObject state = entry.getValue().getAsJsonObject();
                JsonArray counts = state.getAsJsonArray("progress");
                int[] progress = new int[counts.size()];
                for (int index = 0; index < progress.length; index++) progress[index] = Math.max(0, counts.get(index).getAsInt());
                int stage = Math.max(0, state.get("stage").getAsInt());
                record.active.put(entry.getKey(), new Active(stage, progress, state.get("started").getAsLong(),
                        state.has("ready") && state.get("ready").getAsBoolean(), state.has("mirror") ? state.get("mirror").getAsLong() : stage + 1L));
            }
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("finished").entrySet()) {
                JsonObject done = entry.getValue().getAsJsonObject();
                record.finished.put(entry.getKey(), new Finished(Math.max(1, done.get("count").getAsInt()), done.get("last").getAsLong(),
                        done.has("mirror") ? done.get("mirror").getAsLong() : -1));
            }
        } catch (IllegalArgumentException malformed) { throw malformed;
        } catch (RuntimeException malformed) { throw new IllegalArgumentException(malformed.getMessage() == null ? "malformed quest record" : malformed.getMessage(), malformed); }
        return record;
    }
}
