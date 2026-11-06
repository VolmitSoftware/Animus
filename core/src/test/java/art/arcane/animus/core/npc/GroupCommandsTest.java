package art.arcane.animus.core.npc;

import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.storage.DocumentStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupCommandsTest {
    @TempDir Path root;
    static final UUID ADMIN = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");

    static final class Actor implements CommandActor {
        final List<String> told = new ArrayList<>();
        final boolean player;
        Actor(boolean player) { this.player = player; }
        @Override public boolean permitted(String node) { return true; }
        @Override public void tell(String message) { told.add(message); }
        @Override public Optional<UUID> player() { return player ? Optional.of(ADMIN) : Optional.empty(); }
        @Override public boolean reply(Runnable action) { action.run(); return true; }
    }

    private static String npc(String id, String uuid, String folder, String extra) {
        return "{\"schemaVersion\":1,\"revision\":1,\"uuid\":\"" + uuid + "\",\"id\":\"" + id + "\",\"group\":\"" + folder + "\",\"name\":\"N\",\"type\":\"minecraft:player\",\"spawned\":false,"
                + "\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0}" + extra + "}";
    }

    @Test
    void assignAndUnassignEditTheMatchingNpcsInOneTransaction() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(
                    new DocumentStore.Change("groups/watch.json", "missing", "{\"schemaVersion\":1,\"revision\":1,\"id\":\"watch\",\"values\":{\"teamColor\":\"gold\"}}"),
                    new DocumentStore.Change("npcs/town/guard.json", "missing", npc("guard", "12345678-1234-1234-1234-123456789abc", "town", "")),
                    new DocumentStore.Change("npcs/town/baker.json", "missing", npc("baker", "22345678-1234-1234-1234-123456789abc", "town", ",\"groups\":[\"watch\"]")),
                    new DocumentStore.Change("npcs/harbor/sailor.json", "missing", npc("sailor", "32345678-1234-1234-1234-123456789abc", "harbor", ""))));
            NpcRepository repository = new NpcRepository(store);
            assertTrue(repository.reload().isEmpty());
            AtomicInteger refreshes = new AtomicInteger();
            GroupCommands commands = new GroupCommands(() -> repository, Runnable::run, (UUID player) -> Optional.of("sailor"), refreshes::incrementAndGet);
            Actor console = new Actor(false);
            commands.execute(console, new String[]{"groups", "assign", "watch", "folder=town"});
            assertEquals(1, console.told.size(), console.told.toString());
            assertTrue(console.told.getFirst().startsWith("assigned 1, unchanged 1; transaction "), console.told.toString());
            String transaction = console.told.getFirst().substring(console.told.getFirst().lastIndexOf(' ') + 1);
            assertEquals("gold", repository.find("guard").orElseThrow().json().get("teamColor").getAsString());
            assertEquals(1, refreshes.get());
            commands.execute(console, new String[]{"groups", "members", "watch"});
            assertEquals(List.of("Members of watch: 2", "baker, guard"), console.told.subList(1, 3));

            Actor player = new Actor(true);
            commands.execute(player, new String[]{"groups", "assign", "watch", "selection"});
            assertTrue(player.told.getFirst().startsWith("assigned 1, unchanged 0"), player.told.toString());
            commands.execute(player, new String[]{"groups", "unassign", "watch", "ids=baker,sailor,guard"});
            assertTrue(player.told.get(1).startsWith("unassigned 3, unchanged 0"), player.told.toString());
            assertTrue(repository.find("baker").orElseThrow().groups().isEmpty());
            assertTrue(!repository.draft("baker").document().has("groups"));

            commands.execute(console, new String[]{"groups", "assign", "missing", "folder=town"});
            assertTrue(console.told.getLast().startsWith("Animus groups: Unknown group missing"), console.told.toString());
            commands.execute(console, new String[]{"groups", "assign", "watch", "selection"});
            assertTrue(console.told.getLast().contains("selection belongs to a player"), console.told.toString());
            assertTrue(store.paths(transaction).contains("npcs/town/guard.json"));
        }
    }

    @Test
    void anAssignmentWithAnInvalidResultIsRefusedWhole() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(
                    new DocumentStore.Change("groups/stands.json", "missing", "{\"schemaVersion\":1,\"revision\":1,\"id\":\"stands\",\"values\":{\"armorStand\":{\"small\":true}}}"),
                    new DocumentStore.Change("npcs/town/guard.json", "missing", npc("guard", "12345678-1234-1234-1234-123456789abc", "town", "")),
                    new DocumentStore.Change("npcs/town/stand.json", "missing", npc("stand", "22345678-1234-1234-1234-123456789abc", "town", "").replace("minecraft:player", "minecraft:armor_stand"))));
            NpcRepository repository = new NpcRepository(store);
            assertTrue(repository.reload().isEmpty());
            GroupCommands commands = new GroupCommands(() -> repository, Runnable::run, (UUID player) -> Optional.empty(), () -> {});
            Actor console = new Actor(false);
            commands.execute(console, new String[]{"groups", "assign", "stands", "folder=town"});
            assertTrue(console.told.getFirst().startsWith("Animus groups: guard: with groups stands: $.armorStand"), console.told.toString());
            assertTrue(repository.find("stand").orElseThrow().groups().isEmpty(), "nothing is written when one NPC is refused");
        }
    }
}
