package art.arcane.animus.core.command;

import art.arcane.animus.core.text.Palette;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/** {@code /animus record}: performance capture, the recording ghost and previews of saved recordings. */
@Director(name = "record", description = "Record a player's performance for NPC playback")
final class RecordCommands {
    private final Supplier<RecordingControl> recordings;

    /** {@code recordings} is read per command, so a tree built without services still renders its help. */
    RecordCommands(Supplier<RecordingControl> recordings) { this.recordings = recordings; }

    private static CommandActor actor() { return AnimusDirector.actor(); }

    private RecordingControl recordings() { return recordings.get(); }

    @Director(description = "Start recording yourself as recordings/<id>.json", origin = DirectorOrigin.PLAYER)
    void start(@Param(name = "recording", description = "The recording id: 1 to 64 lowercase letters, digits, _ or -") String recording) {
        recordings().start(actor(), recording);
    }

    @Director(description = "Stop recording and save it", origin = DirectorOrigin.PLAYER)
    void stop() { recordings().stop(actor()); }

    @Director(description = "Stop recording and discard it", origin = DirectorOrigin.PLAYER)
    void cancel() { recordings().cancel(actor()); }

    @Director(description = "Show running recordings and previews")
    void status() {
        CommandActor actor = actor();
        recordings().status(actor).stream().limit(CommandGroup.MAXIMUM_LINES).forEach(actor::tell);
    }

    @Director(description = "Show or hide the ghost that replays your last moves while you record", origin = DirectorOrigin.PLAYER)
    void ghost(@Param(name = "enabled", description = "on, off or toggle") String enabled) {
        CommandActor actor = actor();
        Optional<Boolean> chosen = switch (enabled.toLowerCase(Locale.ROOT)) {
            case "toggle" -> Optional.empty();
            case "on", "true", "yes", "enabled" -> Optional.of(true);
            case "off", "false", "no", "disabled" -> Optional.of(false);
            default -> null;
        };
        if (chosen == null) { actor.tell(Palette.error("Unknown state " + enabled + "; use on, off or toggle")); return; }
        recordings().ghost(actor, chosen);
    }

    @Director(description = "Play a saved recording once as a ghost only you see, or stop it", origin = DirectorOrigin.PLAYER)
    void preview(@Param(name = "recording", description = "The saved recording id") String recording,
                 @Param(name = "action", defaultValue = "play", description = "play, or stop to end the preview") String action) {
        CommandActor actor = actor();
        switch (action.toLowerCase(Locale.ROOT)) {
            case "play" -> recordings().preview(actor, recording);
            case "stop" -> recordings().stopPreview(actor);
            default -> actor.tell(Palette.error("Unknown action " + action + "; use play or stop"));
        }
    }
}
