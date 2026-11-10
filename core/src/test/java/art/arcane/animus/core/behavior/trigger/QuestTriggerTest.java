package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.BehaviorProgram;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Server behaviors hear Animus quests start, move on and complete, filtered by quest id. */
class QuestTriggerTest {
    /** The example on the quests wiki page. */
    private static final String DONE = """
            {
              "schemaVersion": 1,
              "revision": 1,
              "id": "town:herbs-done",
              "server": true,
              "entry": "main",
              "routines": {
                "main": {"type": "action", "capability": "animus:title", "arguments": {"title": "&aThe healer thanks you"}}
              },
              "triggers": [{"id": "done", "event": "quest_completed", "quest": "town:herbs"}]
            }
            """;

    /** The catalog takes {@code revision} off a stored behavior before compiling it. */
    private static BehaviorCompiler.Result compile(String stored) {
        com.google.gson.JsonObject source = com.google.gson.JsonParser.parseString(stored).getAsJsonObject();
        source.remove("revision");
        return BehaviorCompiler.compile(source.toString());
    }

    @Test void questEventsMatchTheirQuestFilter() {
        BehaviorCompiler.Result compiled = compile(DONE);
        BehaviorProgram program = compiled.program().orElseThrow(() -> new AssertionError(compiled.diagnostics().toString()));
        TriggerBinding.Spec spec = program.triggers().getFirst().spec();
        UUID alex = UUID.randomUUID();
        assertTrue(ServerEvent.quest(TriggerKind.QUEST_COMPLETED, alex, "town:herbs", 3, "Herbs").matches(spec));
        assertFalse(ServerEvent.quest(TriggerKind.QUEST_COMPLETED, alex, "town:other", 3, "Other").matches(spec));
        assertFalse(ServerEvent.quest(TriggerKind.QUEST_STARTED, alex, "town:herbs", 1, "Herbs").matches(spec));
        ServerEvent started = ServerEvent.quest(TriggerKind.QUEST_STAGE, alex, "town:herbs", 2, "Herbs");
        assertEquals(2L, started.context().get("stage"));
        assertEquals(alex, started.player().orElseThrow());
    }

    @Test void aBareQuestNameMatchesAnyNamespaceAndQuestTriggersAreServerOnly() {
        String bare = DONE.replace("\"quest\": \"town:herbs\"", "\"quest\": \"herbs\"");
        TriggerBinding.Spec spec = compile(bare).program().orElseThrow().triggers().getFirst().spec();
        assertTrue(ServerEvent.quest(TriggerKind.QUEST_COMPLETED, UUID.randomUUID(), "harbor:herbs", 3, "Herbs").matches(spec));
        assertFalse(compile(DONE.replace("\"server\": true,", "")).diagnostics().isEmpty(), "an NPC behavior cannot listen for quests");
    }
}
