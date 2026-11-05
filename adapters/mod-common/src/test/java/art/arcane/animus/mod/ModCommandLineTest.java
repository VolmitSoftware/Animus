package art.arcane.animus.mod;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModCommandLineTest {
    @Test void wordsSplitLikeABukkitCommandLine() {
        assertArrayEquals(new String[0], ModCommandLine.words(""));
        assertArrayEquals(new String[]{"spawn", "guard"}, ModCommandLine.words("spawn guard"));
        assertArrayEquals(new String[]{"edit", "guard", "{\"name\":", "\"Town", "Guard\"}"}, ModCommandLine.words("edit guard {\"name\": \"Town Guard\"}"));
        assertArrayEquals(new String[]{"select", ""}, ModCommandLine.words("select "));
        assertArrayEquals(new String[]{"a", "", "b"}, ModCommandLine.words("a  b"));
    }

    @Test void completionReplacesOnlyTheWordBeingTyped() {
        assertEquals(0, ModCommandLine.lastWordStart(""));
        assertEquals(0, ModCommandLine.lastWordStart("sp"));
        assertEquals(6, ModCommandLine.lastWordStart("spawn "));
        assertEquals(6, ModCommandLine.lastWordStart("spawn gu"));
        assertArrayEquals(new String[]{""}, ModCommandLine.completionWords(""));
        assertArrayEquals(new String[]{"spawn", "gu"}, ModCommandLine.completionWords("spawn gu"));
    }
}
