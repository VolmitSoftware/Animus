package art.arcane.animus.core.hotload;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HotloadEngineTest {
    /** In-memory files whose signature changes on every write. */
    private static final class Memory implements HotloadEngine.Source {
        final Map<String, String> files = new HashMap<>();
        final Map<String, Integer> versions = new HashMap<>();
        String refused;
        boolean transientFailure;
        Runnable beforeRead = () -> {};

        void write(String path, String text) { files.put(path, text); versions.merge(path, 1, Integer::sum); }
        void delete(String path) { files.remove(path); versions.remove(path); }

        @Override public Map<String, String> signatures() {
            Map<String, String> signatures = new HashMap<>();
            for (String path : files.keySet()) signatures.put(path, String.valueOf(versions.get(path)));
            return signatures;
        }
        @Override public String read(String path) throws IOException {
            beforeRead.run();
            if (transientFailure) throw new IOException("locked");
            if (path.equals(refused)) throw new HotloadEngine.Refused("$: too large");
            return files.get(path);
        }
    }

    @Test void changeIsReportedOnlyOnceItReadsTheSameOnTwoPolls() throws Exception {
        Memory files = new Memory();
        files.write("npcs/town/guard.json", "{\"a\":1}");
        HotloadEngine engine = new HotloadEngine(files);
        engine.prime();
        assertTrue(engine.poll(10).isEmpty());
        files.write("npcs/town/guard.json", "{\"a\":");
        assertTrue(engine.poll(10).isEmpty(), "the first read only starts the stability window");
        files.write("npcs/town/guard.json", "{\"a\":2}");
        assertTrue(engine.poll(10).isEmpty(), "a different second read restarts it");
        List<HotloadEngine.Snapshot> stable = engine.poll(10);
        assertEquals(1, stable.size());
        assertEquals("{\"a\":2}", stable.getFirst().content());
        Optional<HotloadEngine.Delta> delta = engine.settle(stable.getFirst(), HotloadEngine.Outcome.APPLIED);
        assertEquals(new HotloadEngine.Delta("npcs/town/guard.json", "{\"a\":1}", "{\"a\":2}"), delta.orElseThrow());
        assertTrue(engine.poll(10).isEmpty());
        assertTrue(engine.poll(10).isEmpty());
    }

    @Test void selfWritesAreNeverReported() throws Exception {
        Memory files = new Memory();
        files.write("config.toml", "a = 1");
        HotloadEngine engine = new HotloadEngine(files);
        engine.prime();
        engine.noteSelfWrite("config.toml", "a = 2");
        files.write("config.toml", "a = 2");
        engine.noteSelfWrite("config.toml", "a = 2");
        for (int poll = 0; poll < 3; poll++) assertTrue(engine.poll(10).isEmpty());
        engine.noteSelfWrite("npcs/town/new.json", "{}");
        files.write("npcs/town/new.json", "{}");
        for (int poll = 0; poll < 3; poll++) assertTrue(engine.poll(10).isEmpty());
        engine.noteSelfWrite("npcs/town/new.json", null);
        files.delete("npcs/town/new.json");
        for (int poll = 0; poll < 3; poll++) assertTrue(engine.poll(10).isEmpty());
    }

    @Test void aSelfWriteDuringAReadSupersedesTheSnapshot() throws Exception {
        Memory files = new Memory();
        files.write("config.toml", "a = 1");
        HotloadEngine engine = new HotloadEngine(files);
        engine.prime();
        files.write("config.toml", "a = 9");
        engine.poll(10);
        List<HotloadEngine.Snapshot> stable = engine.poll(10);
        assertEquals(1, stable.size());
        engine.noteSelfWrite("config.toml", "a = 3");
        assertFalse(engine.current(stable.getFirst()));
        assertTrue(engine.settle(stable.getFirst(), HotloadEngine.Outcome.APPLIED).isEmpty());
        assertEquals("a = 3", engine.known("config.toml"));

        files.write("config.toml", "a = 4");
        files.beforeRead = () -> engine.noteSelfWrite("config.toml", "a = 4");
        assertTrue(engine.poll(10).isEmpty(), "a read raced by a self write is dropped");
        files.beforeRead = () -> {};
        assertTrue(engine.poll(10).isEmpty());
    }

    @Test void refusedChangeIsNotReportedAgainUntilTheFileChanges() throws Exception {
        Memory files = new Memory();
        files.write("shops/town/baker.json", "{\"ok\":true}");
        HotloadEngine engine = new HotloadEngine(files);
        engine.prime();
        files.write("shops/town/baker.json", "{\"ok\":");
        engine.poll(10);
        HotloadEngine.Snapshot bad = engine.poll(10).getFirst();
        assertTrue(engine.settle(bad, HotloadEngine.Outcome.REJECTED).isEmpty());
        assertEquals("{\"ok\":true}", engine.known("shops/town/baker.json"), "the last valid version stays the base");
        files.write("shops/town/baker.json", "{\"ok\":");
        for (int poll = 0; poll < 3; poll++) assertTrue(engine.poll(10).isEmpty());
        files.write("shops/town/baker.json", "{\"ok\":false}");
        engine.poll(10);
        HotloadEngine.Snapshot fixed = engine.poll(10).getFirst();
        assertEquals(new HotloadEngine.Delta("shops/town/baker.json", "{\"ok\":true}", "{\"ok\":false}"),
                engine.settle(fixed, HotloadEngine.Outcome.APPLIED).orElseThrow());
    }

    @Test void retriedChangeIsReportedAgainOnTheNextPoll() throws Exception {
        Memory files = new Memory();
        HotloadEngine engine = new HotloadEngine(files);
        engine.prime();
        files.write("behaviors/town/greet.json", "{}");
        engine.poll(10);
        HotloadEngine.Snapshot first = engine.poll(10).getFirst();
        engine.settle(first, HotloadEngine.Outcome.RETRY);
        HotloadEngine.Snapshot again = engine.poll(10).getFirst();
        assertEquals("{}", again.content());
        assertEquals(new HotloadEngine.Delta("behaviors/town/greet.json", null, "{}"), engine.settle(again, HotloadEngine.Outcome.APPLIED).orElseThrow());
    }

    @Test void transientReadFailureIsReadAgainAndRefusedFileIsReported() throws Exception {
        Memory files = new Memory();
        HotloadEngine engine = new HotloadEngine(files);
        engine.prime();
        files.write("npcs/town/big.json", "{}");
        files.transientFailure = true;
        assertTrue(engine.poll(10).isEmpty());
        assertTrue(engine.poll(10).isEmpty());
        files.transientFailure = false;
        files.refused = "npcs/town/big.json";
        engine.poll(10);
        HotloadEngine.Snapshot refused = engine.poll(10).getFirst();
        assertEquals("$: too large", refused.failure());
        assertNull(refused.content());
        assertFalse(refused.deleted());
    }

    @Test void deletionIsAStableChangeToMissing() throws Exception {
        Memory files = new Memory();
        files.write("routes/patrol.json", "{}");
        HotloadEngine engine = new HotloadEngine(files);
        engine.prime();
        files.delete("routes/patrol.json");
        engine.poll(10);
        HotloadEngine.Snapshot gone = engine.poll(10).getFirst();
        assertTrue(gone.deleted());
        assertEquals(new HotloadEngine.Delta("routes/patrol.json", "{}", null), engine.settle(gone, HotloadEngine.Outcome.APPLIED).orElseThrow());
    }

    @Test void readsPerPollBoundTheWorkAndTheRestFollow() throws Exception {
        Memory files = new Memory();
        HotloadEngine engine = new HotloadEngine(files);
        engine.prime();
        for (int index = 0; index < 5; index++) files.write("npcs/town/n" + index + ".json", "{}");
        int reported = 0;
        for (int poll = 0; poll < 12 && reported < 5; poll++) {
            List<HotloadEngine.Snapshot> stable = engine.poll(2);
            assertTrue(stable.size() <= 2);
            for (HotloadEngine.Snapshot snapshot : stable) engine.settle(snapshot, HotloadEngine.Outcome.APPLIED);
            reported += stable.size();
        }
        assertEquals(5, reported);
    }
}
