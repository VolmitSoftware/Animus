package art.arcane.animus.core.owner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.config.OwnerSettings;
import art.arcane.animus.core.npc.NpcDefinition;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Who acts on which NPC, and how many a player may own. */
class NpcOwnershipTest {
    private static final UUID ALEX = OwnedNpcAttackTest.ALEX, SAM = UUID.fromString("0000000b-0000-4000-8000-0000000000b2");

    static NpcDefinition npc(String id, Optional<UUID> owner) throws IOException {
        return NpcDefinition.parse("{\"schemaVersion\":2,\"revision\":1,\"uuid\":\"" + UUID.nameUUIDFromBytes(id.getBytes()) + "\",\"id\":\"" + id + "\",\"group\":\"\",\"name\":\"" + id
                + "\",\"type\":\"minecraft:player\",\"spawned\":true,\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0}"
                + owner.map((UUID player) -> ",\"owner\":\"" + player + "\"").orElse("") + "}");
    }

    @Test void onlyTheOwnerAnAdminOrTheConsoleActsOnAnNpc() throws IOException {
        NpcDefinition stall = npc("stall", Optional.of(ALEX)), mayor = npc("mayor", Optional.empty());
        Set<String> creator = Set.of(PermissionNodes.NPC_CREATE, PermissionNodes.NPC_EDIT, PermissionNodes.NPC_DELETE);
        assertTrue(NpcOwnership.mayAct(Optional.of(ALEX), creator::contains, stall));
        assertFalse(NpcOwnership.mayAct(Optional.of(SAM), creator::contains, stall), "editing rights do not reach another player's NPC");
        assertFalse(NpcOwnership.mayAct(Optional.of(ALEX), creator::contains, mayor), "nor a server NPC");
        assertTrue(NpcOwnership.mayAct(Optional.of(SAM), Set.of(PermissionNodes.ADMIN)::contains, stall));
        assertTrue(NpcOwnership.mayAct(Optional.empty(), Set.<String>of()::contains, mayor), "the console acts on every NPC");
        assertEquals("mayor is not yours; you can change only NPCs you own", NpcOwnership.refusal(mayor));
    }

    @Test void staffWithTheOthersNodeActOnEveryNpcButStayLimited() throws IOException {
        NpcDefinition stall = npc("stall", Optional.of(ALEX)), mayor = npc("mayor", Optional.empty());
        Set<String> staff = Set.of(PermissionNodes.NPC_EDIT, PermissionNodes.NPC_OTHERS);
        assertTrue(NpcOwnership.mayAct(Optional.of(SAM), staff::contains, mayor), "a server NPC");
        assertTrue(NpcOwnership.mayAct(Optional.of(SAM), staff::contains, stall), "another player's NPC");
        assertEquals(OptionalInt.of(3), NpcOwnership.limit(Optional.of(SAM), staff::contains, new OwnerSettings(3)), "the node is no admin: the limit stays");
        assertTrue(PermissionNodes.CHILDREN.contains(PermissionNodes.NPC_OTHERS), "animus.admin grants it");
    }

    @Test void theHighestLimitNodeWinsElseTheDefault() {
        OwnerSettings three = new OwnerSettings(3);
        assertEquals(OptionalInt.of(3), NpcOwnership.limit(Optional.of(ALEX), Set.<String>of()::contains, three));
        assertEquals(OptionalInt.of(10), NpcOwnership.limit(Optional.of(ALEX), Set.of("animus.npc.limit.2", "animus.npc.limit.10")::contains, three));
        assertEquals(OptionalInt.of(0), NpcOwnership.limit(Optional.of(ALEX), Set.of("animus.npc.limit.0")::contains, three), "a 0 node stops a player making any");
        assertEquals(OptionalInt.empty(), NpcOwnership.limit(Optional.of(ALEX), Set.of(PermissionNodes.ADMIN)::contains, three), "an admin has no limit");
    }

    @Test void onlyNpcsThePlayerOwnsCount() throws IOException {
        List<NpcDefinition> npcs = List.of(npc("a", Optional.of(ALEX)), npc("b", Optional.of(ALEX)), npc("c", Optional.of(SAM)), npc("d", Optional.empty()));
        assertEquals(Optional.empty(), NpcOwnership.full(npcs, ALEX, OptionalInt.of(3)));
        assertEquals(Optional.of("You own 2 NPCs, your limit; remove one to make another"), NpcOwnership.full(npcs, ALEX, OptionalInt.of(2)));
        assertEquals(Optional.empty(), NpcOwnership.full(npcs, ALEX, OptionalInt.empty()));
    }
}
