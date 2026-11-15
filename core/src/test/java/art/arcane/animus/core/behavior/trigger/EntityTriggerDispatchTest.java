package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityTriggerDispatchTest {
    private static final String BEHAVIOR = "town:sense";
    private final UUID npc = UUID.randomUUID(), other = UUID.randomUUID(), sheep = UUID.randomUUID(), cow = UUID.randomUUID(), player = UUID.randomUUID();
    private final AtomicReference<Map<String, NpcDefinition>> definitions = new AtomicReference<>(Map.of());
    private final AtomicReference<BehaviorCatalog.Snapshot> catalog = new AtomicReference<>(new BehaviorCatalog.Snapshot(0, Map.of(), Map.of(), List.of()));
    private final List<TriggerDispatcher.Request> started = new ArrayList<>();
    private TriggerDispatcher dispatcher;

    private static TriggerSettings settings(int cooldown, int maximumEntityRadius) {
        return new TriggerSettings(true, 64, 32, 8, 64, 8, 8, 100, 32, 8, 8, cooldown, 0, 4096, 1024, 1, 32, 5, 16, 32, 64, 0, 5, 16, 32, maximumEntityRadius, 64, 256);
    }
    private void setUp(TriggerSettings settings, String triggers) throws Exception {
        BehaviorProgram program = BehaviorCompiler.compile("{\"schemaVersion\":1,\"id\":\"" + BEHAVIOR + "\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[]}},\"triggers\":" + triggers + "}").program().orElseThrow();
        BehaviorCatalog.Accepted accepted = new BehaviorCatalog.Accepted(1, String.format("%064x", 1), 100, program, Set.of());
        catalog.set(new BehaviorCatalog.Snapshot(1, Map.of(), Map.of(BEHAVIOR, new BehaviorCatalog.Resolution(BEHAVIOR, true, Optional.of(accepted), List.of())), List.of()));
        definitions.set(Map.of("listener", definition("listener", npc), "speaker", definition("speaker", other)));
        dispatcher = new TriggerDispatcher(settings, definitions::get, catalog::get);
        dispatcher.connect();
        dispatcher.activated(npc);
        pump();
    }
    private static NpcDefinition definition(String id, UUID uuid) throws Exception {
        String behaviors = id.equals("listener") ? "[\"" + BEHAVIOR + "\"]" : "[]";
        return NpcDefinition.parse("{\"schemaVersion\":2,\"revision\":1,\"uuid\":\"" + uuid + "\",\"id\":\"" + id + "\",\"group\":\"test\",\"name\":\"Test\",\"type\":\"minecraft:player\",\"spawned\":true,\"behaviors\":"
                + behaviors + ",\"position\":{\"world\":\"world\",\"x\":0,\"y\":61,\"z\":0,\"yaw\":0,\"pitch\":0}}");
    }
    private void pump() { dispatcher.pump((TriggerDispatcher.Request request) -> { started.add(request); return Outcome.ok(UUID.randomUUID()); }); }
    private void pump(int count) { for (int index = 0; index < count; index++) pump(); }
    private TriggerEvent.Entity near(UUID entity) {
        return new TriggerEvent.Entity(npc, TriggerKind.ENTITY_PROXIMITY, BEHAVIOR + "#near", Optional.of(new TriggerEvent.Subject(entity, "minecraft:sheep", "", Set.of())),
                Optional.empty(), Map.of("entity", entity.toString(), "phase", "enter"));
    }

    @Test void thePlanListsTheSensedWatchesOfPresentedNpcsAndHowFarTheyLook() throws Exception {
        setUp(settings(0, 16), """
                [{"id":"near","event":"entity_proximity","phase":"enter","radius":6},{"id":"shot","event":"hit_by_projectile"},
                 {"id":"ear","event":"npc_speech","radius":12},{"id":"click","event":"interaction"}]""");
        TriggerDispatcher.EntityPlan plan = dispatcher.entityPlan();
        assertEquals(List.of(BEHAVIOR + "#near", BEHAVIOR + "#shot", BEHAVIOR + "#ear"), plan.watches(npc).stream().map(EntitySensor.Watch::key).toList());
        assertTrue(plan.scans(npc));
        assertEquals(6.0, plan.radius().get(npc), "speech is heard, not looked for");
        assertTrue(plan.wants(npc, TriggerKind.HIT_BY_PROJECTILE));
        assertEquals(Set.of(TriggerKind.ENTITY_PROXIMITY, TriggerKind.HIT_BY_PROJECTILE, TriggerKind.NPC_SPEECH), plan.kinds());
        assertFalse(plan.scans(other));
        dispatcher.deactivated(npc);
        pump();
        assertEquals(List.of(), dispatcher.entityPlan().watches(npc));
    }

    @Test void aHitPublishedBeforeTheBlowThatKillsTheNpcStillRuns() throws Exception {
        setUp(settings(0, 16), "[{\"id\":\"shot\",\"event\":\"hit_by_projectile\"},{\"id\":\"dead\",\"event\":\"death\"}]");
        dispatcher.publish(new TriggerEvent.Entity(npc, TriggerKind.HIT_BY_PROJECTILE, BEHAVIOR + "#shot", Optional.of(new TriggerEvent.Subject(player, "minecraft:player", "Alex", Set.of())),
                Optional.of(player), Map.of("projectile", "minecraft:arrow", "stopped", true)));
        dispatcher.publish(new TriggerEvent.Death(npc, Optional.empty()));
        dispatcher.dead(npc);
        pump();
        assertEquals(List.of("shot", "dead"), started.stream().map((TriggerDispatcher.Request request) -> request.binding().trigger().id()).toList(),
                "the arrow struck a living NPC, so its hit runs though the NPC died of it");
        dispatcher.publish(new TriggerEvent.Entity(npc, TriggerKind.HIT_BY_PROJECTILE, BEHAVIOR + "#shot", Optional.empty(), Optional.empty(), Map.of("projectile", "minecraft:arrow", "stopped", false)));
        pump();
        assertEquals(2, started.size(), "a hit on a body that is already dead does not run");
    }

    @Test void aRadiusBeyondTheBudgetStaysUnboundWithADiagnostic() throws Exception {
        setUp(settings(0, 4), "[{\"id\":\"near\",\"event\":\"entity_proximity\",\"phase\":\"enter\",\"radius\":6}]");
        assertEquals(List.of(), dispatcher.entityPlan().watches(npc));
        assertTrue(dispatcher.snapshot().diagnostics().stream().anyMatch((art.arcane.animus.api.Diagnostic problem) -> problem.code().equals("trigger-entity-budget")));
    }

    @Test void eachEntityKeepsItsOwnCooldownAndTheRunReadsItsContext() throws Exception {
        setUp(settings(5, 16), "[{\"id\":\"near\",\"event\":\"entity_proximity\",\"phase\":\"enter\",\"radius\":6}]");
        dispatcher.publish(near(sheep)); pump();
        dispatcher.publish(near(sheep)); dispatcher.publish(near(cow)); pump();
        assertEquals(2, started.size(), "the sheep cools down, the cow does not wait for it");
        assertEquals(1, dispatcher.snapshot().suppressed());
        assertEquals(sheep.toString(), started.getFirst().event().get("entity"));
        pump(5);
        dispatcher.publish(near(sheep)); pump();
        assertEquals(3, started.size());
    }

    @Test void entityCooldownTicksReplacesTheDefaultAndCooldownTicksHoldsTheTrigger() throws Exception {
        setUp(settings(100, 16), "[{\"id\":\"near\",\"event\":\"entity_proximity\",\"phase\":\"enter\",\"radius\":6,\"entityCooldownTicks\":0}]");
        dispatcher.publish(near(sheep)); pump(); dispatcher.publish(near(sheep)); pump();
        assertEquals(2, started.size());
        started.clear();
        setUp(settings(0, 16), "[{\"id\":\"near\",\"event\":\"entity_proximity\",\"phase\":\"enter\",\"radius\":6,\"cooldownTicks\":50}]");
        dispatcher.publish(near(sheep)); pump(); dispatcher.publish(near(cow)); pump();
        assertEquals(1, started.size());
    }

    @Test void aPlayerInTheEventIsTheRunsPlayer() throws Exception {
        setUp(settings(0, 16), "[{\"id\":\"near\",\"event\":\"entity_proximity\",\"phase\":\"enter\",\"radius\":6}]");
        dispatcher.publish(new TriggerEvent.Entity(npc, TriggerKind.ENTITY_PROXIMITY, BEHAVIOR + "#near", Optional.of(new TriggerEvent.Subject(player, "minecraft:player", "Alex", Set.of())),
                Optional.of(player), Map.of("entity", player.toString())));
        pump();
        assertEquals(Optional.of(player), started.getFirst().player());
    }

    @Test void speechReachesListenersWithinTheirRadius() throws Exception {
        setUp(settings(0, 16), "[{\"id\":\"ear\",\"event\":\"npc_speech\",\"radius\":8,\"npc\":\"speak*\"}]");
        Map<UUID, WorldPosition> positions = new HashMap<>(Map.of(npc, new WorldPosition("world", new Vec3(5, 61, 0))));
        assertEquals(1, dispatcher.spoke(other, "speaker", new WorldPosition("world", new Vec3(0, 61, 0)), "Halt!", (UUID id) -> Optional.ofNullable(positions.get(id))));
        pump();
        assertEquals(1, started.size());
        assertEquals("Halt!", started.getFirst().event().get("text"));
        assertEquals("speaker", started.getFirst().event().get("npc"));
        assertEquals(0, dispatcher.spoke(other, "speaker", new WorldPosition("world", new Vec3(20, 61, 0)), "Halt!", (UUID id) -> Optional.ofNullable(positions.get(id))));
        assertEquals(0, dispatcher.spoke(npc, "listener", new WorldPosition("world", new Vec3(5, 61, 0)), "Halt!", (UUID id) -> Optional.ofNullable(positions.get(id))));
    }
}
