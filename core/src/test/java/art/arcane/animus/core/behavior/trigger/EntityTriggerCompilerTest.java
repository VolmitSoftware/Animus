package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.core.behavior.BehaviorCompiler;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityTriggerCompilerTest {
    private static BehaviorCompiler.Result compile(String triggers, boolean server) {
        return BehaviorCompiler.compile("{\"schemaVersion\":1,\"id\":\"town:sense\",\"entry\":\"main\"," + (server ? "\"server\":true," : "")
                + "\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[]}},\"triggers\":" + triggers + "}");
    }
    private static TriggerBinding.EntityEvent only(String trigger) {
        BehaviorCompiler.Result result = compile("[" + trigger + "]", false);
        assertEquals(List.of(), result.diagnostics());
        return (TriggerBinding.EntityEvent) result.program().orElseThrow().triggers().getFirst().spec();
    }
    private static String problem(String trigger) {
        BehaviorCompiler.Result result = compile("[" + trigger + "]", false);
        assertTrue(result.program().isEmpty(), trigger);
        return result.diagnostics().getFirst().field();
    }

    @Test void everySensedEventCompilesWithItsOwnFieldsAndTheEntityFilters() {
        TriggerBinding.EntityEvent near = only("""
                {"id":"near","event":"entity_proximity","phase":"leave","radius":5.5,"entity":"zombie,skeleton","named":"Bo*","tag":"pet","players":false,"entityCooldownTicks":40}""");
        assertEquals(Optional.of(TriggerBinding.Phase.LEAVE), near.phase());
        assertEquals(5.5, near.radius());
        assertEquals(new TriggerBinding.EntityFilter(Optional.of("zombie,skeleton"), Optional.of("Bo*"), Optional.of("pet"), Optional.of(false), Optional.empty(), OptionalInt.of(40)), near.filter());
        assertEquals(TriggerBinding.Contact.TOP, only("{\"id\":\"c\",\"event\":\"entity_collide\",\"contact\":\"top\"}").contact());
        assertEquals(Map.of("projectile", "arrow,trident"), only("{\"id\":\"p\",\"event\":\"hit_by_projectile\",\"projectile\":\"arrow,trident\",\"permission\":\"town.archer\"}").patterns());
        TriggerBinding.EntityEvent item = only("{\"id\":\"i\",\"event\":\"item_dropped\",\"radius\":3,\"item\":\"minecraft:emerald\",\"thrown\":true}");
        assertTrue(item.thrown());
        assertEquals(Map.of("item", "minecraft:emerald"), item.patterns());
        assertEquals(TriggerKind.VEHICLE_BUMP, only("{\"id\":\"v\",\"event\":\"vehicle_bump\",\"entity\":\"*minecart\",\"players\":true}").kind());
        assertEquals(TriggerKind.LEASH_TIE, only("{\"id\":\"t\",\"event\":\"leash_tie\",\"entity\":\"horse\"}").kind());
        assertEquals(2.0, only("{\"id\":\"l\",\"event\":\"leash_pass\",\"radius\":2}").radius());
        assertEquals(Map.of("npc", "guard*"), only("{\"id\":\"n\",\"event\":\"npc_proximity\",\"phase\":\"enter\",\"radius\":6,\"npc\":\"guard*\"}").patterns());
        assertEquals(Map.of("npc", "crier", "pattern", "*news*"), only("{\"id\":\"s\",\"event\":\"npc_speech\",\"radius\":20,\"npc\":\"crier\",\"pattern\":\"*news*\"}").patterns());
    }

    @Test void invalidSensedTriggersNameTheField() {
        assertEquals("$.triggers[0].radius", problem("{\"id\":\"near\",\"event\":\"entity_proximity\",\"phase\":\"enter\"}"));
        assertEquals("$.triggers[0].phase", problem("{\"id\":\"near\",\"event\":\"entity_proximity\",\"radius\":4}"));
        assertEquals("$.triggers[0].permission", problem("{\"id\":\"near\",\"event\":\"entity_collide\",\"players\":false,\"permission\":\"town.vip\"}"));
        assertEquals("$.triggers[0].tag", problem("{\"id\":\"near\",\"event\":\"entity_collide\",\"tag\":\"two words\"}"));
        assertEquals("$.triggers[0].contact", problem("{\"id\":\"near\",\"event\":\"entity_collide\",\"contact\":\"under\"}"));
        assertEquals("$.triggers[0].entity", problem("{\"id\":\"near\",\"event\":\"entity_collide\",\"entity\":\"Zombie\"}"));
        assertEquals("$.triggers[0].entity", problem("{\"id\":\"near\",\"event\":\"entity_collide\",\"entity\":\"a,b,c,d,e,f,g,h,i\"}"), "at most eight alternatives");
        assertEquals("$.triggers[0].radius", problem("{\"id\":\"say\",\"event\":\"npc_speech\"}"));
        assertEquals("$.triggers[0].named", problem("{\"id\":\"near\",\"event\":\"npc_proximity\",\"phase\":\"enter\",\"radius\":4,\"named\":\"Bob\"}"), "NPC events take no entity filters");
        assertEquals("$.triggers[0].thrown", problem("{\"id\":\"near\",\"event\":\"entity_collide\",\"thrown\":true}"));
    }

    @Test void aLookedForRadiusStopsAtTheHighestEntityRadiusTheServerAllows() {
        for (String trigger : List.of("{\"id\":\"near\",\"event\":\"entity_proximity\",\"phase\":\"enter\",\"radius\":33}", "{\"id\":\"gift\",\"event\":\"item_dropped\",\"radius\":40}",
                "{\"id\":\"led\",\"event\":\"leash_pass\",\"radius\":32.5}", "{\"id\":\"guard\",\"event\":\"npc_proximity\",\"phase\":\"enter\",\"radius\":64}")) {
            BehaviorCompiler.Result result = compile("[" + trigger + "]", false);
            assertTrue(result.program().isEmpty(), trigger);
            assertEquals("$.triggers[0].radius", result.diagnostics().getFirst().field(), trigger);
            assertEquals("expected a radius above 0 and at most 32 blocks; the server's behaviors.triggers.maximumEntityRadius (16 by default) caps it",
                    result.diagnostics().getFirst().message(), trigger);
        }
        assertEquals(32.0, only("{\"id\":\"near\",\"event\":\"entity_proximity\",\"phase\":\"enter\",\"radius\":32}").radius());
        assertEquals(64.0, only("{\"id\":\"ear\",\"event\":\"npc_speech\",\"radius\":64}").radius(), "speech is heard, not looked for, so the setting does not cap it");
        assertEquals(List.of(), compile("[{\"id\":\"walk\",\"event\":\"proximity\",\"phase\":\"enter\",\"radius\":64}]", false).diagnostics(), "plain proximity keeps its own limit");
    }

    @Test void serverBehaviorsCannotSenseAroundAnNpc() {
        BehaviorCompiler.Result result = compile("[{\"id\":\"near\",\"event\":\"entity_collide\"}]", true);
        assertEquals("$.triggers[0].event", result.diagnostics().getFirst().field());
    }

    @Test void commaSeparatedFiltersMatchAnyAlternativeInServerEventsToo() {
        assertTrue(ServerEvent.key("zombie,skeleton", "minecraft:skeleton"));
        assertFalse(ServerEvent.key("zombie,skeleton", "minecraft:creeper"));
        assertTrue(ServerEvent.key("minecraft:oak_log,*_planks", "minecraft:birch_planks"));
        BehaviorCompiler.Result result = compile("[{\"id\":\"cut\",\"event\":\"player_block_break\",\"block\":\"oak_log,birch_log\"}]", true);
        assertEquals(List.of(), result.diagnostics());
    }

    @Test void aSensedTriggerThatMayFireWithoutAPlayerWarnsABehaviorWithPlayerVariables() {
        String variables = "\"variables\":{\"seen\":{\"type\":\"boolean\",\"initial\":false,\"scope\":\"player\",\"lifetime\":\"persistent\"}},";
        java.util.function.Function<String, List<String>> warned = (String trigger) -> BehaviorCompiler.compile("{\"schemaVersion\":1,\"id\":\"town:sense\",\"entry\":\"main\"," + variables
                + "\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[]}},\"triggers\":[" + trigger + "]}").diagnostics().stream().map(art.arcane.animus.api.Diagnostic::code).toList();
        assertEquals(List.of("behavior-trigger-needs-player"), warned.apply("{\"id\":\"near\",\"event\":\"entity_collide\"}"));
        assertEquals(List.of("behavior-trigger-needs-player"), warned.apply("{\"id\":\"ear\",\"event\":\"npc_speech\",\"radius\":4}"));
        assertEquals(List.of(), warned.apply("{\"id\":\"near\",\"event\":\"entity_collide\",\"players\":true}"));
        assertEquals(List.of(), warned.apply("{\"id\":\"near\",\"event\":\"hit_by_projectile\",\"permission\":\"town.archer\"}"));
        assertEquals(List.of(), warned.apply("{\"id\":\"tie\",\"event\":\"leash_tie\"}"));
    }

    @Test void sensedEventsCarryAPlayerOnlyWhenOneIsInvolved() {
        for (TriggerKind kind : TriggerKind.values()) if (kind.sensed()) assertEquals(kind != TriggerKind.NPC_PROXIMITY && kind != TriggerKind.NPC_SPEECH, kind.carriesPlayer(), kind.jsonName());
    }
}
