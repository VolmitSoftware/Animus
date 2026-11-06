package art.arcane.animus.core.rig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.mount.SeatGeometry;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.presentation.BoneDisplay;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NpcRigTest {
    @TempDir Path data;
    private static final SeatGeometry SEAT = new SeatGeometry(0, 1.8, 0, 0);
    private static final String AIR = "minecraft:air";
    /** The test rig with a raised head visual and the main hand socketed to the head. */
    private static final String SOCKETED = RigPlaybackTest.RIG
            .replace("\"item\":{\"id\":\"minecraft:stick\"}}", "\"item\":{\"id\":\"minecraft:stick\"},\"translation\":[0,0.5,0]}")
            .replace("\"animations\":", "\"sockets\":{\"main_hand\":\"head\"},\"animations\":");

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
        NpcRig<String> rig = NpcRig.bind("town:golem", catalog(RigPlaybackTest.RIG), SEAT, (NpcItem item, String path, List<String> errors) -> item.id(), Optional.of(AIR), problems).orElseThrow();
        assertEquals(List.of(), problems);
        List<BoneDisplay<String>> displays = rig.snapshot().displays();
        assertEquals(BoneDisplay.Kind.BLOCK, displays.get(0).kind());
        assertEquals("minecraft:stone", displays.get(0).block());
        assertEquals("minecraft:stick", displays.get(1).item());
        assertTrue(rig.snapshot().hideBody());
        assertEquals(0, rig.snapshot().sockets());
        assertNull(rig.snapshot().frame(), "nothing is shown before the first plan");
        rig.plan(0, 0, 2, 0);
        assertNotNull(rig.snapshot().frame());
    }

    @Test
    void unknownItemsAndMissingRigsAreReported() throws IOException {
        List<String> problems = new ArrayList<>();
        assertTrue(NpcRig.bind("town:golem", catalog(RigPlaybackTest.RIG), SEAT, (NpcItem item, String path, List<String> errors) -> { errors.add(path + ": unknown"); return null; }, Optional.of(AIR), problems).isEmpty());
        assertTrue(problems.getFirst().contains("head"), problems.toString());
        problems.clear();
        assertTrue(NpcRig.bind("town:nobody", RigCatalog.EMPTY, SEAT, (NpcItem item, String path, List<String> errors) -> item.id(), Optional.of(AIR), problems).isEmpty());
        assertTrue(problems.getFirst().contains("town:nobody"));
    }

    @Test
    void reloadKeepsAnUnchangedRigAndDropsAChangedOne() throws IOException {
        RigCatalog first = catalog(RigPlaybackTest.RIG);
        NpcRig<String> rig = NpcRig.bind("town:golem", first, SEAT, (NpcItem item, String path, List<String> errors) -> item.id(), Optional.of(AIR), new ArrayList<>()).orElseThrow();
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
        NpcRig<String> rig = NpcRig.bind("town:golem", catalog(RigPlaybackTest.RIG), SEAT, (NpcItem item, String path, List<String> errors) -> item.id(), Optional.of(AIR), new ArrayList<>()).orElseThrow();
        rig.play("town:golem/slide", 4, Optional.empty(), 1, 0);
        assertEquals(List.of(), rig.tick(4, false).finished());
        assertEquals(List.of(new RigPlayback.Finished("town:golem/slide", 1)), rig.tick(5, false).finished());
        assertEquals(List.of(), rig.tick(6, false).finished());
    }

    @Test
    void socketsShowTheSlotItemAtTheBoneAndFollowItsPose() throws IOException {
        NpcRig<String> rig = NpcRig.bind("town:golem", catalog(SOCKETED), SEAT, (NpcItem item, String path, List<String> errors) -> item.id(), Optional.of(AIR), new ArrayList<>()).orElseThrow();
        NpcRig.Snapshot<String> before = rig.snapshot();
        assertEquals(1, before.sockets());
        assertEquals(3, before.displays().size());
        BoneDisplay<String> socket = before.displays().get(2);
        assertEquals(BoneDisplay.Kind.ITEM, socket.kind());
        assertEquals(AIR, socket.item(), "an empty slot shows nothing");
        assertEquals(BoneDisplay.itemContext("thirdperson_righthand"), socket.itemContext());

        Map<EquipmentSlot, String> equipment = new EnumMap<>(EquipmentSlot.class);
        equipment.put(EquipmentSlot.MAIN_HAND, "minecraft:iron_sword");
        equipment.put(EquipmentSlot.HEAD, "minecraft:iron_helmet");
        rig.equip(equipment, (String item) -> item);
        assertEquals("minecraft:iron_sword", rig.snapshot().displays().get(2).item());
        assertTrue(rig.snapshot().socketRevision() > before.socketRevision());
        long revision = rig.snapshot().socketRevision();
        rig.equip(equipment, (String item) -> item);
        assertEquals(revision, rig.snapshot().socketRevision(), "unchanged equipment keeps the revision");
        assertEquals(Map.of(EquipmentSlot.HEAD, "minecraft:iron_helmet"), rig.bodyEquipment(equipment), "the socketed slot leaves the body");

        rig.plan(0, 0, 2, 0);
        RigFrame frame = rig.snapshot().frame();
        assertEquals(3, frame.bones());
        assertEquals(frame.target(1).ty() - 0.5, frame.target(2).ty(), 1e-6, "the socket sits on the bone, not on its visual");
        assertEquals(frame.target(1).tx(), frame.target(2).tx(), 1e-6);
    }
}
