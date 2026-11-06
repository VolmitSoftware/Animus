package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.BehaviorFacts;
import art.arcane.animus.core.behavior.BehaviorFlags;
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.ServerNpc;
import art.arcane.animus.core.behavior.builtin.MemoryBehaviorState;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.catalog.ServerBehaviorList;
import art.arcane.animus.core.platform.BukkitWorlds;
import art.arcane.animus.core.platform.ModWorlds;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerWorldEventTest {
    private static final String ROUTINES = "\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[]}}";
    private static final String BEHAVIOR = "denizen:world/events";
    private final UUID alex = UUID.randomUUID();
    private final AtomicReference<BehaviorCatalog.Snapshot> catalog = new AtomicReference<>(new BehaviorCatalog.Snapshot(0, Map.of(), Map.of(), List.of()));
    private final AtomicReference<ServerBehaviorList> enabled = new AtomicReference<>(ServerBehaviorList.UNLOADED);
    private final List<ServerTriggerDispatcher.Request> started = new ArrayList<>();
    private ServerTriggerDispatcher dispatcher;

    private void setup(ServerTriggerSettings settings, String triggers) {
        dispatcher = new ServerTriggerDispatcher(settings, catalog::get, enabled::get);
        dispatcher.connect();
        String source = "{\"schemaVersion\":1,\"id\":\"" + BEHAVIOR + "\",\"server\":true,\"entry\":\"main\"," + ROUTINES + ",\"triggers\":" + triggers + "}";
        BehaviorCompiler.Result compiled = BehaviorCompiler.compile(source);
        BehaviorProgram program = compiled.program().orElseThrow(() -> new AssertionError(compiled.diagnostics().toString()));
        Map<String, BehaviorCatalog.Resolution> resolutions = new LinkedHashMap<>();
        resolutions.put(BEHAVIOR, new BehaviorCatalog.Resolution(BEHAVIOR, true, Optional.of(new BehaviorCatalog.Accepted(1, String.format("%064x", 1), 100, program, Set.of())), List.of()));
        catalog.set(new BehaviorCatalog.Snapshot(1, Map.of(), Map.copyOf(resolutions), List.of()));
        enabled.set(new ServerBehaviorList(1, List.of(BEHAVIOR), List.of()));
        pump();
    }
    private void pump() { dispatcher.pump(request -> { started.add(request); return Outcome.ok(UUID.randomUUID()); }); }
    private List<String> fired() { return started.stream().map(request -> request.binding().trigger().id()).toList(); }

    @Test void triggersCompileFiltersCancelAndAreasAndRejectMisplacedFields() {
        String base = "{\"schemaVersion\":1,\"id\":\"denizen:world/x\",\"server\":true,\"entry\":\"main\"," + ROUTINES + ",\"triggers\":";
        assertTrue(BehaviorCompiler.compile(base + "[{\"id\":\"a\",\"event\":\"player_block_break\",\"block\":\"*_log\",\"cancel\":\"has_flag('player', 'protected')\"},"
                + "{\"id\":\"b\",\"event\":\"player_area\",\"phase\":\"enter\",\"area\":\"camp\",\"world\":\"world\",\"cuboid\":[10,60,10,0,70,0]},"
                + "{\"id\":\"c\",\"event\":\"player_area\",\"phase\":\"leave\",\"area\":\"lake\",\"world\":\"world\",\"ellipsoid\":[0,64,0,5,3,5]}]}").program().isPresent());
        BehaviorCompiler.Result closed = BehaviorCompiler.compile(base + "[{\"id\":\"a\",\"event\":\"player_inventory_close\",\"cancel\":\"true\"}]}");
        assertTrue(closed.program().isEmpty(), "closing an inventory cannot be cancelled");
        BehaviorCompiler.Result shapes = BehaviorCompiler.compile(base + "[{\"id\":\"a\",\"event\":\"player_area\",\"phase\":\"enter\",\"area\":\"camp\",\"world\":\"world\"}]}");
        assertTrue(shapes.program().isEmpty(), "an area needs a cuboid or an ellipsoid");
        BehaviorCompiler.Result click = BehaviorCompiler.compile(base + "[{\"id\":\"a\",\"event\":\"player_block_click\",\"click\":\"middle\"}]}");
        assertTrue(click.program().isEmpty(), "a block click is left or right");
    }

    @Test void filteredEventsTakeNoQueueSlotUnlessATriggerMatches() {
        setup(ServerTriggerSettings.DEFAULT, "[{\"id\":\"logs\",\"event\":\"player_block_break\",\"block\":\"*_log\"},{\"id\":\"bread\",\"event\":\"player_item_consume\",\"item\":\"minecraft:bread\"}]");
        assertEquals(Optional.of(false), dispatcher.publish(ServerEvent.block(TriggerKind.PLAYER_BLOCK_BREAK, alex, "minecraft:stone", "world", 1, 2, 3)).value());
        assertEquals(Optional.of(true), dispatcher.publish(ServerEvent.block(TriggerKind.PLAYER_BLOCK_BREAK, alex, "minecraft:oak_log", "world", 1, 2, 3)).value());
        assertEquals(Optional.of(false), dispatcher.publish(ServerEvent.item(TriggerKind.PLAYER_ITEM_CONSUME, alex, "minecraft:apple", 1)).value());
        assertEquals(Optional.of(true), dispatcher.publish(ServerEvent.item(TriggerKind.PLAYER_ITEM_CONSUME, alex, "minecraft:bread", 1)).value());
        pump();
        assertEquals(List.of("logs", "bread"), fired());
        Map<String, Object> event = started.getFirst().event();
        assertEquals("minecraft:oak_log", event.get("block"));
        assertEquals("1.00,2.00,3.00,world", event.get("location"));
        assertEquals(3L, event.get("z"));
    }

    @Test void cancelConditionsDecideAsTheEventHappensAndTheRunStillStarts() {
        setup(ServerTriggerSettings.DEFAULT, "[{\"id\":\"guard\",\"event\":\"player_block_break\",\"cancel\":\"!has_flag('player', 'builder') && context.block != 'minecraft:dirt'\"},"
                + "{\"id\":\"broken\",\"event\":\"player_damaged\",\"cancel\":\"num(context.hand) > 1\"}]");
        ServerEvent stone = ServerEvent.block(TriggerKind.PLAYER_BLOCK_BREAK, alex, "minecraft:stone", "world", 0, 0, 0);
        assertFalse(dispatcher.decide(stone), "nothing connected the state yet");
        MemoryBehaviorState state = new MemoryBehaviorState();
        dispatcher.consumption(state, BehaviorFacts.NONE);
        assertTrue(dispatcher.decide(stone));
        assertFalse(dispatcher.decide(ServerEvent.block(TriggerKind.PLAYER_BLOCK_BREAK, alex, "minecraft:dirt", "world", 0, 0, 0)));
        state.write(BehaviorFlags.subject(BehaviorFlags.FLAGS, BEHAVIOR, ServerNpc.HANDLE.uuid(), Optional.of(alex)),
                Map.of("builder", new BehaviorProgram.Value(BehaviorProgram.Type.BOOLEAN, true)), Map.of("builder", BehaviorFlags.declaration("player")));
        assertFalse(dispatcher.decide(stone), "a builder may break it");
        assertFalse(dispatcher.decide(ServerEvent.damaged(alex, "fall", 4, "")), "a failing condition does not cancel");
        pump();
        assertEquals(List.of("guard", "guard", "guard", "guard", "broken"), fired());
    }

    @Test void areasPublishEnterAndLeaveWithTheirCause() {
        setup(ServerTriggerSettings.DEFAULT, "[{\"id\":\"in\",\"event\":\"player_area\",\"phase\":\"enter\",\"area\":\"camp\",\"world\":\"world\",\"cuboid\":[0,0,0,9,9,9]},"
                + "{\"id\":\"out\",\"event\":\"player_area\",\"phase\":\"leave\",\"area\":\"camp\",\"world\":\"world\",\"cuboid\":[9,9,9,0,0,0]},"
                + "{\"id\":\"pond\",\"event\":\"player_area\",\"phase\":\"enter\",\"area\":\"pond\",\"world\":\"world\",\"ellipsoid\":[50,64,50,4,2,4]}]");
        dispatcher.position(alex, "world", 5, 5, 5);
        dispatcher.position(alex, "world", 9.9, 5, 5);
        dispatcher.position(alex, "world", 10.1, 5, 5);
        dispatcher.position(alex, "world_nether", 5, 5, 5);
        dispatcher.position(alex, "world", 53, 64, 50);
        dispatcher.position(alex, "world", 5, 5, 5);
        dispatcher.departed(alex);
        pump();
        assertEquals(List.of("in", "out", "pond", "in", "out"), fired());
        assertEquals(List.of("join", "move", "move", "move", "quit"), started.stream().map(request -> request.event().get("cause")).toList());
        assertEquals("camp", started.getFirst().event().get("area"));
    }

    @Test void areasPastTheMaximumOrWithAnotherShapeAreNotTracked() {
        setup(new ServerTriggerSettings(true, 512, 64, 16, 64, 8, 32, 0, 20, 64, 1),
                "[{\"id\":\"a\",\"event\":\"player_area\",\"phase\":\"enter\",\"area\":\"camp\",\"world\":\"world\",\"cuboid\":[0,0,0,9,9,9]},"
                + "{\"id\":\"b\",\"event\":\"player_area\",\"phase\":\"leave\",\"area\":\"camp\",\"world\":\"world\",\"cuboid\":[0,0,0,5,5,5]},"
                + "{\"id\":\"c\",\"event\":\"player_area\",\"phase\":\"enter\",\"area\":\"town\",\"world\":\"world\",\"cuboid\":[0,0,0,1,1,1]}]");
        List<String> codes = dispatcher.snapshot().diagnostics().stream().map(Diagnostic::code).toList();
        assertTrue(codes.contains("server-area-conflict"), codes.toString());
        assertTrue(codes.contains("server-area-capacity"), codes.toString());
        assertEquals(1, dispatcher.snapshot().bindings());
    }

    private static final Set<String> DIMENSIONS = Set.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");
    private void modWorlds() { dispatcher.worlds((String reference) -> ModWorlds.resolve(reference, "world", DIMENSIONS)); pump(); }

    @Test void bukkitWorldNamesOfAreasMatchModDimensionKeys() {
        setup(ServerTriggerSettings.DEFAULT, "[{\"id\":\"in\",\"event\":\"player_area\",\"phase\":\"enter\",\"area\":\"camp\",\"world\":\"world\",\"cuboid\":[0,0,0,9,9,9]},"
                + "{\"id\":\"hot\",\"event\":\"player_area\",\"phase\":\"enter\",\"area\":\"camp\",\"world\":\"world_nether\",\"cuboid\":[0,0,0,9,9,9]}]");
        modWorlds();
        dispatcher.position(alex, "minecraft:overworld", 5, 5, 5);
        dispatcher.position(alex, "minecraft:the_nether", 5, 5, 5);
        pump();
        assertEquals(List.of("in", "hot"), fired());
        assertEquals("minecraft:overworld", started.getFirst().event().get("world"));
    }

    @Test void bukkitWorldNamesOfWorldChangesMatchModDimensionKeys() {
        setup(ServerTriggerSettings.DEFAULT, "[{\"id\":\"down\",\"event\":\"player_world_change\",\"from\":\"world\",\"to\":\"world_nether\"},"
                + "{\"id\":\"end\",\"event\":\"player_world_change\",\"to\":\"minecraft:the_end\"}]");
        modWorlds();
        dispatcher.publish(ServerEvent.worldChange(alex, "minecraft:overworld", "minecraft:the_nether"));
        dispatcher.publish(ServerEvent.worldChange(alex, "minecraft:the_nether", "minecraft:overworld"));
        dispatcher.publish(ServerEvent.worldChange(alex, "minecraft:overworld", "minecraft:the_end"));
        pump();
        assertEquals(List.of("down", "end"), fired());
    }

    @Test void dimensionKeysOfAreasMatchBukkitWorldNames() {
        setup(ServerTriggerSettings.DEFAULT, "[{\"id\":\"in\",\"event\":\"player_area\",\"phase\":\"enter\",\"area\":\"camp\",\"world\":\"minecraft:overworld\",\"cuboid\":[0,0,0,9,9,9]}]");
        List<BukkitWorlds.Loaded> loaded = List.of(new BukkitWorlds.Loaded("world", UUID.randomUUID(), "minecraft:overworld"));
        dispatcher.worlds((String reference) -> BukkitWorlds.resolve(reference, loaded));
        pump();
        dispatcher.position(alex, "world", 5, 5, 5);
        pump();
        assertEquals(List.of("in"), fired());
    }

    @Test void unknownWorldsAreReportedWhenTheyBind() {
        setup(ServerTriggerSettings.DEFAULT, "[{\"id\":\"in\",\"event\":\"player_area\",\"phase\":\"enter\",\"area\":\"camp\",\"world\":\"skylands\",\"cuboid\":[0,0,0,9,9,9]},"
                + "{\"id\":\"go\",\"event\":\"player_world_change\",\"from\":\"world\",\"to\":\"skylands\"}]");
        modWorlds();
        List<Diagnostic> unknown = dispatcher.snapshot().diagnostics().stream().filter((Diagnostic diagnostic) -> diagnostic.code().equals("server-world-unknown")).toList();
        assertEquals(2, unknown.size(), dispatcher.snapshot().diagnostics().toString());
        assertTrue(unknown.getFirst().message().contains("skylands"), unknown.toString());
        assertEquals(Diagnostic.Severity.WARNING, unknown.getFirst().severity());
    }

    @Test void schemaSettingsAcceptMaximumAreas() {
        assertThrows(IllegalArgumentException.class, () -> new ServerTriggerSettings(true, 512, 64, 16, 64, 8, 32, 0, 20, 64, 5000));
    }
}
