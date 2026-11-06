package art.arcane.animus.core.command;

import java.util.List;
import java.util.Optional;

/**
 * What {@code /animus record} reaches: the platform's recording sessions and previews. Every call runs on the actor's
 * owner; the player-only calls answer the console with a refusal.
 */
public interface RecordingControl {
    void start(CommandActor actor, String id);
    void stop(CommandActor actor);
    void cancel(CommandActor actor);
    /** The roster; a player sees its own sessions unless it holds {@code animus.admin}. */
    List<String> status(CommandActor actor);
    /** Turns the actor's ghost on or off; empty toggles it. */
    void ghost(CommandActor actor, Optional<Boolean> on);
    void preview(CommandActor actor, String id);
    void stopPreview(CommandActor actor);
    /** Saved recording ids for completion. */
    List<String> ids();
}
