package art.arcane.animus.core.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachedPlaceholdersTest {
    private final UUID alex = UUID.randomUUID(), sam = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong();

    /** A stand-in for PlaceholderAPI: {@code %player_name%} is the viewer's name and {@code %bal%} a balance that can change. */
    static final class FakeApi implements CachedPlaceholders.Source {
        final Map<UUID, String> names;
        final List<String> asked = new ArrayList<>();
        volatile boolean installed = true, failing;
        volatile String balance = "5";
        FakeApi(Map<UUID, String> names) { this.names = names; }
        @Override public boolean available() { return installed; }
        @Override public String fill(UUID viewer, String text) {
            asked.add(text);
            if (failing) throw new IllegalStateException("expansion broke");
            String name = names.get(viewer);
            if (name == null) return null;
            return text.replace("%player_name%", name).replace("%bal%", balance);
        }
    }

    private FakeApi api() { return new FakeApi(Map.of(alex, "Alex", sam, "Sam")); }

    @Test
    void shouldFillEachViewersOwnValue() {
        CachedPlaceholders placeholders = new CachedPlaceholders(api(), clock::get);
        assertEquals("Hi Alex", placeholders.fill(alex, "Hi %player_name%"));
        assertEquals("Hi Sam", placeholders.fill(sam, "Hi %player_name%"));
    }

    @Test
    void shouldNeverAskThePluginForTextWithoutAPlaceholder() {
        FakeApi api = api();
        CachedPlaceholders placeholders = new CachedPlaceholders(api, clock::get);
        String plain = "50% off today";
        assertSame(plain, placeholders.fill(alex, plain));
        assertSame("100%%", placeholders.fill(alex, "100%%"));
        assertTrue(api.asked.isEmpty(), api.asked.toString());
    }

    @Test
    void shouldReuseAFilledTextUntilItExpires() {
        FakeApi api = api();
        CachedPlaceholders placeholders = new CachedPlaceholders(api, clock::get);
        assertEquals("Balance 5", placeholders.fill(alex, "Balance %bal%"));
        api.balance = "7";
        clock.addAndGet(CachedPlaceholders.TTL_NANOS - 1);
        assertEquals("Balance 5", placeholders.fill(alex, "Balance %bal%"));
        assertEquals(1, api.asked.size());
        clock.addAndGet(1);
        assertEquals("Balance 7", placeholders.fill(alex, "Balance %bal%"));
        assertEquals(2, api.asked.size());
    }

    @Test
    void shouldLeaveTheTextAsWrittenWithoutAPlayerOrPlugin() {
        FakeApi api = api();
        CachedPlaceholders placeholders = new CachedPlaceholders(api, clock::get);
        assertEquals("Hi %player_name%", placeholders.fill(UUID.randomUUID(), "Hi %player_name%"));
        assertEquals("Hi %player_name%", placeholders.fill(null, "Hi %player_name%"));
        api.installed = false;
        assertFalse(placeholders.active());
        assertEquals("Hi %player_name%", placeholders.fill(sam, "Hi %player_name%"));
    }

    @Test
    void shouldLeaveTheTextAsWrittenWhenAnExpansionFails() {
        FakeApi api = api();
        api.failing = true;
        CachedPlaceholders placeholders = new CachedPlaceholders(api, clock::get);
        assertEquals("Hi %player_name%", placeholders.fill(alex, "Hi %player_name%"));
        assertEquals("Hi %player_name%", placeholders.fill(alex, "Hi %player_name%"));
        assertEquals(1, api.asked.size(), "a failure is kept like a value, so a broken expansion is not asked every packet");
    }

    @Test
    void shouldForgetAPlayerWhoLeft() {
        FakeApi api = api();
        CachedPlaceholders placeholders = new CachedPlaceholders(api, clock::get);
        placeholders.fill(alex, "Balance %bal%");
        placeholders.fill(sam, "Balance %bal%");
        api.balance = "9";
        placeholders.forget(alex);
        assertEquals("Balance 9", placeholders.fill(alex, "Balance %bal%"));
        assertEquals("Balance 5", placeholders.fill(sam, "Balance %bal%"));
    }

    @Test
    void shouldFillListsOnlyWhenALineIsMarked() {
        CachedPlaceholders placeholders = new CachedPlaceholders(api(), clock::get);
        List<String> plain = List.of("Welcome", "to spawn");
        assertSame(plain, placeholders.fill(alex, plain));
        assertEquals(List.of("Welcome", "Alex"), placeholders.fill(alex, List.of("Welcome", "%player_name%")));
    }

    @Test
    void shouldFollowTheRefreshSetting() {
        CachedPlaceholders placeholders = new CachedPlaceholders(api(), clock::get);
        assertEquals(0, placeholders.refreshTicks());
        placeholders.refreshTicks(40);
        assertEquals(40, placeholders.refreshTicks());
        assertEquals(0, Placeholders.NONE.refreshTicks());
        assertEquals("%player_name%", Placeholders.NONE.fill(alex, "%player_name%"));
    }
}
