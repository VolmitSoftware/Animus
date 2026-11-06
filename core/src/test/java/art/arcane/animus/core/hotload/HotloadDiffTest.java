package art.arcane.animus.core.hotload;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HotloadDiffTest {
    @Test void tomlDiffNamesEachChangedKey() {
        String before = "statusMessage = 'Hello'\n[npcs]\nviewDistance = 48\nmaximumVisible = 64\n[shops.messages]\nbought = '&aBought'\n";
        String after = "statusMessage = 'Hello'\n[npcs]\nviewDistance = 32\n[shops.messages]\nbought = '&bBought'\n[hotload]\nsound = false\n";
        assertEquals(List.of(
                new HotloadDiff.Change("hotload.sound", HotloadDiff.MISSING, "false"),
                new HotloadDiff.Change("npcs.maximumVisible", "64", HotloadDiff.REMOVED),
                new HotloadDiff.Change("npcs.viewDistance", "48", "32"),
                new HotloadDiff.Change("shops.messages.bought", "\"&aBought\"", "\"&bBought\"")),
                HotloadDiff.diff("config.toml", before, after));
    }

    @Test void jsonDiffWalksObjectsAndArrays() {
        String before = "{\"name\":\"Guard\",\"position\":{\"x\":1,\"y\":64},\"behaviors\":[\"town:a\",\"town:b\"],\"tags\":[]}";
        String after = "{\"name\":\"Guard Two\",\"position\":{\"x\":1,\"y\":65},\"behaviors\":[\"town:a\"],\"tags\":[\"x\"]}";
        assertEquals(List.of(
                new HotloadDiff.Change("behaviors[1]", "\"town:b\"", HotloadDiff.REMOVED),
                new HotloadDiff.Change("name", "\"Guard\"", "\"Guard Two\""),
                new HotloadDiff.Change("position.y", "64", "65"),
                new HotloadDiff.Change("tags", "[]", HotloadDiff.REMOVED),
                new HotloadDiff.Change("tags[0]", HotloadDiff.MISSING, "\"x\"")),
                HotloadDiff.diff("npcs/town/guard.json", before, after));
    }

    @Test void createdAndDeletedFilesListEveryKeyAndUnparsableTextComparesWhole() {
        assertEquals(List.of(new HotloadDiff.Change("id", HotloadDiff.MISSING, "\"guard\"")), HotloadDiff.diff("npcs/town/guard.json", null, "{\"id\":\"guard\"}"));
        assertEquals(List.of(new HotloadDiff.Change("id", "\"guard\"", HotloadDiff.REMOVED)), HotloadDiff.diff("npcs/town/guard.json", "{\"id\":\"guard\"}", null));
        assertEquals(List.of(new HotloadDiff.Change("$", "{", "{\"a\"")), HotloadDiff.diff("npcs/town/guard.json", "{", "{\"a\""));
        assertTrue(HotloadDiff.diff("npcs/town/guard.json", "{\"a\": 1}", "{ \"a\":1 }").isEmpty(), "formatting alone is no change");
    }

    @Test void numericallyEqualNumbersAreNoChange() {
        assertTrue(HotloadDiff.diff("npcs/town/guard.json", "{\"position\":{\"x\":0.0,\"y\":64,\"z\":1.50}}", "{\"position\":{\"x\":0,\"y\":64.0,\"z\":1.5}}").isEmpty());
        assertTrue(HotloadDiff.diff("config.toml", "[npcs]\nscale = 1.0\n", "[npcs]\nscale = 1\n").isEmpty());
        assertEquals(List.of(new HotloadDiff.Change("position.x", "0.0", "0.5")),
                HotloadDiff.diff("npcs/town/guard.json", "{\"position\":{\"x\":0.0}}", "{\"position\":{\"x\":0.5}}"));
        assertEquals(List.of(new HotloadDiff.Change("id", "1", "\"1\"")), HotloadDiff.diff("npcs/town/guard.json", "{\"id\":1}", "{\"id\":\"1\"}"));
    }

    @Test void valuesAreCompactedToOneBoundedLine() {
        assertEquals("a\\nb", HotloadDiff.compact("a\nb", 120));
        String compact = HotloadDiff.compact("x".repeat(500), 120);
        assertEquals(120, compact.length());
        assertTrue(compact.endsWith("..."));
        assertEquals("null", HotloadDiff.compact(null, 120));
    }
}
