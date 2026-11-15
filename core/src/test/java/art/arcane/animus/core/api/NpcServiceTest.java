package art.arcane.animus.core.api;

import art.arcane.animus.api.Audience;
import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.DefinitionSnapshot;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.Flag;
import art.arcane.animus.api.ItemSpec;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.NpcLocation;
import art.arcane.animus.api.NpcModules;
import art.arcane.animus.api.NpcService;
import art.arcane.animus.api.NpcSpec;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NpcServiceTest {
    @TempDir Path root;
    private ApiHarness harness;
    private NpcService npcs;
    private static final NpcLocation HERE = NpcLocation.of(ApiHarness.WORLD, 1, 64, 2);

    @BeforeEach void start() throws Exception { harness = new ApiHarness(root); npcs = harness.service("plugin:Demo"); }
    @AfterEach void stop() { harness.close(); }

    private static <T> Outcome<T> await(CompletionStage<Outcome<T>> stage) throws Exception { return stage.toCompletableFuture().get(10, TimeUnit.SECONDS); }
    private static String code(Outcome<?> outcome) { return outcome.diagnostics().isEmpty() ? "" : outcome.diagnostics().getFirst().code(); }
    private NpcHandle temporary(String id) throws Exception {
        Outcome<DefinitionSnapshot> created = await(npcs.createTemporary(NpcSpec.builder().id(id).location(HERE).build()));
        assertEquals(Outcome.Status.OK, created.status(), created.diagnostics().toString());
        return created.value().orElseThrow().npc();
    }

    // ---- Lifecycle ----

    @Test void aTemporaryNpcIsNeverSavedAndEndsWithItsPlugin() throws Exception {
        Outcome<DefinitionSnapshot> created = await(npcs.createTemporary(NpcSpec.builder().name("&aVisitor").location(HERE).build()));
        assertEquals(Outcome.Status.OK, created.status(), created.diagnostics().toString());
        DefinitionSnapshot snapshot = created.value().orElseThrow();
        assertTrue(snapshot.id().startsWith("temp-"), "an id is generated");
        NpcDefinition definition = harness.runtime.npcs().find(snapshot.id()).orElseThrow();
        assertEquals("minecraft:player", definition.json().get("type").getAsString());
        assertTrue(definition.spawned(), "new NPCs are spawned by default");
        assertTrue(harness.runtime.npcs().stored().isEmpty());
        try (var files = Files.walk(root)) { assertTrue(files.noneMatch((Path file) -> file.getFileName().toString().equals(snapshot.id() + ".json")), "no file is written"); }
        assertTrue(await(npcs.state(snapshot.npc())).value().orElseThrow().temporary());
        harness.api.release("plugin:Demo");
        assertTrue(harness.runtime.npcs().find(snapshot.id()).isEmpty(), "release removes the plugin's temporary NPCs");
        assertEquals(List.of("plugin:Demo stopped: removed 1 temporary NPC"), harness.logged);
        assertEquals(Outcome.Status.UNAVAILABLE, await(npcs.createTemporary(NpcSpec.builder().location(HERE).build())).status(), "a released service refuses");
    }

    @Test void creatingASpawnedNpcWaitsForItsBodyButNotForever() throws Exception {
        harness.living.dormant = true;
        CompletionStage<Outcome<DefinitionSnapshot>> waiting = npcs.createTemporary(NpcSpec.builder().id("late").location(HERE).build());
        for (int i = 0; i < 20 && harness.runtime.npcs().find("late").isEmpty(); i++) Thread.sleep(25);
        assertTrue(harness.runtime.npcs().find("late").isPresent(), "saved before the body appears");
        assertFalse(waiting.toCompletableFuture().isDone(), "the stage waits for the body");
        harness.living.dormant = false;
        harness.tick(1);
        assertEquals(Outcome.Status.OK, await(waiting).status());
        harness.living.dormant = true;
        CompletionStage<Outcome<DefinitionSnapshot>> dormant = npcs.createTemporary(NpcSpec.builder().id("never").location(HERE).build());
        for (int i = 0; i < 20 && harness.runtime.npcs().find("never").isEmpty(); i++) Thread.sleep(25);
        Thread.sleep(100);
        harness.tick(NpcApi.ACTIVE_WAIT_TICKS + 1);
        assertEquals(Outcome.Status.OK, await(dormant).status(), "a body that does not appear in time still answers");
        assertFalse(await(npcs.state(await(dormant).value().orElseThrow().npc())).value().orElseThrow().active());
    }

    @Test void aPersistentNpcIsSavedChecksItsIdAndTypeAndLogsAnUndo() throws Exception {
        assertEquals("id-missing", code(await(npcs.create(NpcSpec.builder().location(HERE).build()))));
        assertEquals("location-missing", code(await(npcs.create(NpcSpec.builder().id("guard").build()))));
        Outcome<DefinitionSnapshot> body = await(npcs.create(NpcSpec.builder().id("guard").type("minecraft:stone").location(HERE).build()));
        assertEquals(Outcome.Status.INVALID, body.status());
        assertEquals("$.type", body.diagnostics().getFirst().field());
        Outcome<DefinitionSnapshot> saved = await(npcs.create(NpcSpec.builder().id("guard").group("town").name("Guard").location(HERE)
                .equip(NpcSpec.Slot.MAIN_HAND, ItemSpec.of("iron_sword")).pose(NpcSpec.Pose.SNEAKING).build()));
        assertEquals(Outcome.Status.OK, saved.status(), saved.diagnostics().toString());
        assertTrue(Files.exists(root.resolve("npcs/town/guard.json")));
        String json = Files.readString(root.resolve("npcs/town/guard.json"));
        assertTrue(json.contains("\"minecraft:iron_sword\"") && json.contains("\"sneaking\""), json);
        assertTrue(harness.logged.getFirst().startsWith("plugin:Demo created guard; /animus undo "), harness.logged.toString());
        assertEquals(Outcome.Status.CONFLICT, await(npcs.create(NpcSpec.builder().id("guard").location(HERE).build())).status());
        assertEquals(Outcome.Status.CONFLICT, await(npcs.createTemporary(NpcSpec.builder().id("guard").location(HERE).build())).status());
    }

    @Test void badInputComesBackAsDiagnosticsNeverAsExceptions() throws Exception {
        assertEquals("id-invalid", code(await(npcs.create(NpcSpec.builder().id("Not Valid").location(HERE).build()))));
        Outcome<DefinitionSnapshot> coordinate = await(npcs.createTemporary(NpcSpec.builder().location(new NpcLocation(ApiHarness.WORLD, Double.NaN, 64, 0, 0, 0)).build()));
        assertEquals(Outcome.Status.INVALID, coordinate.status());
        assertEquals("$.position.x", coordinate.diagnostics().getFirst().field());
        assertEquals("module-key", code(await(npcs.createTemporary(NpcSpec.builder().location(HERE).module("NoColon", "{}").build()))));
        assertEquals("module-json", code(await(npcs.createTemporary(NpcSpec.builder().location(HERE).module("demo:clicks", "[1]").build()))));
        assertEquals("field-owned", code(await(npcs.createTemporary(NpcSpec.builder().location(HERE).json("{\"uuid\":\"x\"}").build()))));
        assertEquals("json-invalid", code(await(npcs.createTemporary(NpcSpec.builder().location(HERE).json("not json").build()))));
        Outcome<DefinitionSnapshot> slot = await(npcs.createTemporary(NpcSpec.builder().type("minecraft:pig").location(HERE).equip(NpcSpec.Slot.HEAD, ItemSpec.of("diamond_helmet")).build()));
        assertEquals("$.equipment.head", slot.diagnostics().getFirst().field());
        harness.living.activation.add("$.position.world: world is not loaded");
        Outcome<DefinitionSnapshot> refused = await(npcs.create(NpcSpec.builder().id("ghost").location(HERE).build()));
        assertEquals(Outcome.Status.INVALID, refused.status());
        assertEquals("$.position.world", refused.diagnostics().getFirst().field());
        assertTrue(harness.runtime.npcs().find("ghost").isEmpty(), "an NPC that could not go live is not saved");
    }

    @Test void anEditChangesOnlyItsPartsAndKeepsTheId() throws Exception {
        NpcHandle npc = temporary("visitor");
        Outcome<DefinitionSnapshot> renamed = await(npcs.edit(npc, NpcSpec.builder().name("Renamed").json("{\"hologram\":{\"lines\":[\"Hello\"]}}").build()));
        assertEquals(Outcome.Status.OK, renamed.status(), renamed.diagnostics().toString());
        assertEquals(2, renamed.value().orElseThrow().token().revision());
        NpcDefinition definition = harness.runtime.npcs().find("visitor").orElseThrow();
        assertEquals("Renamed", definition.name());
        assertTrue(definition.json().has("hologram"));
        assertEquals("identity-change", code(await(npcs.edit(npc, NpcSpec.builder().id("other").build()))));
        assertEquals(Outcome.Status.NOT_FOUND, await(npcs.edit(new NpcHandle(UUID.randomUUID()), NpcSpec.builder().name("x").build())).status());
    }

    @Test void moduleUpdatesNeverLoseEachOtherAndKeepTheLiveBody() throws Exception {
        NpcHandle npc = temporary("counter");
        String before = harness.runtime.npcs().find("counter").orElseThrow().runtimeForm();
        List<CompletionStage<Outcome<DefinitionSnapshot>>> updates = new ArrayList<>();
        for (int i = 0; i < 20; i++) updates.add(npcs.updateModule(npc, "demo:clicks", (Optional<String> data) -> {
            int count = data.map((String json) -> JsonParser.parseString(json).getAsJsonObject().get("count").getAsInt()).orElse(0);
            return Optional.of("{\"count\":" + (count + 1) + "}");
        }));
        for (CompletionStage<Outcome<DefinitionSnapshot>> update : updates) assertEquals(Outcome.Status.OK, await(update).status());
        NpcDefinition counted = harness.runtime.npcs().find("counter").orElseThrow();
        assertEquals(20, counted.module("demo:clicks").orElseThrow().get("count").getAsInt());
        assertEquals(before, counted.runtimeForm(), "plugin module data does not change the body");
        assertEquals(Outcome.Status.OK, await(npcs.updateModule(npc, "demo:clicks", (Optional<String> data) -> Optional.empty())).status());
        assertTrue(harness.runtime.npcs().find("counter").orElseThrow().module("demo:clicks").isEmpty());
    }

    @Test void removingASavedNpcDeletesItsFileAndRemovingATemporaryOneForgetsIt() throws Exception {
        NpcHandle saved = await(npcs.create(NpcSpec.builder().id("guard").location(HERE).build())).value().orElseThrow().npc();
        assertEquals(Outcome.Status.OK, await(npcs.remove(saved)).status());
        assertFalse(Files.exists(root.resolve("npcs/guard.json")));
        NpcHandle visitor = temporary("visitor");
        assertEquals(Outcome.Status.OK, await(npcs.remove(visitor)).status());
        assertTrue(harness.runtime.npcs().find("visitor").isEmpty());
        assertEquals(Outcome.Status.NOT_FOUND, await(npcs.remove(visitor)).status());
    }

    // ---- Modules ----

    @Test void modulesClaimTheirOwnNamespaceAndValidateTheirData() throws Exception {
        NpcModules.Module counter = new NpcModules.Module() {
            @Override public List<Diagnostic> validate(String data) {
                return JsonParser.parseString(data).getAsJsonObject().has("count") ? List.of() : List.of(new Diagnostic(Diagnostic.Severity.ERROR, "count", "$.count", "expected a count"));
            }
        };
        assertEquals("module-namespace-reserved", code(npcs.modules().register(new NpcModules.Descriptor(new Capabilities.Key("animus", "clicks"), "Clicks", 0), counter)));
        assertEquals("module-tick-interval", code(npcs.modules().register(new NpcModules.Descriptor(new Capabilities.Key("demo", "clicks"), "Clicks", 5000), counter)));
        assertEquals(Outcome.Status.OK, npcs.modules().register(new NpcModules.Descriptor(new Capabilities.Key("demo", "clicks"), "Counts clicks", 0), counter).status());
        NpcService other = harness.service("plugin:Other");
        assertEquals(Outcome.Status.CONFLICT, other.modules().register(new NpcModules.Descriptor(new Capabilities.Key("demo", "clicks"), "Again", 0), counter).status());
        Outcome<DefinitionSnapshot> refused = await(npcs.createTemporary(NpcSpec.builder().location(HERE).module("demo:clicks", "{}").build()));
        assertEquals(Outcome.Status.INVALID, refused.status());
        assertEquals("$.modules.demo:clicks.count", refused.diagnostics().getFirst().field());
        assertEquals(Outcome.Status.OK, await(npcs.createTemporary(NpcSpec.builder().location(HERE).module("demo:clicks", "{\"count\":0}").build())).status());
        harness.api.release("plugin:Demo");
        assertEquals(Outcome.Status.OK, other.modules().register(new NpcModules.Descriptor(new Capabilities.Key("demo", "clicks"), "Again", 0), counter).status(), "release frees the key");
    }

    @Test void clickSpawnAndTickHooksSeeTheModuleData() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        npcs.modules().register(new NpcModules.Descriptor(new Capabilities.Key("demo", "watch"), "Watches", 4), new NpcModules.Module() {
            @Override public void spawned(NpcModules.Context npc) { seen.add("spawn " + npc.id() + " " + npc.data()); }
            @Override public void clicked(NpcModules.Context npc, UUID player, boolean attack) { seen.add("click " + npc.id() + " " + attack + " " + npc.temporary()); }
            @Override public void ticked(NpcModules.Context npc) { seen.add("tick " + npc.id() + " " + Thread.currentThread().getName()); }
        });
        NpcHandle npc = await(npcs.createTemporary(NpcSpec.builder().id("watched").location(HERE).module("demo:watch", "{\"a\":1}").build())).value().orElseThrow().npc();
        temporary("plain");
        harness.api.spawned(npc.uuid());
        harness.api.clicked(npc.uuid(), UUID.randomUUID(), true);
        harness.tick(8);
        harness.settle();
        assertEquals(List.of("spawn watched {\"a\":1}", "click watched true true", "tick watched fake-owner", "tick watched fake-owner"), seen);
    }

    // ---- Runtime and threads ----

    @Test void runtimeWorkRunsOnTheNpcThreadAndResultsArriveOnAWorker() throws Exception {
        NpcHandle npc = temporary("visitor");
        AtomicReference<String> completed = new AtomicReference<>();
        java.util.concurrent.CountDownLatch busy = new java.util.concurrent.CountDownLatch(1);
        harness.owner.execute(() -> { try { busy.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } });
        CompletionStage<Outcome<NpcHandle>> pending = npcs.gesture(npc, NpcService.Gesture.SWING).whenComplete((Outcome<NpcHandle> outcome, Throwable failure) -> completed.set(Thread.currentThread().getName()));
        busy.countDown();
        Outcome<NpcHandle> waved = pending.toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(Outcome.Status.OK, waved.status());
        assertEquals(List.of("animate visitor SWING"), harness.calls, "the fake runtime throws off its owner thread");
        assertTrue(completed.get().startsWith("Animus-api-"), completed.get());
        assertEquals(Outcome.Status.NOT_FOUND, await(npcs.gesture(new NpcHandle(UUID.randomUUID()), NpcService.Gesture.SWING)).status());
        harness.living.retire(npc.uuid());
        assertEquals("npc-inactive", code(await(npcs.gesture(npc, NpcService.Gesture.SWING))));
        assertEquals(Outcome.Status.FAILED, await(npcs.run(temporary("other"), () -> { throw new IllegalStateException("boom"); })).status());
    }

    @Test void navigationCompletesOnArrivalAndIsCancelledByStop() throws Exception {
        NpcHandle npc = temporary("walker");
        assertEquals("speed-range", code(await(npcs.navigate(npc, HERE, 9))));
        assertEquals("world-unknown", code(await(npcs.navigate(npc, NpcLocation.of("nether", 0, 64, 0), 0))));
        CompletionStage<Outcome<NpcLocation>> walk = npcs.navigate(npc, NpcLocation.of(ApiHarness.WORLD, 5, 64, 5), 0);
        harness.settle();
        assertEquals(List.of("navigate walker 5 64 5 at 2.0"), harness.calls);
        harness.living.routes.get("walker").complete(null);
        Outcome<NpcLocation> arrived = await(walk);
        assertEquals(Outcome.Status.OK, arrived.status());
        assertEquals(ApiHarness.WORLD, arrived.value().orElseThrow().world());
        CompletionStage<Outcome<NpcLocation>> stopped = npcs.navigate(npc, NpcLocation.of(ApiHarness.WORLD, 9, 64, 9), 3);
        harness.settle();
        assertEquals(Outcome.Status.OK, await(npcs.stop(npc)).status());
        assertEquals(Outcome.Status.CANCELLED, await(stopped).status());
    }

    @Test void aFollowEndsWhenItsTargetIsLostOrItIsStopped() throws Exception {
        NpcHandle npc = temporary("dog");
        UUID owner = UUID.randomUUID();
        assertEquals("distance", code(await(npcs.follow(npc, owner, 0.2))));
        harness.entities.put(owner, new WorldPosition(ApiHarness.WORLD_KEY, new Vec3(11, 64, 2)));
        CompletionStage<Outcome<NpcHandle>> following = npcs.follow(npc, owner, 2);
        harness.tick(10);
        harness.settle();
        assertTrue(harness.calls.stream().anyMatch((String call) -> call.startsWith("navigate dog 9 64 2")), harness.calls.toString());
        harness.entities.remove(owner);
        for (int step = 0; step < 12; step++) { harness.tick(10); harness.settle(); }
        assertEquals("follow-lost", code(await(following)));
        CompletionStage<Outcome<NpcHandle>> again = npcs.follow(npc, owner, 2);
        assertEquals(Outcome.Status.OK, await(npcs.stop(npc)).status());
        assertEquals(Outcome.Status.CANCELLED, await(again).status());
    }

    @Test void speechChecksItsTextAndReachesChatAndBubble() throws Exception {
        NpcHandle npc = temporary("talker");
        assertEquals("text", code(await(npcs.speak(npc, "x".repeat(2000), Audience.everyone(), NpcService.Speech.CHAT))));
        assertEquals("radius", code(await(npcs.speak(npc, "Hi", Audience.within(500), NpcService.Speech.CHAT))));
        Outcome<Integer> said = await(npcs.speak(npc, "&eHello", Audience.within(16), NpcService.Speech.BOTH));
        assertEquals(3, said.value().orElseThrow());
        assertEquals(List.of("bubble talker &eHello 100", "say &eHello radius=16.0", "heard Hello"), harness.calls);
        assertEquals(0, await(npcs.speak(npc, "Quiet", Audience.everyone(), NpcService.Speech.BUBBLE)).value().orElseThrow());
    }

    @Test void equipmentPoseTeleportAndSeatRunOnTheRuntime() throws Exception {
        NpcHandle npc = temporary("model");
        assertEquals(Outcome.Status.OK, await(npcs.equip(npc, NpcSpec.Slot.HEAD, Optional.of(ItemSpec.of("diamond_helmet")))).status());
        assertEquals(Outcome.Status.OK, await(npcs.equip(npc, NpcSpec.Slot.HEAD, Optional.empty())).status());
        assertEquals(Outcome.Status.INVALID, await(npcs.equip(npc, NpcSpec.Slot.HEAD, Optional.of(ItemSpec.of("barrier")))).status());
        assertEquals(Outcome.Status.OK, await(npcs.pose(npc, NpcSpec.Pose.SLEEPING)).status());
        assertEquals(Outcome.Status.OK, await(npcs.teleport(npc, NpcLocation.of(ApiHarness.WORLD, 3, 70, 3).facing(90, 0))).status());
        assertEquals("teleport-blocked", code(await(npcs.teleport(npc, NpcLocation.of(ApiHarness.WORLD, 3, -70, 3)))));
        UUID player = UUID.randomUUID();
        assertEquals("player-offline", code(await(npcs.seat(npc, player))));
        harness.online.add(player);
        assertEquals(Outcome.Status.OK, await(npcs.seat(npc, player)).status());
        assertTrue(await(npcs.unseat(player)).value().orElseThrow());
        assertEquals(List.of("equip model HEAD minecraft:diamond_helmet", "equip model HEAD air", "rest model SLEEPING", "teleport model 3 70 3 facing 90.0",
                "teleport model 3 -70 3 facing 0.0", "seat " + player, "unseat Alex"), harness.calls);
    }

    @Test void hidingIsPerPlayerAndEndsWithTheOwner() throws Exception {
        NpcHandle npc = temporary("shy");
        UUID alex = UUID.randomUUID(), sam = UUID.randomUUID();
        assertEquals(Outcome.Status.OK, await(npcs.show(npc, alex, false)).status());
        assertTrue(harness.living.previews().hides(alex, "shy", npc.uuid()));
        assertFalse(harness.living.previews().hides(sam, "shy", npc.uuid()));
        await(npcs.show(npc, alex, true));
        assertFalse(harness.living.previews().hides(alex, "shy", npc.uuid()));
        await(npcs.show(npc, alex, false));
        await(npcs.show(npc, sam, false));
        harness.api.playerLeft(sam);
        assertFalse(harness.living.previews().hides(sam, "shy", npc.uuid()), "a player who leaves is forgotten");
        harness.api.release("plugin:Demo");
        assertFalse(harness.living.previews().hides(alex, "shy", npc.uuid()));
    }

    @Test void flagsAreReadWrittenAndUnsetByScope() throws Exception {
        NpcHandle npc = temporary("keeper");
        UUID player = UUID.randomUUID();
        Flag greeted = Flag.relationship("town", "greeted", npc, player);
        assertEquals(Optional.empty(), await(npcs.flag(greeted)).value().orElseThrow());
        assertEquals(Outcome.Status.OK, await(npcs.setFlag(greeted, Optional.of("3"))).status());
        assertEquals(Optional.of("3"), await(npcs.flag(greeted)).value().orElseThrow());
        assertEquals(Optional.empty(), await(npcs.flag(Flag.relationship("town", "greeted", npc, UUID.randomUUID()))).value().orElseThrow(), "another player's flag is apart");
        await(npcs.setFlag(greeted, Optional.empty()));
        assertEquals(Optional.empty(), await(npcs.flag(greeted)).value().orElseThrow());
        assertEquals("flag-player", code(await(npcs.flag(new Flag("town", "greeted", Flag.Scope.PLAYER, Optional.empty(), Optional.empty())))));
        assertEquals("flag-namespace", code(await(npcs.flag(Flag.global("Town!", "x")))));
        assertEquals("flag-value", code(await(npcs.setFlag(Flag.global("town", "x"), Optional.of("null")))));
        assertEquals("behaviors-unavailable", code(await(npcs.startBehavior(npc, "town:greet", Optional.empty(), Map.of()))));
        assertEquals("variable-name", code(await(npcs.startBehavior(npc, "town:greet", Optional.empty(), Map.of("1bad", "x")))));
    }
}
