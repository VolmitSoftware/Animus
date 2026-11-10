package art.arcane.animus.bukkit.integration.libsdisguises;

import art.arcane.animus.core.presentation.BodySkin;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Player skins for disguises that name a player without textures, fetched through LibsDisguises once per name. With
 * its SaveGameProfiles off LibsDisguises keeps no profile it fetched, so the reload a fetched skin starts would fetch it
 * again and start another reload; the skin kept here ends that after one reload.
 */
final class PlayerSkins {
    /** A player's skin now, or pending while it is fetched; {@code arrived} then takes it, or nothing when the player has none. */
    interface Lookup { Answer fetch(String name, Consumer<Optional<BodySkin>> arrived); }

    record Answer(Optional<BodySkin> skin, boolean pending) {}

    private final Lookup lookup;
    private final Runnable reload;
    private final Map<String, Optional<BodySkin>> fetched = new ConcurrentHashMap<>();

    /** @param reload runs once for each name whose skin arrives after it was asked for */
    PlayerSkins(Lookup lookup, Runnable reload) { this.lookup = Objects.requireNonNull(lookup); this.reload = Objects.requireNonNull(reload); }

    Answer skin(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Optional<BodySkin> kept = fetched.get(key);
        if (kept != null) return new Answer(kept, false);
        return lookup.fetch(name, (Optional<BodySkin> skin) -> { if (fetched.putIfAbsent(key, skin) == null) reload.run(); });
    }
}
