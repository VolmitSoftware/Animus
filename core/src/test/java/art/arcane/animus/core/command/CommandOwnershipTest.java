package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.npc.NpcDefinition;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Every command that names an NPC refuses a player who neither owns it nor is an admin, in one line. */
class CommandOwnershipTest {
    private static NpcDefinition npc(String id, Optional<UUID> owner) throws IOException {
        return NpcDefinition.parse("{\"schemaVersion\":2,\"revision\":1,\"uuid\":\"" + UUID.nameUUIDFromBytes(id.getBytes()) + "\",\"id\":\"" + id + "\",\"group\":\"\",\"name\":\"" + id
                + "\",\"type\":\"minecraft:player\",\"spawned\":true,\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0}"
                + owner.map((UUID player) -> ",\"owner\":\"" + player + "\"").orElse("") + "}");
    }

    @Test void anOwnerActsOnTheirOwnAndIsRefusedOnTheRest() throws IOException {
        AnimusDirectorTest.Actor alex = new AnimusDirectorTest.Actor(true, PermissionNodes.NPC_CREATE, PermissionNodes.NPC_EDIT, PermissionNodes.NPC_MOVE);
        assertTrue(CommandGroup.owned(alex, Optional.of(npc("stall", alex.player()))));
        assertFalse(CommandGroup.owned(alex, Optional.of(npc("mayor", Optional.empty()))));
        assertEquals("[Animus]: mayor is not yours; you can change only NPCs you own", alex.lines.getLast());
        assertFalse(CommandGroup.owned(alex, Optional.of(npc("booth", Optional.of(UUID.randomUUID())))), "nor another player's");
        AnimusDirectorTest.Actor admin = new AnimusDirectorTest.Actor(true, PermissionNodes.ADMIN), console = new AnimusDirectorTest.Actor(false);
        assertTrue(CommandGroup.owned(admin, Optional.of(npc("mayor", Optional.empty()))));
        assertTrue(CommandGroup.owned(console, Optional.of(npc("booth", Optional.of(UUID.randomUUID())))));
        assertTrue(CommandGroup.owned(alex, Optional.empty()), "an unknown id is the lookup's to report");
        AnimusDirectorTest.Actor staff = new AnimusDirectorTest.Actor(true, PermissionNodes.NPC_EDIT, PermissionNodes.NPC_OTHERS);
        assertTrue(CommandGroup.owned(staff, Optional.of(npc("mayor", Optional.empty()))), "animus.npc.others reaches server NPCs");
        assertTrue(CommandGroup.owned(staff, Optional.of(npc("booth", Optional.of(UUID.randomUUID())))), "and other players' NPCs");
    }

    @Test void onlyAnAdminPatchesTheOwner() {
        assertTrue(AnimusCommandTree.namesOwner("{\"owner\":null}"));
        assertTrue(AnimusCommandTree.namesOwner("{\"owner\":\"0000000a-0000-4000-8000-0000000000a1\",\"name\":\"Mine\"}"));
        assertFalse(AnimusCommandTree.namesOwner("{\"name\":\"Mine\"}"));
        assertFalse(AnimusCommandTree.namesOwner("not json"));
    }
}
