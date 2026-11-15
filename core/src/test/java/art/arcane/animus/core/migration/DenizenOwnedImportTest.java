package art.arcane.animus.core.migration;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.owner.OwnerPermissions;
import art.arcane.animus.core.owner.OwnerWrites;
import art.arcane.animus.core.storage.DocumentStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A Citizens NPC whose owner trait names a player imports as that player's NPC, with what the import keeps for review,
 * and a Denizen script bound to it is held to the player's nodes: without them the Denizen part is refused with the NPC
 * and the node while the NPC itself still imports.
 */
class DenizenOwnedImportTest {
    private static final UUID ALEX = UUID.fromString("0000000a-0000-4000-8000-0000000000a1");
    @TempDir Path root;
    /** The nodes ALEX holds; everything else is a no. */
    private final Set<String> held = new HashSet<>();

    @AfterEach void clearGuard() { CitizensGuard.clear(); }

    private record Applied(String lines, Optional<NpcDefinition> stall, boolean installed) {}

    private Applied apply(String command) throws IOException {
        Files.createDirectories(root.resolve("plugins/Citizens"));
        Files.writeString(root.resolve("plugins/Citizens/saves.yml"), """
                npc:
                  '0':
                    name: Stall
                    uuid: 0a1b2c3d-0000-4000-8000-000000000500
                    traits:
                      type: PLAYER
                      spawned: true
                      location: {world: world, x: 0, y: 64, z: 0}
                      owner: {owner: Alex, uuid: %s}
                      assignment: {assignment: stall_assignment}
                """.formatted(ALEX));
        Files.createDirectories(root.resolve("plugins/Denizen/scripts"));
        Files.writeString(root.resolve("plugins/Denizen/scripts/stall.dsc"), """
                stall_assignment:
                  type: assignment
                  actions:
                    on assignment:
                    - trigger name:click state:true
                  interact scripts:
                  - stall_interact

                stall_interact:
                  type: interact
                  steps:
                    1:
                      click trigger:
                        script:
                        - narrate "Welcome to the stall."
                        - %s
                """.formatted(command));
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store);
            npcs.loadForStartup();
            OwnerPermissions permissions = new OwnerPermissions() {
                @Override public Answer has(UUID player, String node) { return player.equals(ALEX) && held.contains(node) ? Answer.YES : Answer.NO; }
                @Override public Answer cached(UUID player, String node) { return has(player, node); }
            };
            store.check(new OwnerWrites(store, npcs, () -> permissions, Set::of));
            CitizensDropIn flow = new CitizensDropIn(store, npcs, new CitizensDropIn.Host(Optional.of(root.resolve("plugins")), () -> false, () -> false, () -> false));
            flow.preview("reject", Optional.empty(), Map.of(), false);
            List<String> lines = flow.applyStaged();
            return new Applied(String.join("\n", lines), npcs.find("citizens-0"), store.read("behaviors/denizen/interact/stall_interact.json") != null);
        }
    }

    @Test void anOwnedNpcImportsWithWhatTheImportKeptForReview() throws IOException {
        Applied applied = apply("narrate \"Come back soon.\"");
        NpcDefinition stall = applied.stall().orElseThrow(() -> new AssertionError(applied.lines()));
        assertEquals(Optional.of(ALEX), stall.owner());
        assertTrue(stall.module("citizens:review").isPresent(), "the kept traits stay for review");
        assertTrue(applied.installed() && stall.behaviors().contains("denizen:interact/stall_interact"), applied.lines());
    }

    @Test void aDenizenCommandOnAnOwnedNpcIsRefusedWithTheNpcAndTheNode() throws IOException {
        Applied applied = apply("execute as_server \"give <player.name> diamond\"");
        assertTrue(applied.lines().contains("Denizen import failed, none of it installed: citizens-0 is owned by a player, so it cannot run commands (behavior denizen:interact/stall_interact) unless its owner holds "
                + PermissionNodes.OWNED_COMMANDS), applied.lines());
        assertTrue(applied.stall().isPresent(), "the NPC itself still imports");
        assertFalse(applied.installed());
        assertTrue(applied.stall().get().behaviors().isEmpty());
    }

    @Test void theOwnersNodeLetsTheDenizenCommandImport() throws IOException {
        held.add(PermissionNodes.OWNED_COMMANDS);
        Applied applied = apply("execute as_server \"give <player.name> diamond\"");
        assertTrue(applied.installed(), applied.lines());
        assertEquals(List.of("denizen:interact/stall_interact"), applied.stall().orElseThrow().behaviors().stream().filter((String id) -> id.contains("stall_interact")).toList());
    }
}
