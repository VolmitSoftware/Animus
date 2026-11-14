package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.recording.Playback;
import art.arcane.animus.core.recording.RecordingDocument;
import art.arcane.animus.core.text.Words;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Saved recordings: a click puts one on the selected NPCs, a right-click opens its details. With nothing selected, as
 * from the Library, a click opens the details. New recording names one and records the player after a countdown.
 */
final class RecordingScreen<P, S> extends ToolScreen<P, S> {
    static final int CREATE = ACTIONS, TRAIL = ACTIONS + 1, DISCARD = ACTIONS + 8;
    /** Ticks after Stop before the list is read again, so the saved file shows. */
    static final int SAVE_DELAY = 20;
    static final String HELP_TEXT = MovementScreen.RECORDING_HELP + " New recording counts down 3 seconds, then records you until you click Stop and save in chat.";
    private List<RecordingFiles.Recording> recordings;

    RecordingScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) {
        super(toolkit, viewer, commands);
    }

    @Override void begin() { reload(); }

    void reload() {
        toolkit.later(viewer, (ToolkitFiles ignored) -> files(toolkit).recordings(), (List<RecordingFiles.Recording> found) -> { recordings = found; viewer.refresh(); });
    }

    static <P, S> RecordingFiles files(Toolkit<P, S> toolkit) { return new RecordingFiles(toolkit.npcs(), toolkit.documents()); }

    @Override String title() { return "Recordings"; }
    @Override String node() { return PermissionNodes.RECORD; }
    @Override int rows() { return 6; }
    @Override boolean searchable() { return true; }

    @Override void draw() {
        List<String> ids = toolkit.selected(viewer.id());
        if (recordings == null) icon(22, "minecraft:clock", "&7Reading recordings", List.of(), Optional.empty());
        else list(recordings, RecordingFiles.Recording::id, "No recordings yet; New recording makes one", (Integer slot, RecordingFiles.Recording recording) -> entry(slot, recording, ids));
        if (commands.recording().test(viewer.id())) {
            button(CREATE, "minecraft:red_dye", title("Stop and save"), lore(List.of("&7Ends your recording and saves it"), any("stop and save")), (GuiModel.Click click) -> {
                command("record stop");
                viewer.schedule(SAVE_DELAY, this::reload);
            });
            confirm(DISCARD, "minecraft:bucket", "Discard", "Ends your recording; nothing is saved", 0, true, () -> command("record cancel"));
        } else if (recordings == null) icon(CREATE, "minecraft:gray_dye", "&7New recording", List.of("&7Reading recordings"), Optional.empty());
        else button(CREATE, "minecraft:writable_book", title("New recording"), lore(List.of("&7Records you after a 3 second countdown"), any("name it")), (GuiModel.Click click) -> create(ids));
        if (advanced())
            button(TRAIL, "minecraft:glass", title("Show my trail"), lore(List.of("&7While you record, a ghost replays your last moves"), any("turn on or off")), (GuiModel.Click click) -> command("record ghost"));
        back();
        help(HELP_TEXT, MovementScreen.RECORDINGS, "recording-a-performance");
    }

    private void entry(int slot, RecordingFiles.Recording recording, List<String> ids) {
        List<String> mine = recording.playerIds().stream().filter(ids::contains).toList();
        String played = mine.isEmpty() ? ", played by " + ToolkitFiles.count(recording.players().size()) : "";
        List<String> lines = new ArrayList<>(List.of(recording.error().map((String error) -> "&cInvalid: " + error).orElse("&7" + MovementScreen.seconds(recording.durationTicks()) + played)));
        if (!mine.isEmpty())
            lines.add("&fNow: " + named(mine) + (mine.size() == 1 ? " plays it (" : " play it (") + RecordingFiles.label(recording.players().get(mine.getFirst()).orElse(Playback.DEFAULT)) + ")");
        if (advanced()) lines.add("&8" + recording.id());
        button(slot, recording.error().isPresent() ? "minecraft:barrier" : "minecraft:music_disc_13", title(Words.readable(recording.id())),
                lore(lines, ids.isEmpty() ? any("details") : hints("use on " + named(ids), "details", "")), (GuiModel.Click click) -> {
                    if (ids.isEmpty() || right(click)) { push(new RecordingDetailScreen<>(toolkit, viewer, commands, recording.id(), this::reload)); return; }
                    if (recording.error().isPresent()) { refuse("Recording " + Words.readable(recording.id()) + " cannot be played: " + recording.error().get()); return; }
                    ask(slot, "Puts " + Words.readable(recording.id()) + " on " + named(ids), ids.size(), false, () -> {
                        toolkit.write(viewer, (ToolkitFiles ignored) -> files(toolkit).assign(recording.id(), ids));
                        reload();
                    });
                });
    }

    /** New recording, once the list is read: a free name is suggested; a taken one asks first, since recording over it replaces it. */
    private void create(List<String> ids) {
        List<String> taken = recordings.stream().map(RecordingFiles.Recording::id).toList();
        String base = ids.isEmpty() ? "" : toolkit.npcs().find(ids.getFirst()).map(NpcsScreen::name).orElse(ids.getFirst()) + " recording";
        prompt("Recording name", RouteFiles.suggest(base, "recording", taken), (String typed) -> {
            String id = RouteFiles.typed(typed);
            if (!id.matches(RecordingDocument.ID)) { refuse(RecordingFiles.NAMES); return; }
            Runnable record = () -> toolkit.captures().record(viewer, commands, id, ids);
            if (taken.contains(id)) ask(CREATE, "Records over " + Words.readable(id) + "; the old take is replaced", 0, true, record);
            else record.run();
        });
    }

    /** Runs an {@code /animus} command as the player; its reply reaches them as chat. */
    void command(String command) { viewer.host().command(Optional.of(viewer.player()), "animus " + command); }
}
