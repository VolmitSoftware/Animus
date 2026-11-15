package art.arcane.animus.core.api;

import art.arcane.animus.api.DefinitionSnapshot;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.NpcLocation;
import art.arcane.animus.api.NpcService;
import art.arcane.animus.api.NpcSpec;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.owner.OwnerPermissions;
import art.arcane.animus.core.storage.DocumentStore;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** A plugin's NPC is a server NPC unless the plugin names an owner; an owned one, saved or temporary, gains no power its owner lacks. */
class OwnedApiNpcsTest {
    private static final UUID ALEX = UUID.fromString("0000000a-0000-4000-8000-0000000000a1");
    private static final NpcLocation HERE = NpcLocation.of(ApiHarness.WORLD, 1, 64, 2);
    private static final String OWNED = "\"owner\":\"" + ALEX + "\"";
    @TempDir Path root;
    private ApiHarness harness;
    private NpcService npcs;
    /** The nodes ALEX holds; everything else is a no. */
    private final Set<String> held = new HashSet<>();

    @BeforeEach void start() throws Exception {
        harness = new ApiHarness(root);
        harness.runtime.ownerPermissions(new OwnerPermissions() {
            @Override public Answer has(UUID player, String node) { return player.equals(ALEX) && held.contains(node) ? Answer.YES : Answer.NO; }
            @Override public Answer cached(UUID player, String node) { return has(player, node); }
        });
        harness.runtime.documents().commit(List.of(new DocumentStore.Change("behaviors/town/reward.json", harness.runtime.documents().hash("behaviors/town/reward.json"),
                "{\"schemaVersion\":1,\"id\":\"town:reward\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"action\",\"capability\":\"animus:command\","
                        + "\"arguments\":{\"command\":\"give {player_name} diamond 64\"}}},\"triggers\":[{\"id\":\"click\",\"event\":\"interaction\",\"routine\":\"main\"}]}")));
        npcs = harness.service("plugin:Pets");
    }
    @AfterEach void stop() { harness.close(); }

    private static <T> Outcome<T> await(CompletionStage<Outcome<T>> stage) throws Exception { return stage.toCompletableFuture().get(10, TimeUnit.SECONDS); }
    private static NpcSpec spec(String id, String json) { return NpcSpec.builder().id(id).location(HERE).json(json).build(); }
    private static void refused(Outcome<?> outcome, String what) {
        assertEquals(Outcome.Status.INVALID, outcome.status(), outcome.diagnostics().toString());
        assertTrue(outcome.diagnostics().stream().anyMatch((art.arcane.animus.api.Diagnostic diagnostic) -> diagnostic.message().contains("cannot " + what + " unless its owner holds")),
                outcome.diagnostics().toString());
    }

    @Test void aPluginNpcIsAServerNpcUnlessThePluginNamesAnOwner() throws Exception {
        assertEquals(Outcome.Status.OK, await(npcs.create(NpcSpec.builder().id("guard").location(HERE).build())).status());
        assertTrue(harness.runtime.npcs().find("guard").orElseThrow().owner().isEmpty());
        assertEquals(Outcome.Status.OK, await(npcs.createTemporary(spec("pet", "{" + OWNED + "}"))).status());
        assertEquals(Optional.of(ALEX), harness.runtime.npcs().find("pet").orElseThrow().owner());
    }

    @Test void aSavedOwnedNpcCannotRunCommandsItsOwnerCannot() throws Exception {
        refused(await(npcs.create(spec("stall", "{" + OWNED + ",\"behaviors\":[\"town:reward\"]}"))), "run commands (behavior town:reward)");
        assertTrue(harness.runtime.npcs().find("stall").isEmpty());
        held.add(PermissionNodes.OWNED_COMMANDS);
        assertEquals(Outcome.Status.OK, await(npcs.create(spec("stall", "{" + OWNED + ",\"behaviors\":[\"town:reward\"]}"))).status());
    }

    /** The plugin answers for what its own module does, so it may put the module on an NPC a player owns without any owned node. */
    @Test void aPluginsOwnModuleRidesOnAnOwnedNpcWithoutAnyNode() throws Exception {
        assertEquals(Outcome.Status.OK, npcs.modules().register(new art.arcane.animus.api.NpcModules.Descriptor(new art.arcane.animus.api.Capabilities.Key("pets", "collar"), "Collar", 0),
                new art.arcane.animus.api.NpcModules.Module() {}).status());
        Outcome<DefinitionSnapshot> temporary = await(npcs.createTemporary(NpcSpec.builder().id("pet").location(HERE).json("{" + OWNED + "}").module("pets:collar", "{}").build()));
        assertEquals(Outcome.Status.OK, temporary.status(), temporary.diagnostics().toString());
        Outcome<DefinitionSnapshot> saved = await(npcs.create(NpcSpec.builder().id("stall").location(HERE).json("{" + OWNED + "}").module("pets:collar", "{}").build()));
        assertEquals(Outcome.Status.OK, saved.status(), saved.diagnostics().toString());
        for (NpcHandle npc : List.of(temporary.value().orElseThrow().npc(), saved.value().orElseThrow().npc())) {
            assertEquals(Outcome.Status.OK, await(npcs.updateModule(npc, "pets:collar", (Optional<String> data) -> Optional.of("{\"color\":\"red\"}"))).status());
            assertEquals(Outcome.Status.OK, await(npcs.edit(npc, NpcSpec.builder().module("pets:collar", "{\"color\":\"blue\"}").build())).status());
        }
        assertTrue(held.isEmpty(), "the owner holds no owned node");
        refused(await(npcs.createTemporary(spec("booth", "{" + OWNED + ",\"modules\":{\"other:thing\":{}}}"))), "carry a module from another plugin (module other:thing)");
    }

    /** A player writing a plugin's module data themselves, in a command or the menu, still needs the node. */
    @Test void aPlayerChangingAPluginsModuleDataStillNeedsTheServerNode() throws Exception {
        assertEquals(Outcome.Status.OK, npcs.modules().register(new art.arcane.animus.api.NpcModules.Descriptor(new art.arcane.animus.api.Capabilities.Key("pets", "collar"), "Collar", 0),
                new art.arcane.animus.api.NpcModules.Module() {}).status());
        assertEquals(Outcome.Status.OK, await(npcs.create(NpcSpec.builder().id("stall").location(HERE).json("{" + OWNED + "}").module("pets:collar", "{}").build())).status());
        art.arcane.animus.core.admin.Administration admin = new art.arcane.animus.core.admin.Administration(harness.runtime);
        art.arcane.animus.core.owner.ActingPlayer.Actor alex = new art.arcane.animus.core.owner.ActingPlayer.Actor(ALEX, false, false);
        java.io.IOException refused = assertThrows(java.io.IOException.class, () -> art.arcane.animus.core.owner.ActingPlayer.call(Optional.of(alex),
                () -> admin.edit("stall", "{\"modules\":{\"pets:collar\":{\"color\":\"gold\"}}}")));
        assertTrue(refused.getMessage().contains("cannot change a plugin's module data (module pets:collar) unless its owner holds " + PermissionNodes.OWNED_SERVER), refused.getMessage());
        art.arcane.animus.core.owner.ActingPlayer.call(Optional.of(alex), () -> admin.rename("stall", "Pet stall"));
        held.add(PermissionNodes.OWNED_SERVER);
        art.arcane.animus.core.owner.ActingPlayer.call(Optional.of(alex), () -> admin.edit("stall", "{\"modules\":{\"pets:collar\":{\"color\":\"gold\"}}}"));
    }

    @Test void aTemporaryOwnedNpcIsCheckedAsASavedOneIs() throws Exception {
        refused(await(npcs.createTemporary(spec("booth", "{" + OWNED + ",\"behaviors\":[\"town:reward\"]}"))), "run commands (behavior town:reward)");
        assertTrue(harness.runtime.npcs().find("booth").isEmpty(), "nothing is published");
        Outcome<DefinitionSnapshot> plain = await(npcs.createTemporary(spec("pet", "{" + OWNED + "}")));
        assertEquals(Outcome.Status.OK, plain.status(), plain.diagnostics().toString());
        NpcHandle pet = plain.value().orElseThrow().npc();
        refused(await(npcs.edit(pet, NpcSpec.builder().json("{\"behaviors\":[\"town:reward\"]}").build())), "run commands (behavior town:reward)");
        refused(await(npcs.updateModule(pet, "animus:health", (Optional<String> data) -> Optional.of("{\"death\":{\"drops\":[{\"item\":{\"id\":\"minecraft:diamond\"}}]}}"))),
                "create items (death drops)");
        assertTrue(harness.runtime.npcs().find("pet").orElseThrow().behaviors().isEmpty());
        held.add(PermissionNodes.OWNED_COMMANDS);
        assertEquals(Outcome.Status.OK, await(npcs.edit(pet, NpcSpec.builder().json("{\"behaviors\":[\"town:reward\"]}").build())).status());
        held.clear();
        assertEquals(Outcome.Status.OK, await(npcs.edit(pet, NpcSpec.builder().name("Rex").build())).status(), "what it already had stays when the owner loses the node");
    }
}
