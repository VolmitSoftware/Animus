package art.arcane.animus.core.npc;

import com.google.gson.JsonParser;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An operator holds every unset node by default; only nodes set for a player count toward a deny filter. */
class ViewerFilterTest {
    private static final UUID ALEX = new UUID(0, 1);
    private static final Predicate<String> EVERYTHING = (String node) -> true, NOTHING = (String node) -> false;

    private static ViewerFilter filter(String json) { return ViewerFilter.of(JsonParser.parseString("{\"viewers\":" + json + "}").getAsJsonObject()); }

    @Test
    void shouldShowADenyGroupsNpcToAnOperatorOutsideTheGroup() {
        ViewerFilter deny = filter("{\"groups\":[\"outcasts\"],\"permissions\":[\"court.banned\"]}");
        assertFalse(deny.hides(ALEX, 2, EVERYTHING, NOTHING), "an operator's default grant is not membership");
        assertTrue(deny.hides(ALEX, 2, EVERYTHING, Set.of("group.outcasts")::contains), "a set group node still hides it");
        assertTrue(deny.hides(ALEX, 2, EVERYTHING, Set.of("court.banned")::contains), "a set permission still hides it");
    }

    @Test
    void shouldLetAnOperatorPassAnAllowFilter() {
        ViewerFilter allow = filter("{\"mode\":\"allow\",\"groups\":[\"nobles\"]}");
        assertFalse(allow.hides(ALEX, 2, EVERYTHING, NOTHING));
        assertTrue(allow.hides(ALEX, 2, NOTHING, NOTHING));
    }
}
