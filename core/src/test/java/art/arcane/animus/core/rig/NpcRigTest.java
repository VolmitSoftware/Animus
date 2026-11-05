package art.arcane.animus.core.rig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.mount.SeatGeometry;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.presentation.BoneDisplay;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NpcRigTest {
    @TempDir Path data;
    private static final SeatGeometry SEAT = new SeatGeometry(0, 1.8, 0, 0);

    private RigCatalog catalog(String rig) throws IOException {
        Path file = data.resolve("rigs/town/golem.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, rig);
        Path walk = data.resolve("animations/town/golem/walk.json");
        Files.createDirectories(walk.getParent());
        Files.writeString(walk, RigPlaybackTest.BOUNCE);
        return RigCatalog.load(data, RigCatalog.EMPTY, RigCheckpoints.disabled());
    }

    @Test
    void bindsDisplaysInBoneOrderAndHidesTheBodyByDefault() throws IOException {
        List<String> problems = new ArrayList<>();
        NpcRig<String> rig = NpcRig.bind("town:golem", catalog(RigPlaybackTest.RIG), SEAT, (NpcItem item, String path, List<String> errors) -> item.id(), problems).orElseThrow();
        assertEquals(List.of(), problems);
        List<BoneDisplay<String>> displays = rig.snapshot().displays();
        assertEquals(BoneDisplay.Kind.BLOCK, displays.get(0).kind());
        assertEquals("minecraft:stone", displays.get(0).block());
        assertEquals("minecraft:stick", displays.get(1).item());
        assertTrue(rig.snapshot().hideBody());
        assertNull(rig.snapshot().frame(), "nothing is shown before the first plan");
        rig.plan(0, 0, 2, 0);
        assertNotNull(rig.snapshot().frame());
    }

    @Test
    void unknownItemsAndMissingRigsAreReported() throws IOException {
        List<String> problems = new ArrayList<>();
        assertTrue(NpcRig.bind("town:golem", catalog(RigPlaybackTest.RIG), SEAT, (NpcItem item, String path, List<String> errors) -> { errors.add(path + ": unknown"); return null; }, problems).isEmpty());
        assertTrue(problems.getFirst().contains("head"), problems.toString());
        problems.clear();
        assertTrue(NpcRig.bind("town:nobody", RigCatalog.EMPTY, SEAT, (NpcItem item, String path, List<String> errors) -> item.id(), problems).isEmpty());
        assertTrue(problems.getFirst().contains("town:nobody"));
    }

    @Test
    void reloadKeepsAnUnchangedRigAndDropsAChangedOne() throws IOException {
        RigCatalog first = catalog(RigPlaybackTest.RIG);
        NpcRig<String> rig = NpcRig.bind("town:golem", first, SEAT, (NpcItem item, String path, List<String> errors) -> item.id(), new ArrayList<>()).orElseThrow();
        RigCatalog same = RigCatalog.load(data, first, RigCheckpoints.disabled());
        assertTrue(rig.refresh(same, 5));
        Files.writeString(data.resolve("rigs/town/golem.json"), RigPlaybackTest.RIG.replace("\"revision\":1", "\"revision\":2").replace("minecraft:stone", "minecraft:dirt"));
        RigCatalog changed = RigCatalog.load(data, same, RigCheckpoints.disabled());
        assertFalse(rig.refresh(changed, 6));
    }

    @Test
    void playedClipsReportTheirEndOnce() throws IOException {
        Path slide = data.resolve("animations/town/golem/slide.json");
        Files.createDirectories(slide.getParent());
        Files.writeString(slide, RigPlaybackTest.SLIDE);
        NpcRig<String> rig = NpcRig.bind("town:golem", catalog(RigPlaybackTest.RIG), SEAT, (NpcItem item, String path, List<String> errors) -> item.id(), new ArrayList<>()).orElseThrow();
        rig.play("town:golem/slide", 4, Optional.empty(), 1, 0);
        assertEquals(List.of(), rig.tick(4, false));
        assertEquals(List.of(new RigPlayback.Finished("town:golem/slide", 1)), rig.tick(5, false));
        assertEquals(List.of(), rig.tick(6, false));
    }
}
