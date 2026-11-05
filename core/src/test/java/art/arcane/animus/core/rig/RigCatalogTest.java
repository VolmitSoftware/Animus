package art.arcane.animus.core.rig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.storage.DocumentStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RigCatalogTest {
    @TempDir Path data;

    private void write(String relative, String content) throws IOException {
        Path file = data.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String revised(String source, int revision) { return source.replace("\"revision\":1", "\"revision\":" + revision); }

    @Test
    void loadsDocumentsByLocationAndRefusesMismatchedIds() throws IOException {
        write("rigs/town/golem.json", RigPlaybackTest.RIG);
        write("animations/town/golem/walk.json", RigPlaybackTest.BOUNCE);
        write("animations/town/golem/wrong.json", RigPlaybackTest.SLIDE);
        RigCatalog catalog = RigCatalog.load(data, RigCatalog.EMPTY, RigCheckpoints.disabled());
        assertTrue(catalog.rig("town:golem").isPresent());
        assertTrue(catalog.animation("town:golem/walk").isPresent());
        assertTrue(catalog.animation("town:golem/wrong").isEmpty());
        assertTrue(catalog.problems().stream().anyMatch((String problem) -> problem.contains("wrong.json") && problem.contains("$.id")), catalog.problems().toString());
        assertTrue(catalog.problems().stream().anyMatch((String problem) -> problem.contains("town:golem/idle")), "a missing idle clip is reported");
    }

    @Test
    void invalidEditKeepsTheLastValidDocument() throws IOException {
        write("rigs/town/golem.json", RigPlaybackTest.RIG);
        RigCatalog first = RigCatalog.load(data, RigCatalog.EMPTY, RigCheckpoints.disabled());
        write("rigs/town/golem.json", revised(RigPlaybackTest.RIG, 2).replace("\"pivot\":[0,1,0]", "\"pivot\":[0,1]"));
        RigCatalog second = RigCatalog.load(data, first, RigCheckpoints.disabled());
        assertEquals(1, second.rig("town:golem").orElseThrow().revision());
        assertTrue(second.problems().stream().anyMatch((String problem) -> problem.contains("last valid revision 1 kept")), second.problems().toString());
    }

    @Test
    void changedContentNeedsAHigherRevision() throws IOException {
        write("rigs/town/golem.json", RigPlaybackTest.RIG);
        RigCatalog first = RigCatalog.load(data, RigCatalog.EMPTY, RigCheckpoints.disabled());
        write("rigs/town/golem.json", RigPlaybackTest.RIG.replace("minecraft:stone", "minecraft:dirt"));
        RigCatalog second = RigCatalog.load(data, first, RigCheckpoints.disabled());
        assertTrue(second.rig("town:golem").orElseThrow().source().contains("minecraft:stone"));
        assertTrue(second.problems().stream().anyMatch((String problem) -> problem.contains("raise $.revision")), second.problems().toString());
        write("rigs/town/golem.json", revised(RigPlaybackTest.RIG, 2).replace("minecraft:stone", "minecraft:dirt"));
        RigCatalog third = RigCatalog.load(data, second, RigCheckpoints.disabled());
        assertEquals(2, third.rig("town:golem").orElseThrow().revision());
    }

    @Test
    void checkpointRecoversAfterRestartWithABrokenFile() throws IOException {
        write("rigs/town/golem.json", RigPlaybackTest.RIG);
        try (DocumentStore store = new DocumentStore(data)) {
            RigCatalog.load(data, RigCatalog.EMPTY, new RigCheckpoints(store));
        }
        write("rigs/town/golem.json", "{ broken");
        try (DocumentStore store = new DocumentStore(data)) {
            RigCatalog restarted = RigCatalog.load(data, RigCatalog.EMPTY, new RigCheckpoints(store));
            assertTrue(restarted.rig("town:golem").isPresent(), restarted.problems().toString());
            assertTrue(restarted.problems().stream().anyMatch((String problem) -> problem.contains("last-valid checkpoint")), restarted.problems().toString());
        }
        assertEquals("{ broken", Files.readString(data.resolve("rigs/town/golem.json")), "sources are never rewritten");
    }

    @Test
    void animationsMustNameBonesOfTheirRig() throws IOException {
        write("rigs/town/golem.json", RigPlaybackTest.RIG);
        write("animations/town/golem/walk.json", RigPlaybackTest.BOUNCE.replace("\"bone\":\"head\"", "\"bone\":\"tail\""));
        RigCatalog catalog = RigCatalog.load(data, RigCatalog.EMPTY, RigCheckpoints.disabled());
        assertTrue(catalog.animation("town:golem/walk").isEmpty());
        assertTrue(catalog.problems().stream().anyMatch((String problem) -> problem.contains("tail")), catalog.problems().toString());
    }

    @Test
    void deletedFilesLeaveTheCatalog() throws IOException {
        write("rigs/town/golem.json", RigPlaybackTest.RIG);
        RigCatalog first = RigCatalog.load(data, RigCatalog.EMPTY, RigCheckpoints.disabled());
        Files.delete(data.resolve("rigs/town/golem.json"));
        assertTrue(RigCatalog.load(data, first, RigCheckpoints.disabled()).rig("town:golem").isEmpty());
    }
}
