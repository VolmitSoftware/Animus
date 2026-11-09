package art.arcane.animus.core.migration;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Lines that guard or stop a script and do not convert: the routine ends there, and the behavior is held from binding. */
class DenizenGuardsTest {
    private static DenizenConverter.Output convert(String script) throws IOException {
        TreeMap<String, String> scripts = new TreeMap<>();
        scripts.put("a.dsc", script);
        return DenizenConverter.convert(new DenizenConverter.Input(scripts, Optional.empty(), Optional.empty(), new TreeMap<>()));
    }

    private static String text(DenizenConverter.Output output, String id) {
        String text = output.files().get("behaviors/" + id.replace(':', '/') + ".json");
        assertNotNull(text, "no behavior " + id + " in " + output.files().keySet() + "\n" + output.report().get("preserved"));
        return text;
    }

    private static JsonObject main(DenizenConverter.Output output) throws IOException {
        return JsonDocuments.object(text(output, "denizen:task/t")).getAsJsonObject("routines").getAsJsonObject("main");
    }

    private static List<String> types(JsonObject sequence) {
        List<String> found = new ArrayList<>();
        JsonArray steps = sequence.has("steps") ? sequence.getAsJsonArray("steps") : null;
        if (steps == null) { found.add(sequence.get("type").getAsString()); return found; }
        for (JsonElement step : steps) found.add(step.getAsJsonObject().get("type").getAsString());
        return found;
    }

    private static String reasonAt(DenizenConverter.Output output, String source) {
        for (JsonElement entry : output.report().getAsJsonArray("preserved"))
            if (entry.getAsJsonObject().get("source").getAsString().equals(source)) return entry.getAsJsonObject().get("reason").getAsString();
        return fail("nothing preserved at " + source + ": " + output.report().get("preserved"));
    }

    private static List<String> heldLines(DenizenConverter.Output output, String id) {
        JsonObject held = output.report().getAsJsonObject("heldBehaviors");
        assertTrue(held.has(id), id + " is not held: " + held);
        List<String> lines = new ArrayList<>();
        for (JsonElement line : held.getAsJsonObject(id).getAsJsonArray("lines")) lines.add(line.getAsString());
        return lines;
    }

    private static final String TASK = "t:\n  type: task\n  script:\n";

    @Test void anUnconvertedGuardEndsTheRoutineInsteadOfFallingThrough() throws IOException {
        DenizenConverter.Output output = convert(TASK + """
                  - if <player.money> < 10:
                    - narrate "Already claimed"
                    - stop
                  - execute as_server "give <player.name> diamond 64"
                """);
        assertEquals(List.of("return"), types(main(output)));
        assertFalse(text(output, "denizen:task/t").contains("animus:command"));
        assertTrue(reasonAt(output, "a.dsc:7").startsWith("never runs: "), reasonAt(output, "a.dsc:7"));
        assertEquals(List.of("a.dsc:4"), heldLines(output, "denizen:task/t"));
        assertEquals("held", output.report().getAsJsonObject("behaviorStatus").get("denizen:task/t").getAsString());
    }

    @Test void anUnconvertedElseIfChainEndsTheRoutineWhenAnyBranchStops() throws IOException {
        DenizenConverter.Output output = convert(TASK + """
                  - if <player.has_flag[a]>:
                    - narrate "a"
                  - else if <player.money> < 10:
                    - stop
                  - else:
                    - narrate "c"
                  - flag player rewarded
                """);
        assertEquals(List.of("return"), types(main(output)));
        assertTrue(reasonAt(output, "a.dsc:10").startsWith("never runs: "));
    }

    @Test void anUnconvertedBlockThatCannotStopIsSkippedAndTheRestRuns() throws IOException {
        DenizenConverter.Output output = convert(TASK + """
                  - if <player.money> < 10:
                    - narrate "Poor"
                  - narrate "Hello"
                """);
        assertEquals("action", main(output).get("type").getAsString());
        assertEquals("Hello", main(output).getAsJsonObject("arguments").get("text").getAsString());
        assertEquals(List.of("a.dsc:4"), heldLines(output, "denizen:task/t"));
    }

    @Test void loopsWithUnconvertedConditionsEndTheRoutineWhenTheBodyStops() throws IOException {
        DenizenConverter.Output stopping = convert(TASK + """
                  - while <player.money> < 10:
                    - stop
                  - narrate "After"
                """);
        assertEquals(List.of("return"), types(main(stopping)));
        DenizenConverter.Output contained = convert(TASK + """
                  - foreach <player.money> as:x:
                    - foreach stop
                  - narrate "After"
                """);
        assertEquals("action", main(contained).get("type").getAsString());
        assertEquals(List.of("a.dsc:4"), heldLines(contained, "denizen:task/t"));
    }

    @Test void unconvertedRatelimitQueueAndInjectEndTheRoutine() throws IOException {
        for (String line : List.of("ratelimit <npc> 10s", "queue clear", "inject missing_task")) {
            DenizenConverter.Output output = convert(TASK + "  - " + line + "\n  - narrate \"After\"\n");
            assertEquals(List.of("return"), types(main(output)), line);
            assertEquals(List.of("a.dsc:4"), heldLines(output, "denizen:task/t"), line);
        }
    }

    @Test void injectingATaskThatStopsIsKeptBecauseAnimusWouldEndOnlyTheTask() throws IOException {
        DenizenConverter.Output output = convert("""
                check:
                  type: task
                  script:
                  - if !<player.has_flag[vip]>:
                    - stop
                t:
                  type: task
                  script:
                  - inject check
                  - execute as_server "give <player.name> diamond 1"
                """);
        assertEquals(List.of("return"), types(main(output)));
        assertTrue(reasonAt(output, "a.dsc:9").contains("inject"), reasonAt(output, "a.dsc:9"));
        DenizenConverter.Output run = convert("""
                check:
                  type: task
                  script:
                  - if !<player.has_flag[vip]>:
                    - stop
                t:
                  type: task
                  script:
                  - run check
                  - narrate "After"
                """);
        assertEquals(List.of("call", "action"), types(main(run)));
        assertFalse(run.report().getAsJsonObject("heldBehaviors").has("denizen:task/t"));
    }

    @Test void heldServerBehaviorsAreNotEnabledOnAccept() throws IOException {
        DenizenConverter.Output output = convert("""
                lobby:
                  type: world
                  events:
                    on player joins:
                    - if <player.money> > 5:
                      - stop
                    - execute as_server "give <player.name> diamond 1"
                calm:
                  type: world
                  events:
                    on player joins:
                    - narrate "Hi"
                """);
        JsonArray enabled = output.report().getAsJsonArray("serverBehaviors");
        assertFalse(enabled.toString().contains("denizen:world/lobby"), enabled.toString());
        assertTrue(enabled.toString().contains("denizen:world/calm"), enabled.toString());
        JsonObject held = output.report().getAsJsonObject("heldBehaviors").getAsJsonObject("denizen:world/lobby");
        assertTrue(held.get("server").getAsBoolean());
    }

    @Test void aCommandPermissionThatCannotBeUsedHoldsTheCommand() throws IOException {
        DenizenConverter.Output output = convert("""
                c:
                  type: command
                  name: reward
                  permission: %s
                  script:
                  - execute as_server "give <player.name> diamond 1"
                """.formatted("x".repeat(300)));
        assertEquals(List.of("a.dsc:4"), heldLines(output, "denizen:command/c"));
        assertEquals(0, output.report().getAsJsonArray("serverBehaviors").size());
    }

    @Test void thePreviewCountsHeldBehaviors() throws IOException {
        DenizenConverter.Output output = convert(TASK + "  - queue clear\n");
        assertEquals(1, new DenizenImporter.Preview(output.id(), java.nio.file.Path.of("."), output.report()).held());
    }

    @Test void executeAsOpRunsAsThePlayerAndIsReported() throws IOException {
        DenizenConverter.Output output = convert(TASK + "  - execute as_op \"warp vip\"\n");
        JsonObject command = main(output);
        assertEquals("animus:command", command.get("capability").getAsString());
        assertEquals("player", command.getAsJsonObject("arguments").get("as").getAsString());
        assertTrue(output.report().getAsJsonArray("issues").toString().contains("a.dsc:4: execute as_op"), output.report().get("issues").toString());
    }
}
