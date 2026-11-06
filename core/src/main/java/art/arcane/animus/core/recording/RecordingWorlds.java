package art.arcane.animus.core.recording;

import java.util.Optional;
import java.util.function.Function;

/**
 * The world a recording names. Capture writes a portable reference, the Bukkit world name or the dimension key; the
 * server that plays it resolves that, or a world UUID, through {@code RuntimeHost.resolveWorld} to its runtime key.
 */
public final class RecordingWorlds {
    private RecordingWorlds() {}

    /**
     * Both references name the same world, as the {@code same_world} expression function decides: each side resolves
     * when the server knows it and otherwise compares as written, ignoring case.
     */
    public static boolean same(String first, String second, Function<String, Optional<String>> resolve) {
        if (first == null || second == null) return false;
        return resolve.apply(first).orElse(first).equalsIgnoreCase(resolve.apply(second).orElse(second));
    }

    /** Why the recording cannot play for a body standing in {@code here}, or empty when it can. */
    public static Optional<String> refusal(RecordingDocument document, String here, Function<String, Optional<String>> resolve) {
        if (resolve.apply(document.world()).isEmpty())
            return Optional.of("recording " + document.id() + " names world " + document.world() + ", which is not loaded");
        if (!same(document.world(), here, resolve))
            return Optional.of("recording " + document.id() + " was made in world " + document.world() + ", not the NPC's world");
        return Optional.empty();
    }
}
