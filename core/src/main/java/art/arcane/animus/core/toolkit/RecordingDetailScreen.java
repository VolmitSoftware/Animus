package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** One saved recording: preview it, rename it with everything that plays it, or delete it. */
final class RecordingDetailScreen<P, S> extends ToolScreen<P, S> {
    static final int HEADER = 4, PREVIEW = 20, RENAME = 22, DELETE = 24, UNASSIGN = 25;
    private final Runnable changed;
    private String id;
    /** Null while the file is being read; empty once it is gone. */
    private Optional<RecordingFiles.Recording> recording;

    /** {@code changed} runs after every write, so the list below reads the files again. */
    RecordingDetailScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String id, Runnable changed) {
        super(toolkit, viewer, commands);
        this.id = id; this.changed = changed;
        reload(id);
    }

    @Override String title() { return "Recording " + id; }
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
        if (recording == null) { icon(22, "minecraft:clock", "&7Reading recordings/" + id + ".json", List.of(), Optional.empty()); return; }
        if (recording.isEmpty()) { icon(22, "minecraft:barrier", "&7recordings/" + id + ".json is gone", List.of("&7Back returns to the list"), Optional.empty()); return; }
        RecordingFiles.Recording shown = recording.get();
        List<String> info = new ArrayList<>(RecordingScreen.describe(shown));
        if (!shown.players().isEmpty()) info.add("&7" + names(shown.playerIds(), 6));
        icon(HEADER, "minecraft:music_disc_13", title(shown.id()), info, Optional.empty());
        button(PREVIEW, "minecraft:ender_eye", title("Preview"), lore(List.of("&7Plays it once as a ghost only you see"), any("preview")), (GuiModel.Click click) -> {
            viewer.host().command(Optional.of(viewer.player()), "animus record preview " + shown.id());
            viewer.close();
        });
        button(RENAME, "minecraft:name_tag", title("Rename"), lore(List.of("&7Repoints its NPCs and route steps"), any("type the new id")),
                (GuiModel.Click click) -> prompt("New recording id", shown.id(), (String typed) -> {
                    String to = typed.strip();
                    write(to, (ToolkitFiles ignored) -> RecordingScreen.files(toolkit).rename(shown.id(), to));
                }));
        guarded(DELETE, "minecraft:lava_bucket", "Delete", shown.players().isEmpty() ? "Removes recordings/" + shown.id() + ".json" : "Refused while NPCs play it",
                0, true, () -> write(shown.id(), (ToolkitFiles ignored) -> RecordingScreen.files(toolkit).delete(shown.id(), false)));
        if (!shown.players().isEmpty())
            guarded(UNASSIGN, "minecraft:tnt", "Unassign and delete", "Also removes navigation from its NPCs", shown.players().size(), true,
                    () -> write(shown.id(), (ToolkitFiles ignored) -> RecordingScreen.files(toolkit).delete(shown.id(), true)));
    }
}
