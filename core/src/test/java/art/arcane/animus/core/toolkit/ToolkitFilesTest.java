package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.virtual.Vec3;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolkitFilesTest {
    static final String SKIN_TEXT = "{\"textureRaw\":\"e30=\"}\n", SKIN = JsonDocuments.hash(SKIN_TEXT);
    @TempDir Path root;
    DocumentStore store;
    NpcRepository npcs;
    ToolkitFiles files;

    static String npc(String id, String uuid, String folder, String type, String extra) {
        return "{\"schemaVersion\":2,\"revision\":1,\"uuid\":\"" + uuid + "\",\"id\":\"" + id + "\",\"group\":\"" + folder + "\",\"name\":\"N\",\"type\":\"" + type + "\",\"spawned\":false,"
                + "\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0}" + extra + "}";
    }

    @BeforeEach void setUp() throws IOException {
        store = new DocumentStore(root);
        store.commit(List.of(
                new DocumentStore.Change("groups/watch.json", "missing", "{\"schemaVersion\":2,\"revision\":1,\"id\":\"watch\",\"values\":{\"tags\":[\"watch\"]}}"),
                new DocumentStore.Change("groups/looks.json", "missing", "{\"schemaVersion\":2,\"revision\":1,\"id\":\"looks\",\"values\":{\"skins\":[\"" + SKIN + "\"]}}"),
                new DocumentStore.Change("skins/" + SKIN + ".json", "missing", SKIN_TEXT),
                new DocumentStore.Change("routes/patrol.json", "missing", "{\"schemaVersion\":1,\"points\":[{\"x\":0,\"y\":64,\"z\":0}]}"),
                new DocumentStore.Change("npcs/town/guard.json", "missing", npc("guard", "12345678-1234-1234-1234-123456789abc", "town", "minecraft:player", "")),
                new DocumentStore.Change("npcs/town/baker.json", "missing", npc("baker", "22345678-1234-1234-1234-123456789abc", "town", "minecraft:player", ",\"groups\":[\"watch\"],\"skin\":\"" + SKIN + "\"")),
                new DocumentStore.Change("npcs/cow.json", "missing", npc("cow", "32345678-1234-1234-1234-123456789abc", "", "minecraft:cow", ""))));
        npcs = new NpcRepository(store);
        assertTrue(npcs.reload().isEmpty());
        files = new ToolkitFiles(npcs, store);
    }

    @AfterEach void tearDown() throws IOException { store.close(); }

    static String tx(String line) { return line.substring(line.lastIndexOf(' ') + 1); }

    @Test void createdNpcsAreOwnedByTheirMaker() throws IOException {
        java.util.UUID player = java.util.UUID.fromString("0000000a-0000-4000-8000-0000000000c3");
        String line = files.create(files.draft("smith", "minecraft:player", "world", new art.arcane.animus.core.virtual.Vec3(1, 64, 2), 90, player, false));
        assertTrue(line.startsWith("Created npcs/smith.json, despawned; tx "), line);
        assertEquals(1, store.paths(tx(line)).stream().filter((String path) -> path.startsWith("npcs/")).count());
        assertEquals(java.util.Optional.of(player), npcs.find("smith").orElseThrow().owner());
    }

    @Test void assignAndUnassignWriteOneTransaction() throws IOException {
        String assigned = files.groups("watch", List.of("guard", "baker", "cow"), true);
        assertTrue(assigned.startsWith("Assigned watch to 2 NPCs, 1 unchanged; tx "), assigned);
        assertEquals(List.of("npcs/cow.json", "npcs/town/guard.json"), store.paths(tx(assigned)).stream().filter((String path) -> path.startsWith("npcs/")).sorted().toList());
        assertTrue(npcs.find("cow").orElseThrow().groups().contains("watch"));
        String removed = files.groups("watch", List.of("guard", "baker"), false);
        assertTrue(removed.startsWith("Unassigned watch from 2 NPCs; tx "), removed);
        assertTrue(npcs.find("baker").orElseThrow().groups().isEmpty());
        assertThrows(IOException.class, () -> files.groups("missing", List.of("guard"), true));
        assertEquals("Assigned watch to 0 NPCs, 1 unchanged", files.groups("watch", List.of("cow"), true));
    }

    @Test void folderMoveRenamesTheFilesInOneTransaction() throws IOException {
        String moved = files.folder("harbor/dock", List.of("guard", "cow"));
        assertTrue(moved.startsWith("Moved 2 NPCs to npcs/harbor/dock/; tx "), moved);
        assertEquals("harbor/dock", npcs.find("guard").orElseThrow().group());
        assertEquals("npcs/harbor/dock/cow.json", npcs.find("cow").orElseThrow().path());
        assertTrue(store.read("npcs/town/guard.json") == null);
        assertTrue(files.folder("", List.of("guard")).startsWith("Moved 1 NPC to npcs/; tx "));
        assertEquals("", npcs.find("guard").orElseThrow().group());
        assertThrows(IOException.class, () -> files.folder("Bad Folder", List.of("guard")));
    }

    @Test void routeAssignmentWritesTheNavigationModule() throws IOException {
        String line = files.route("patrol", List.of("guard", "baker"));
        assertTrue(line.startsWith("Route patrol on 2 NPCs; tx "), line);
        JsonObject navigation = npcs.find("guard").orElseThrow().json().getAsJsonObject("modules").getAsJsonObject("animus:navigation");
        assertTrue(navigation.get("enabled").getAsBoolean());
        assertEquals("patrol", navigation.get("route").getAsString());
        assertThrows(IOException.class, () -> files.route("nowhere", List.of("guard")));
        assertEquals(List.of("patrol"), files.routes());
    }

    @Test void skinsAreListedSavedAndAppliedToPlayerBodiesOnly() throws IOException {
        List<ToolkitFiles.Skin> skins = files.skins();
        assertEquals(1, skins.size());
        assertEquals(SKIN, skins.getFirst().hash());
        assertEquals(List.of("baker"), skins.getFirst().npcs());
        assertEquals(List.of("looks"), skins.getFirst().groups());

        String texture = Base64.getEncoder().encodeToString("{\"textures\":{}}".getBytes(StandardCharsets.UTF_8));
        String hash = new SkinFiles(npcs, store).save(new BodySkin(texture, Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})), "Alex");
        assertTrue(hash.matches("[a-f0-9]{64}"));
        assertEquals(hash, new SkinFiles(npcs, store).save(new BodySkin(texture, Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})), "Alex"), "saving the same skin again is a no-op");
        String applied = files.skin(Optional.of(hash), List.of("guard", "cow"));
        assertTrue(applied.startsWith("Skin Alex " + hash + " on guard, 1 skipped (not a player body); tx "), applied);
        assertEquals(hash, npcs.find("guard").orElseThrow().json().get("skin").getAsString());
        assertTrue(files.skin(Optional.empty(), List.of("guard")).startsWith("Skin removed on guard; tx "));
        assertFalse(npcs.find("guard").orElseThrow().json().has("skin"));
        assertEquals("No player bodies in the selection; skins only show on minecraft:player", files.skin(Optional.of(hash), List.of("cow")));
    }

    @Test void deleteAndCloneHandleTheWholeSelection() throws IOException {
        String cloned = files.copies(List.of("guard"));
        assertTrue(cloned.startsWith("Cloned guard as dormant guard-copy; tx "), cloned);
        String again = files.copies(List.of("guard", "baker"));
        assertTrue(again.startsWith("Cloned 2 NPCs as dormant guard-copy-2, baker-copy; tx "), again);
        NpcDefinition copy = npcs.find("guard-copy").orElseThrow();
        assertEquals("town", copy.group());
        assertFalse(copy.spawned());
        assertFalse(copy.uuid().equals(npcs.find("guard").orElseThrow().uuid()));

        String deleted = files.delete(List.of("guard-copy", "guard-copy-2", "baker-copy"));
        assertTrue(deleted.startsWith("Deleted 3 NPCs; tx "), deleted);
        assertTrue(npcs.find("guard-copy").isEmpty());
        assertEquals(3, npcs.all().size());
    }

    @Test void spawnDraftsAndPositionSaves() throws IOException {
        ToolkitFiles.Batch batch = files.spawned(List.of("guard", "cow"), true);
        assertEquals(2, batch.drafts().size());
        assertEquals(2, batch.candidates().size());
        assertTrue(batch.candidates().stream().allMatch(NpcDefinition::spawned));
        String line = files.apply(batch, true);
        assertTrue(line.startsWith("Spawning 2 NPCs; tx "), line);
        assertTrue(npcs.find("guard").orElseThrow().spawned());
        assertTrue(files.spawned(List.of("guard"), true).drafts().isEmpty(), "already spawned NPCs are left alone");

        String moved = files.position("guard", new Vec3(10.5, 70, -3.25), 90, 0);
        assertTrue(moved.startsWith("Moved guard here; tx "), moved);
        JsonObject position = npcs.find("guard").orElseThrow().json().getAsJsonObject("position");
        assertEquals(10.5, position.get("x").getAsDouble());
        assertEquals("world", position.get("world").getAsString());
        assertEquals(90, position.get("yaw").getAsFloat());
    }

    @Test void capturedRoutesTakeAFreePlayerId() throws IOException {
        String document = "{\"schemaVersion\":1,\"points\":[{\"x\":0.5,\"y\":64,\"z\":0.5}]}";
        assertEquals("wp-alex_2", files.saveRoute("Alex_2", document));
        assertEquals("wp-alex_2-2", files.saveRoute("Alex_2", document));
        assertTrue(store.read("routes/wp-alex_2-2.json") != null);
    }
}
