package art.arcane.animus.bukkit.recording;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * At most one entry per player and a live cap on all of them. Lowering the cap refuses new entries and leaves running
 * ones alone. Safe from any thread: on Folia every player's owner admits its own.
 */
public final class RecordingRoster<S> {
    private final String noun;
    private final Map<UUID, S> entries = new HashMap<>();

    /** @param noun what an entry is in refusals, such as {@code recording} */
    public RecordingRoster(String noun) { this.noun = Objects.requireNonNull(noun); }

    /** Admits {@code session} for {@code player}, or returns why not. */
    public synchronized Optional<String> admit(UUID player, S session, boolean enabled, int maximum) {
        Objects.requireNonNull(player); Objects.requireNonNull(session);
        if (!enabled) return Optional.of("Recordings are disabled; set [recordings] enabled = true in config.toml.");
        if (entries.containsKey(player)) return Optional.of("You already have a " + noun + " running.");
        if (entries.size() >= maximum) return Optional.of("Every " + noun + " slot is in use (" + entries.size() + " of " + maximum + "); try again when one ends.");
        entries.put(player, session);
        return Optional.empty();
    }

    public synchronized Optional<S> get(UUID player) { return Optional.ofNullable(entries.get(player)); }

    /** Frees the player's slot only when {@code session} still holds it. */
    public synchronized boolean remove(UUID player, S session) { return entries.remove(player, session); }

    public synchronized int size() { return entries.size(); }

    public synchronized List<S> values() { return List.copyOf(entries.values()); }
}
