package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.recording.Playback;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Words;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** One saved recording: rename it with everything that plays it, preview it, set how the selected NPCs repeat it, or delete it. */
final class RecordingDetailScreen<P, S> extends ToolScreen<P, S> {
    static final int HEADER = 4, RENAME = 19, PREVIEW = 20, PLAYBACK = 28, DELETE = 35;
    private final Runnable changed;
    private String id;
    /** Null while the file is being read; empty once it is gone. */
    private Optional<RecordingFiles.Recording> recording;

    /** {@code changed} runs after every write, so the list below reads the files again. */
    RecordingDetailScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String id, Runnable changed) {
        super(toolkit, viewer, commands);
        this.id = id; this.changed = changed;
    }

    @Override void begin() { reload(id); }

    @Override String title() { return "Recording " + Words.readable(id); }
    @Override String node() { return PermissionNodes.RECORD; }
    @Override int rows() { return 6; }

    /** Reads the recording again; a rename shows under {@code next} once it succeeded. */
    private void reload(String next) {
        String previous = id;
        toolkit.later(viewer, (ToolkitFiles ignored) -> {
            RecordingFiles files = RecordingScreen.files(toolkit);
            Optional<RecordingFiles.Recording> found = files.find(next);
            return found.isPresent() ? found : files.find(previous);
        }, (Optional<RecordingFiles.Recording> found) -> {
            recording = found;
            found.ifPresent((RecordingFiles.Recording loaded) -> id = loaded.id());
            viewer.refresh();
        });
    }

    private void write(String next, Toolkit.FileWork work) {
        toolkit.write(viewer, work);
        reload(next);
        changed.run();
    }

    @Override void draw() {
        back();
        help("Playback sets how the NPC repeats it: once, in a loop, or back and forth. Preview plays it to you alone as a ghost.", MovementScreen.RECORDINGS, "playing-a-recording-on-an-npc");
        if (recording == null) { icon(22, "minecraft:clock", "&7Reading recording " + Words.readable(id), List.of(), Optional.empty()); return; }
        if (recording.isEmpty()) { icon(22, "minecraft:barrier", "&7Recording " + Words.readable(id) + " is gone", List.of("&7Back returns to the list"), Optional.empty()); return; }
        RecordingFiles.Recording shown = recording.get();
        List<String> info = new ArrayList<>();
        info.add(shown.error().map((String error) -> "&cInvalid: " + error).orElse("&7" + MovementScreen.seconds(shown.durationTicks())));
        info.add(shown.players().isEmpty() ? "&7Played by no NPC" : "&fPlayed by " + npcNames(shown.playerIds(), 6));
        if (advanced()) {
            if (shown.error().isEmpty()) info.add("&7" + shown.frames() + " frames");
            if (!shown.routes().isEmpty()) info.add("&7A step in route " + names(shown.routes().stream().map(Words::readable).toList(), 3));
            info.add("&8" + RecordingFiles.path(shown.id()));
        }
        icon(HEADER, "minecraft:music_disc_13", title(Words.readable(shown.id())), info, Optional.empty());

        button(RENAME, "minecraft:name_tag", title("Rename"), lore(List.of("&7Its NPCs and routes follow the new name"), any("type a name")),
                (GuiModel.Click click) -> prompt("New recording name", shown.id(), (String typed) -> {
                    String to = RouteFiles.typed(typed);
                    write(to, (ToolkitFiles ignored) -> RecordingScreen.files(toolkit).rename(shown.id(), to));
                }));
        button(PREVIEW, "minecraft:ender_eye", title("Preview"), lore(List.of("&7A ghost only you see plays it once"), any("watch it")), (GuiModel.Click click) -> {
            viewer.close();
            viewer.host().command(Optional.of(viewer.player()), "animus record preview " + shown.id());
            toolkit.say(viewer.player(), ChatLine.reply("Preview of " + shown.id()).run("End preview", "/animus record clear", "Hides the ghost"));
        });
        playback(shown);
        List<String> players = shown.playerIds();
        confirm(DELETE, "minecraft:lava_bucket", "Delete", "Deletes recording " + Words.readable(shown.id()) + (players.isEmpty() ? "" : " and stops it on " + names(players, 4)) + "; Undo in chat brings it back",
                players.size(), true, () -> write(shown.id(), (ToolkitFiles ignored) -> RecordingScreen.files(toolkit).delete(shown.id(), !players.isEmpty())));
    }

    /** Playback of the selected NPCs that play it: Once, Loop, Back and forth. Without any, it says what to do first. */
    private void playback(RecordingFiles.Recording shown) {
        List<String> ids = toolkit.selected(viewer.id());
        List<String> mine = shown.playerIds().stream().filter(ids::contains).toList();
        if (mine.isEmpty()) {
            icon(PLAYBACK, "minecraft:gray_dye", "&7Playback", List.of("&7" + (ids.isEmpty() ? "Select an NPC that plays it" : "Use it on " + named(ids) + " first")), Optional.empty());
            return;
        }
        Playback current = shown.players().get(mine.getFirst()).orElse(Playback.DEFAULT);
        button(PLAYBACK, "minecraft:repeater", title("Playback"), lore(List.of("&7How " + named(mine) + " repeats it", "&fNow: " + RecordingFiles.label(current)), hints("next", "previous", "")),
                (GuiModel.Click click) -> {
                    Playback next = cycle(click, RecordingFiles.PLAYBACKS, current);
                    write(shown.id(), (ToolkitFiles ignored) -> RecordingScreen.files(toolkit).playback(shown.id(), mine, next));
                });
    }
}
