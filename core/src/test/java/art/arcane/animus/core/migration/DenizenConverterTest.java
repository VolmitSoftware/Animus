package art.arcane.animus.core.migration;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The Denizen reader and converter on small scripts; the shared golden fixtures cover the rest. */
class DenizenConverterTest {
    private static JsonObject behavior(String script, String id) throws IOException {
        TreeMap<String, String> scripts = new TreeMap<>();
        scripts.put("a.dsc", script);
        DenizenConverter.Output output = DenizenConverter.convert(new DenizenConverter.Input(scripts, Optional.empty(), Optional.empty(), new TreeMap<>()));
        String text = output.files().get("behaviors/" + id.replace(':', '/') + ".json");
        assertNotNull(text, "no behavior " + id + " in " + output.files().keySet());
        return art.arcane.animus.core.storage.JsonDocuments.object(text);
    }

    @Test void commandsKeepQuotesPrefixesAndTagsTogether() {
        DenizenArgs.Command command = DenizenArgs.command("~narrate \"Hi <player.name>\" targets:<player> format:\"a b\" <element[x y].length>");
        assertEquals("narrate", command.name());
        assertEquals(List.of("Hi <player.name>", "<player>", "a b", "<element[x y].length>"), command.args().stream().map(DenizenArgs.Arg::value).toList());
        assertEquals("targets", command.args().get(1).prefix());
        assertTrue(command.args().getFirst().quoted());
    }

    @Test void durationsRoundHalfUpToTicks() {
        assertEquals(40, DenizenArgs.ticks("2s").orElseThrow());
        assertEquals(1, DenizenArgs.ticks("0.025s").orElseThrow());
        assertEquals(60, DenizenArgs.ticks("3").orElseThrow());
        assertTrue(DenizenArgs.ticks("<[time]>").isEmpty());
    }

    @Test void flagFilesFlattenSubFlagsAndMarkExpiringOnes() {
        DenizenFlags.Values values = DenizenFlags.file("a:true\n\\exb:map@[__value=5;__expiration=time@x]\nc:map@[__value=map@[d=map@[__value=e&scf]]]\nk\\cox:1\n");
        assertEquals(List.of("a", "b", "c.d", "k:x"), List.copyOf(values.flags().keySet()));
        assertTrue(values.flags().get("b").expires());
        assertEquals("e;f", values.flags().get("c.d").value());
    }

    @Test void stopMovesTheRestIntoTheBranchesThatContinue() throws IOException {
        JsonObject task = behavior("""
                t:
                  type: task
                  script:
                  - if <player.has_flag[seen]>:
                    - narrate "Again."
                    - stop
                  - narrate "First."
                  - flag player seen
                """, "denizen:task/t");
        JsonObject main = task.getAsJsonObject("routines").getAsJsonObject("main");
        assertEquals("condition", main.get("type").getAsString());
        assertEquals("Again.", main.getAsJsonObject("then").getAsJsonObject("arguments").get("text").getAsString());
        assertEquals(2, main.getAsJsonObject("else").getAsJsonArray("steps").size());
    }

    @Test void numericFlagsBecomeQuestStagesAndBooleanFlagsFlags() throws IOException {
        JsonObject task = behavior("""
                t:
                  type: task
                  script:
                  - flag player visits:++
                  - flag npc open:false
                  - if <player.flag[visits]> > 2:
                    - flag server done
                """, "denizen:task/t");
        String text = task.toString();
        assertTrue(text.contains("\"capability\":\"animus:advance-quest-state\",\"arguments\":{\"name\":\"visits\",\"scope\":\"player\"}"), text);
        assertTrue(text.contains("\"name\":\"open\",\"scope\":\"npc\",\"value\":false"), text);
        assertTrue(text.contains("\"atLeast\":3"), text);
        assertTrue(text.contains("\"name\":\"done\",\"scope\":\"global\""), text);
    }

    @Test void unconvertibleLinesAreKeptWithTheirReason() throws IOException {
        TreeMap<String, String> scripts = new TreeMap<>();
        scripts.put("a.dsc", "t:\n  type: task\n  script:\n  - narrate \"<player.money>\"\n  - narrate ok\n");
        DenizenConverter.Output output = DenizenConverter.convert(new DenizenConverter.Input(scripts, Optional.empty(), Optional.empty(), new TreeMap<>()));
        JsonObject kept = output.report().getAsJsonArray("preserved").get(0).getAsJsonObject();
        assertEquals("a.dsc:4", kept.get("source").getAsString());
        assertEquals("tag player.money has no Animus placeholder", kept.get("reason").getAsString());
        assertEquals("partial", output.report().getAsJsonObject("behaviorStatus").get("denizen:task/t").getAsString());
    }
}
