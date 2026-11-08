package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.recording.Playback;
import art.arcane.animus.core.recording.RecordingDocument;
import art.arcane.animus.core.text.ChatLine;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Saved recordings to play on the selection, and the {@code /animus record} controls: start, stop, the ghost and the
 * preview. Sneak-click a recording to preview, rename or delete it.
 */
final class RecordingScreen<P, S> extends ToolScreen<P, S> {
    static final int START = 46, STOP = 47, GHOST = 51, END_PREVIEW = 52, SELECTED = 53;
    /** Ticks after Stop before the list is read again, so the saved file shows. */
    static final int SAVE_DELAY = 20;
    private List<RecordingFiles.Recording> recordings;

    RecordingScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) {
        super(toolkit, viewer, commands);
        reload();
    }

    void reload() {
        toolkit.later(viewer, (ToolkitFiles ignored) -> files(toolkit).recordings(), (List<RecordingFiles.Recording> found) -> { recordings = found; viewer.refresh(); });
    }

    static <P, S> RecordingFiles files(Toolkit<P, S> toolkit) { return new RecordingFiles(toolkit.npcs(), toolkit.documents()); }

    @Override String title() { return "Recordings"; }
    @Override int rows() { return 6; }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        Set<String> picked = new HashSet<>(selected);
        if (recordings == null) icon(22, "minecraft:clock", "&7Reading recordings/", List.of(), Optional.empty());
        else list(recordings, "No recordings yet; record one below", (Integer slot, RecordingFiles.Recording recording) -> {
            List<String> lines = new ArrayList<>(describe(recording));
            List<String> mine = recording.playerIds().stream().filter(picked::contains).toList();
            if (!mine.isEmpty()) lines.add("&7Selection plays it &f" + recording.players().get(mine.getFirst()).orElse(Playback.DEFAULT).key() + "&7 on " + ToolkitFiles.count(mine.size()));
            button(slot, recording.error().isPresent() ? "minecraft:barrier" : "minecraft:music_disc_13", title(recording.id()),
                    lore(lines, hints("play it on the selection", "next playback: once, loop, pingpong", "preview, rename, delete")), (GuiModel.Click click) -> {
                        if (sneak(click)) { viewer.push(new RecordingDetailScreen<>(toolkit, viewer, commands, recording.id(), this::reload)); return; }
                        Optional<List<String>> ids = chosen();
                        if (ids.isEmpty()) return;
                        if (right(click)) toolkit.write(viewer, (ToolkitFiles ignored) -> files(toolkit).playback(recording.id(), ids.get()));
                        else toolkit.write(viewer, (ToolkitFiles ignored) -> files(toolkit).assign(recording.id(), ids.get()));
                        reload();
                    });
        });
        back();
        button(START, "minecraft:lime_dye", title("Start recording"), lore(List.of("&7Records you from where you stand", "&7Stop saves it"), any("type an id")),
                (GuiModel.Click click) -> prompt("Recording id", "", (String typed) -> {
                    String id = typed.strip().toLowerCase(Locale.ROOT);
                    if (!id.matches(RecordingDocument.ID)) { toolkit.say(viewer.player(), ChatLine.error("A recording id is 1 to 64 lowercase letters, digits, _ or -")); return; }
                    command("record start " + id);
                    viewer.close();
                }));
        button(STOP, "minecraft:red_dye", title("Stop recording"), lore(List.of("&7Ends your recording"), hints("stop and save", "stop and discard", "stop and save")),
                (GuiModel.Click click) -> {
                    command(right(click) ? "record cancel" : "record stop");
                    viewer.schedule(SAVE_DELAY, this::reload);
                });
        button(GHOST, "minecraft:glass", title("Ghost"), lore(List.of("&7Replays your last moves while you record"), any("show or hide")), (GuiModel.Click click) -> command("record ghost"));
        button(END_PREVIEW, "minecraft:barrier", title("End preview"), any("hide your preview ghost"), (GuiModel.Click click) -> command("record clear"));
        icon(SELECTED, "minecraft:name_tag", title(ToolkitFiles.count(selected.size()) + " selected"), List.of("&7" + names(selected, 6)), Optional.empty());
    }

    /** Runs an {@code /animus} command as the player; its reply reaches them as chat. */
    void command(String command) { viewer.host().command(Optional.of(viewer.player()), "animus " + command); }

    /** Length, frames and users, the first lore lines of a recording. */
    static List<String> describe(RecordingFiles.Recording recording) {
        List<String> lines = new ArrayList<>();
        if (recording.error().isPresent()) lines.add("&cInvalid: " + recording.error().get());
        else lines.add("&7" + recording.frames() + " frames, " + String.format(Locale.ROOT, "%.1f", recording.durationTicks() / 20.0) + " s");
        lines.add("&7Played by &f" + ToolkitFiles.count(recording.players().size()));
        if (!recording.routes().isEmpty()) lines.add("&7Step in &f" + names(recording.routes(), 3));
        return lines;
    }
}
