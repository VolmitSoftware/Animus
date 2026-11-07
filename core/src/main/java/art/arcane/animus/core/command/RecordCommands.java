package art.arcane.animus.core.command;

import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.text.Palette;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import java.util.List;
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
    void status(@Param(name = "page", defaultValue = "1", description = "The page to show") int page) {
        CommandActor actor = actor();
        List<String> rows = recordings().status(actor);
        CommandGroup.listing(actor, "Recordings (" + rows.size() + ")", "/" + BuildIdentity.ID + " record status", rows, page, "Nothing recording; /animus record start <recording> begins");
    }

    @Director(description = "Show or hide the ghost that replays your last moves while you record", origin = DirectorOrigin.PLAYER)
    void ghost(@Param(name = "enabled", defaultValue = DiagnosticCommands.TOGGLE, description = "on, off or toggle") String enabled) {
        CommandActor actor = actor();
        Optional<Optional<Boolean>> chosen = DiagnosticCommands.enabled(enabled);
        if (chosen.isEmpty()) { actor.tell(Palette.error(DiagnosticCommands.unknownState(enabled))); return; }
        recordings().ghost(actor, chosen.get());
    }

    @Director(description = "Play a saved recording once as a ghost only you see", origin = DirectorOrigin.PLAYER)
    void preview(@Param(name = "recording", description = "The saved recording id") String recording) { recordings().preview(actor(), recording); }

    @Director(description = "End your recording preview", origin = DirectorOrigin.PLAYER)
    void clear() { recordings().stopPreview(actor()); }
}
