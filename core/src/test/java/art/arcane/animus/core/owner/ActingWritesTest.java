package art.arcane.animus.core.owner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.admin.Administration;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** File work a player starts reaches only their own NPCs, unless animus.npc.others lets them reach every NPC. */
class ActingWritesTest {
    static final UUID ALEX = OwnedNpcAttackTest.ALEX, SAM = UUID.fromString("0000000b-0000-4000-8000-0000000000b2");
    @TempDir Path root;
    private AnimusRuntime runtime;
    private Administration admin;

    @BeforeEach void start() throws IOException {
        runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", root));
        runtime.start();
        admin = new Administration(runtime);
        admin.create("stall", "minecraft:player", "world", 1, 64, 2, "", "Stall", Optional.of(ALEX));
        admin.create("mayor", "minecraft:player", "world", 9, 64, 2, "", "Mayor");
    }

    @AfterEach void stop() { runtime.close(); }

    private String rename(ActingPlayer.Actor actor, String id, String name) throws IOException { return ActingPlayer.call(Optional.of(actor), () -> admin.rename(id, name)); }

    @Test void aPlayerWithoutTheNodeChangesOnlyTheirOwn() throws IOException {
        ActingPlayer.Actor sam = new ActingPlayer.Actor(SAM, false, false);
        assertEquals("mayor is not yours; you can change only NPCs you own", assertThrows(IOException.class, () -> rename(sam, "mayor", "Sam's")).getMessage());
        assertThrows(IOException.class, () -> rename(sam, "stall", "Sam's"));
        rename(new ActingPlayer.Actor(ALEX, false, false), "stall", "Alex's stall");
    }

    @Test void staffWithTheNodeChangeEveryNpcButNotItsOwner() throws IOException {
        ActingPlayer.Actor sam = new ActingPlayer.Actor(SAM, false, true);
        rename(sam, "mayor", "Mayor Sam");
        rename(sam, "stall", "Market stall");
        assertEquals(Optional.of(ALEX), runtime.npcs().find("stall").orElseThrow().owner(), "the owner stays");
        assertEquals(NpcOwnership.OWNER_REFUSAL, assertThrows(IOException.class, () -> ActingPlayer.call(Optional.of(sam), () -> admin.edit("stall", "{\"owner\":null}"))).getMessage());
    }
}
