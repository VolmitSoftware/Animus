package art.arcane.animus.bukkit.integration.libsdisguises;

import art.arcane.animus.core.presentation.BodySkin;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerSkinsTest {
    private static final BodySkin STEVE = new BodySkin("c2tpbg==", null);
    private final List<String> fetches = new ArrayList<>();
    private final List<Consumer<Optional<BodySkin>>> waiting = new ArrayList<>();
    private final AtomicInteger reloads = new AtomicInteger();
    /** LibsDisguises with SaveGameProfiles off: every lookup fetches again and answers once the fetch lands. */
    private final PlayerSkins skins = new PlayerSkins((String name, Consumer<Optional<BodySkin>> arrived) -> {
        fetches.add(name);
        waiting.add(arrived);
        return new PlayerSkins.Answer(Optional.empty(), true);
    }, reloads::incrementAndGet);

    @Test void aFetchedSkinIsKeptSoTheReloadItCausesFindsItAndReloadsOnce() {
        assertEquals(new PlayerSkins.Answer(Optional.empty(), true), skins.skin("Steve"));
        assertEquals(new PlayerSkins.Answer(Optional.empty(), true), skins.skin("steve"));
        for (Consumer<Optional<BodySkin>> arrived : List.copyOf(waiting)) arrived.accept(Optional.of(STEVE));
        assertEquals(1, reloads.get(), "two waits on one fetch reload once");
        assertEquals(new PlayerSkins.Answer(Optional.of(STEVE), false), skins.skin("STEVE"));
        assertEquals(List.of("Steve", "steve"), fetches, "the reload reads the kept skin instead of fetching again");
    }

    @Test void aNameWithNoSkinSettlesWithoutOne() {
        skins.skin("Nobody");
        waiting.getFirst().accept(Optional.empty());
        assertEquals(new PlayerSkins.Answer(Optional.empty(), false), skins.skin("Nobody"));
        assertEquals(1, reloads.get());
        assertEquals(1, fetches.size());
    }
}
