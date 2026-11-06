package art.arcane.animus.core.recording;

import art.arcane.animus.core.config.RecordingSettings;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.virtual.Vec3;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What {@link RecordingSessions} needs from a server: its players, their owner threads and viewer-private ghost bodies.
 * Player reads run on that player's owner.
 *
 * @param <P> the platform's online player
 * @param <I> the platform's item stack
 */
public interface RecordingHost<P, I> {
    UUID id(P player);
    String name(P player);
    boolean online(P player);
    boolean dead(P player);
    /** The key of the player's world, as recordings store it. */
    String world(P player);
    Vec3 position(P player);
    /** True when the player holds {@code animus.admin}. */
    boolean admin(P player);
    /** Reads one recorder for one session; the reader may keep item conversions between samples. */
    Supplier<RecordingCapture.Sample> sampler(P player);
    /** The player's signed {@code textures} property; empty in offline mode. */
    Optional<BodySkin> skin(P player);
    Optional<P> player(UUID id);
    /** The last known name of a player who may be offline. */
    Optional<String> knownName(UUID id);

    /** Runs {@code work} on the player's owner later, or {@code gone} instead when the player retired first. Never inline. */
    void later(P player, Runnable work, Runnable gone);
    /** Repeats every tick on the player's owner until closed; {@code retired} runs when the owner drops the player first. */
    AutoCloseable everyTick(P player, Runnable tick, Runnable retired);

    /** Empty when ghosts can be shown. */
    Optional<String> ghostsUnavailable();
    /** A ghost of {@code document} for {@code viewer} alone; hand the viewer back to {@link #release} once it is finished. */
    GhostReplay<P, I> ghost(P viewer, RecordingDocument document, String name, Optional<BodySkin> skin, RecordingSettings settings);
    /** A ghost of the viewer ended. */
    void release(P viewer);
    /** Removes every ghost body; called once at shutdown. */
    void closeGhosts();

    void info(String message);
    void warn(String message);
}
