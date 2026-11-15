package art.arcane.animus.core.owner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import art.arcane.animus.core.editor.EditorHost;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** An owner's nodes: live while online, remembered after, and looked up once in the background when never seen. */
class SeenOwnerPermissionsTest {
    private final UUID alex = UUID.randomUUID();
    private boolean online = true;
    private final List<Runnable> lane = new ArrayList<>();
    private int lookups;
    private final SeenOwnerPermissions permissions = new SeenOwnerPermissions(
            (UUID player) -> online ? Optional.of((Predicate<String>) Set.of("animus.owned.commands")::contains) : Optional.empty(),
            (UUID player, String node) -> { lookups++; return node.equals("animus.owned.items") ? EditorHost.Admin.ADMIN : EditorHost.Admin.UNKNOWN; }, lane::add);

    @Test void anOnlineAnswerIsLiveAndRememberedAfterTheyLeave() {
        assertEquals(OwnerPermissions.Answer.YES, permissions.cached(alex, "animus.owned.commands"));
        online = false;
        assertEquals(OwnerPermissions.Answer.YES, permissions.cached(alex, "animus.owned.commands"), "the last answer seen");
        assertEquals(0, lookups);
    }

    @Test void aNeverSeenOfflineOwnerIsRefusedUntilOneLookupAnswers() {
        online = false;
        assertEquals(OwnerPermissions.Answer.UNKNOWN, permissions.cached(alex, "animus.owned.items"));
        assertEquals(OwnerPermissions.Answer.UNKNOWN, permissions.cached(alex, "animus.owned.items"));
        assertEquals(1, lane.size(), "one lookup for one node");
        lane.removeFirst().run();
        assertEquals(OwnerPermissions.Answer.YES, permissions.cached(alex, "animus.owned.items"));
        assertEquals(OwnerPermissions.Answer.UNKNOWN, permissions.has(alex, "animus.owned.players"), "no permission plugin could answer");
    }
}
