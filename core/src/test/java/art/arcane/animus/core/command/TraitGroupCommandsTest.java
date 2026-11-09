package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraitGroupCommandsTest {
    @TempDir Path root;

    private static String npc(String id, String uuid, String folder, String extra) {
        return "{\"schemaVersion\":1,\"revision\":1,\"uuid\":\"" + uuid + "\",\"id\":\"" + id + "\",\"group\":\"" + folder + "\",\"name\":\"N\",\"type\":\"minecraft:player\",\"spawned\":false,"
                + "\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0}" + extra + "}";
    }

    @Test void assignAndUnassignEditTheMatchingNpcsInOneTransaction() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(
                    new DocumentStore.Change("groups/watch.json", "missing", "{\"schemaVersion\":1,\"revision\":1,\"id\":\"watch\",\"description\":\"Town watch\",\"values\":{\"teamColor\":\"gold\",\"tags\":[\"watch\"]}}"),
                    new DocumentStore.Change("npcs/town/guard.json", "missing", npc("guard", "12345678-1234-1234-1234-123456789abc", "town", "")),
                    new DocumentStore.Change("npcs/town/baker.json", "missing", npc("baker", "22345678-1234-1234-1234-123456789abc", "town", ",\"groups\":[\"watch\"]")),
                    new DocumentStore.Change("npcs/harbor/sailor.json", "missing", npc("sailor", "32345678-1234-1234-1234-123456789abc", "harbor", ""))));
            NpcRepository repository = new NpcRepository(store);
            assertTrue(repository.reload().isEmpty());

            String assigned = TraitGroupCommands.change(repository, "watch", TraitGroupCommands.selector(repository, "town", "", Set.of()), true);
            assertTrue(assigned.startsWith("Assigned watch to 1 NPC, 1 unchanged; tx "), assigned);
            String transaction = assigned.substring(assigned.lastIndexOf(' ') + 1);
            assertTrue(store.paths(transaction).contains("npcs/town/guard.json"));
            assertEquals("gold", repository.find("guard").orElseThrow().json().get("teamColor").getAsString());
            assertEquals(List.of("baker", "guard"), TraitGroupCommands.memberIds(repository, "watch"));
            assertEquals(List.of("watch | revision 1 | members 2 | Town watch"), TraitGroupCommands.rows(repository));
            assertEquals("Group watch | revision 1 | members 2 | Town watch", TraitGroupCommands.inspect(repository, "watch").getFirst());

            assertTrue(TraitGroupCommands.change(repository, "watch", TraitGroupCommands.selector(repository, "", "watch", Set.of()), true).equals("Assigned watch to 0 NPCs, 2 unchanged"),
                    "tags are read from the effective definition");
            assertTrue(TraitGroupCommands.change(repository, "watch", TraitGroupCommands.selector(repository, "", "", Set.of("sailor")), true).startsWith("Assigned watch to 1 NPC; tx "));
            String unassigned = TraitGroupCommands.change(repository, "watch", TraitGroupCommands.selector(repository, "", "", Set.of("baker", "sailor", "guard")), false);
            assertTrue(unassigned.startsWith("Unassigned watch from 3 NPCs; tx "), unassigned);
            assertTrue(repository.find("baker").orElseThrow().groups().isEmpty());
            assertFalse(repository.draft("baker").document().has("groups"));

            IOException unknown = assertThrows(IOException.class, () -> TraitGroupCommands.change(repository, "missing", TraitGroupCommands.selector(repository, "town", "", Set.of()), true));
            assertTrue(unknown.getMessage().startsWith("Unknown group missing"), unknown.getMessage());
            assertThrows(IOException.class, () -> TraitGroupCommands.inspect(repository, "missing"));
        }
    }

    @Test void anAssignmentWithAnInvalidResultIsRefusedWhole() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(
                    new DocumentStore.Change("groups/stands.json", "missing", "{\"schemaVersion\":1,\"revision\":1,\"id\":\"stands\",\"values\":{\"armorStand\":{\"small\":true}}}"),
                    new DocumentStore.Change("npcs/town/guard.json", "missing", npc("guard", "12345678-1234-1234-1234-123456789abc", "town", "")),
                    new DocumentStore.Change("npcs/town/stand.json", "missing", npc("stand", "22345678-1234-1234-1234-123456789abc", "town", "").replace("minecraft:player", "minecraft:armor_stand"))));
            NpcRepository repository = new NpcRepository(store);
            assertTrue(repository.reload().isEmpty());
            IOException refused = assertThrows(IOException.class, () -> TraitGroupCommands.change(repository, "stands", TraitGroupCommands.selector(repository, "town", "", Set.of()), true));
            assertTrue(refused.getMessage().startsWith("guard: with groups stands: $.armorStand"), refused.getMessage());
            assertTrue(repository.find("stand").orElseThrow().groups().isEmpty(), "nothing is written when one NPC is refused");
        }
    }

    @Test void theGroupsCommandsTakeOneKeyedSelector() {
        AnimusDirector director = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> parameter.equals("group") ? List.of("watch", "market") : List.of());
        AnimusDirectorTest.Actor admin = new AnimusDirectorTest.Actor(false, AnimusDirector.ADMIN);
        director.execute(admin, new String[]{"groups", "assign", "watch"});
        assertTrue(admin.text().contains(TraitGroupCommands.SELECTORS), admin.text());
        admin.lines.clear();
        director.execute(admin, new String[]{"groups", "assign", "watch", "folder=town", "tag=guard"});
        assertTrue(admin.text().contains(TraitGroupCommands.SELECTORS), admin.text());
        admin.lines.clear();
        director.execute(admin, new String[]{"groups", "assign", "watch", "town"});
        assertTrue(admin.text().contains("Unexpected \"town\"") && admin.text().contains("Usage: /animus groups assign <group> [folder=] [tag=] [ids=]"), admin.text());
        admin.lines.clear();
        director.execute(admin, new String[]{"groups", "unassign", "watch", "selection"});
        assertTrue(admin.text().contains("Unexpected \"selection\""), admin.text());
        admin.lines.clear();
        director.execute(admin, new String[]{"groups", "assign", "watch", "ids=@selected"});
        assertTrue(admin.text().contains("The console has no selection"), admin.text());
        admin.lines.clear();
        director.execute(admin, new String[]{"groups", "inspect"});
        assertTrue(admin.text().contains("Missing argument \"group\""), admin.text());

        director.execute(admin, new String[]{"groups"});
        assertEquals(List.of("assign", "inspect", "list", "members", "unassign"), admin.entries());
        assertEquals(List.of("market", "watch"), director.complete(admin, new String[]{"groups", "inspect", ""}));
        assertEquals(List.of("market", "watch", "folder=", "tag=", "ids="), director.complete(admin, new String[]{"groups", "assign", ""}));
        assertEquals(List.of("folder=", "tag=", "ids="), director.complete(admin, new String[]{"groups", "assign", "watch", ""}));
        assertEquals(List.of("page="), director.complete(admin, new String[]{"groups", "list", ""}));

        AnimusDirectorTest.Actor visitor = new AnimusDirectorTest.Actor(true, "animus.status");
        director.execute(visitor, new String[]{"groups", "list"});
        assertTrue(visitor.text().contains("No permission: animus."), visitor.text());
        assertEquals(List.of(), director.complete(visitor, new String[]{"groups", "inspect", ""}));
    }
}
