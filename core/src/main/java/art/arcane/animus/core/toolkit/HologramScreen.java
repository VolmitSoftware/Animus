package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.gui.GuiBinding;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcRepository;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Hologram: the text lines above the selected NPCs, and a saved hologram shown above them at a height. Every change is
 * made line by line on each NPC's own lines, in one transaction: a new line goes at the end of each, an edit, move or
 * removal acts on that line number of every NPC that has it. A line or height that differs between them reads Mixed,
 * and typing it then sets that one value on all of them after asking. Advanced adds the sideways offsets, Above the head
 * and every hologram setting.
 */
final class HologramScreen<P, S> extends ToolScreen<P, S> {
    static final int HEADER = 4, FIRST_LINE = 9, EMPTY = 13, SAVED = 28, HEIGHT = 29, X = 30, Z = 32, ABOVE = 33, REMOVE = 35, ADD = ACTIONS, SETTINGS = ACTIONS + 8;
    /** Lines the NPC schema allows. */
    static final int MOST = 16;
    /** Where a saved hologram floats without an offset, from the NPC's feet; the first step starts here. */
    static final double[] HEAD = {0, 2.4, 0};
    /** A saved hologram's offset is kept within this many blocks of the feet. */
    static final double REACH = 16;

    /** One change to an NPC's own lines; empty when this NPC has no line for it, so it is skipped. */
    @FunctionalInterface interface LineEdit { Optional<List<JsonElement>> apply(List<JsonElement> lines); }

    HologramScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }

    @Override String title() { return "Hologram"; }
    @Override String node() { return PermissionNodes.NPC_EDIT; }
    @Override int rows() { return 6; }
    @Override boolean forSelection() { return true; }

    @Override void draw() {
        back();
        help("Text lines float above the NPC; a saved hologram is a hologram file shown above it at the height you set.", "04-npc-definitions", "names-and-text-lines");
        List<String> ids = toolkit.selected(viewer.id());
        if (ids.isEmpty()) {
            button(HEADER, "minecraft:oak_sign", title("Nothing selected"), lore(List.of("&7Pick an NPC first"), any("pick one")), (GuiModel.Click click) -> push(new NpcsScreen<>(toolkit, viewer, commands)));
            return;
        }
        List<List<JsonElement>> all = ids.stream().map((String id) -> lines(NpcScreen.stored(toolkit, id))).toList();
        int longest = all.stream().mapToInt(List::size).max().orElse(0);
        Optional<JsonObject> saved = saved(NpcScreen.stored(toolkit, ids.getFirst()));
        String name = ids.size() > 1 ? ToolkitFiles.count(ids.size()) : toolkit.npcs().find(ids.getFirst()).map(NpcsScreen::name).orElse(ids.getFirst());
        boolean same = all.stream().distinct().count() == 1;
        List<String> header = new ArrayList<>(List.of("&fNow: " + (!same ? "Mixed" : longest == 1 ? "1 line" : longest + " lines") + saved.map((JsonObject hologram) -> " and " + id(hologram)).orElse("")));
        if (ids.size() > 1) header.add(same ? "&7Changes all of them" : "&7Changes each one's lines by number");
        icon(HEADER, "minecraft:oak_sign", title(name), header, Optional.empty());
        for (int index = 0; index < longest && index < MOST; index++) line(index, all);
        if (longest == 0) icon(EMPTY, "minecraft:paper", "&7No text yet", List.of("&7Add line puts one above it"), Optional.empty());
        button(ADD, "minecraft:lime_dye", title("Add line"), lore(List.of("&7" + longest + " of " + MOST), any("type the text")), (GuiModel.Click click) -> {
            if (all.stream().allMatch((List<JsonElement> lines) -> lines.size() >= MOST)) { refuse("A hologram holds " + MOST + " lines; remove one first"); return; }
            prompt("New line", "", (String typed) -> {
                if (typed.isBlank()) return;
                edit(this, ADD, "Adds a line to " + named(ids), false, (List<JsonElement> lines) -> {
                    if (lines.size() >= MOST) return Optional.empty();
                    lines.add(new JsonPrimitive(typed));
                    return Optional.of(lines);
                }, () -> {});
            });
        });
        saved(ids, saved);
        if (advanced()) button(SETTINGS, "minecraft:writable_book", title("More settings"), lore(List.of("&7Spacing, view range, facing"), any("open")), (GuiModel.Click click) -> {
            PropertyScreen.Section section = new PropertyScreen.Section("Hologram settings", "", "", 0, List.of(), List.of("hologram"));
            push(new FieldScreen<>(toolkit, viewer, commands, "Hologram settings", PropertyScreen.section(PropertyScreen.SCHEMA, section, Optional.empty()), NpcScreen.scope(toolkit, ids), List.of()));
        });
    }

    /** Line {@code index}: its text when every selected NPC has the same line there, else Mixed. */
    private void line(int index, List<List<JsonElement>> all) {
        boolean mixed = mixed(all, index);
        String text = all.stream().filter((List<JsonElement> lines) -> lines.size() > index).findFirst().map((List<JsonElement> lines) -> text(lines.get(index))).orElse("");
        List<String> lines = mixed ? List.of("&fNow: Mixed", "&7Typing sets it on all that have it") : List.of("&f" + text);
        button(FIRST_LINE + index, "minecraft:paper", title("Line " + (index + 1)), lore(lines, hints(mixed ? "type it for all" : "edit the text", "details", "")), (GuiModel.Click click) -> {
            if (click.right()) push(new LineScreen<>(toolkit, viewer, commands, index));
            else retext(this, FIRST_LINE + index, index);
        });
    }

    /** Whether the selected NPCs' line {@code index} differs, or some of them have none. */
    static boolean mixed(List<List<JsonElement>> all, int index) {
        return all.stream().map((List<JsonElement> lines) -> lines.size() > index ? Optional.of(text(lines.get(index))) : Optional.<String>empty()).distinct().count() > 1;
    }

    /** Row 3: which saved hologram shows, its height, and in Advanced the rest of its offset; changing them needs the GUI node. */
    private void saved(List<String> ids, Optional<JsonObject> saved) {
        opens(SAVED, "minecraft:armor_stand", "Saved hologram", lore(List.of("&7A hologram file from the library", "&fNow: " + saved.map(HologramScreen::id).orElse("none")),
                any(saved.isPresent() ? "pick another" : "pick one")), () -> new SavedHologramScreen<>(toolkit, viewer, commands));
        if (saved.isEmpty()) return;
        String id = id(saved.get());
        if (!holds(PermissionNodes.GUI)) { denied(HEIGHT, "Height", PermissionNodes.GUI); return; }
        List<Optional<double[]>> offsets = new ArrayList<>();
        for (String npc : ids) saved(NpcScreen.stored(toolkit, npc)).filter((JsonObject hologram) -> id(hologram).equals(id)).ifPresent((JsonObject hologram) -> offsets.add(offset(hologram)));
        offset(HEIGHT, "Height", 1, id, offsets);
        if (advanced()) {
            offset(X, "Offset X", 0, id, offsets);
            offset(Z, "Offset Z", 2, id, offsets);
            button(ABOVE, "minecraft:feather", title("Above the head"), lore(List.of("&7Clears the offset"), any("put it back")), (GuiModel.Click click) -> gui((List<String> chosen) ->
                    new ShopFiles(toolkit.npcs(), toolkit.documents()).offset(id, chosen, Optional.empty())));
        }
        confirm(REMOVE, "minecraft:shears", "Remove", "Stops showing " + id + " above " + named(ids), ids.size(), false,
                () -> gui((List<String> chosen) -> new ShopFiles(toolkit.npcs(), toolkit.documents()).unbindGui(DocumentKind.HOLOGRAM, id, chosen)));
    }

    /**
     * One axis of the saved hologram's offset, set on each NPC without touching its other axes. When the NPCs share it a
     * click raises it 0.1, a right-click lowers it and a sneak-click types it; when they differ it reads Mixed and any
     * click types one value for all of them, asking first. Blank puts it back above the head.
     */
    private void offset(int slot, String name, int axis, String id, List<Optional<double[]>> offsets) {
        List<String> values = offsets.stream().map((Optional<double[]> offset) -> offset.map((double[] at) -> number(at[axis]) + " blocks").orElse("above the head")).distinct().toList();
        boolean mixed = values.size() > 1;
        double now = offsets.stream().findFirst().flatMap((Optional<double[]> offset) -> offset).orElse(HEAD)[axis];
        String what = axis == 1 ? "Blocks above its feet" : "Blocks sideways from its feet";
        List<String> hints = mixed ? any("type one value for all") : hints(axis == 1 ? "raise it 0.1" : "add 0.1", axis == 1 ? "lower it 0.1" : "take 0.1", "type a value");
        button(slot, "minecraft:end_rod", title(name), lore(numberRow(what, mixed ? "Mixed" : values.isEmpty() ? "above the head" : values.getFirst()), hints), (GuiModel.Click click) -> {
            if (!mixed && !sneak(click)) { move(slot, id, axis, Optional.of(step(click, now, 0.1, -REACH, REACH)), false); return; }
            prompt(name, mixed || offsets.stream().allMatch(Optional::isEmpty) ? "" : number(now), (String typed) -> {
                if (typed.isBlank()) { move(slot, id, axis, Optional.empty(), mixed); return; }
                double value;
                try { value = Double.parseDouble(typed.strip()); }
                catch (NumberFormatException wrong) { refuse("'" + typed.strip() + "' is not a number of blocks"); return; }
                if (Math.abs(value) > REACH) { refuse("Keep it within " + number(REACH) + " blocks"); return; }
                move(slot, id, axis, Optional.of(value), mixed);
            });
        });
    }

    /** Sets one axis on all of them, or with empty puts the hologram back above the head; from Mixed it asks first. */
    private void move(int slot, String id, int axis, Optional<Double> value, boolean mixed) {
        String what = (value.isEmpty() ? "Puts " + id + " back above the head" : "Sets " + new String[]{"X", "Y", "Z"}[axis] + " to " + number(value.get()))
                + " on all " + ToolkitFiles.count(toolkit.selected(viewer.id()).size()) + (mixed ? "; they differ now" : "");
        ShopFiles files = new ShopFiles(toolkit.npcs(), toolkit.documents());
        ask(slot, what, toolkit.selected(viewer.id()).size(), mixed, () -> gui((List<String> ids) -> value.isPresent() ? files.offset(id, ids, axis, value.get()) : files.offset(id, ids, Optional.empty())));
    }

    @FunctionalInterface private interface GuiWork { String run(List<String> ids) throws IOException; }

    /** A change to the saved hologram on the selection, which needs the GUI permission. */
    private void gui(GuiWork work) {
        if (!allows(PermissionNodes.GUI)) return;
        chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles ignored) -> work.run(ids)));
    }

    /** The text prompt for line {@code index}: sets that line on every selected NPC that has one, keeping its margins; blank keeps it. */
    static <P, S> void retext(ToolScreen<P, S> screen, int slot, int index) {
        List<String> ids = screen.toolkit.selected(screen.viewer.id());
        if (ids.isEmpty()) return;
        List<List<JsonElement>> all = ids.stream().map((String id) -> lines(NpcScreen.stored(screen.toolkit, id))).toList();
        if (all.stream().allMatch((List<JsonElement> lines) -> lines.size() <= index)) return;
        boolean mixed = mixed(all, index);
        String initial = mixed ? "" : text(all.stream().filter((List<JsonElement> lines) -> lines.size() > index).findFirst().orElseThrow().get(index));
        screen.prompt("Line " + (index + 1), initial, (String typed) -> {
            if (typed.isBlank()) return;
            String what = "Sets line " + (index + 1) + " of " + screen.named(ids) + (mixed ? "; they differ now" : "");
            edit(screen, slot, what, mixed, (List<JsonElement> lines) -> {
                if (index >= lines.size()) return Optional.empty();
                lines.set(index, with(lines.get(index), typed));
                return Optional.of(lines);
            }, () -> {});
        });
    }

    /** Makes one line change on every selected NPC's own lines, asking once more above {@link #BULK} NPCs or when they differ, then runs {@code after}. */
    static <P, S> void edit(ToolScreen<P, S> screen, int slot, String what, boolean mixed, LineEdit edit, Runnable after) {
        screen.chosen().ifPresent((List<String> ids) -> screen.ask(slot, what, ids.size(), mixed, () -> {
            screen.toolkit.write(screen.viewer, (ToolkitFiles ignored) -> lines(screen.toolkit.npcs(), ids, edit));
            after.run();
        }));
    }

    /**
     * Applies the edit to each listed NPC's own {@code hologram.lines}, keeping its other hologram settings, in one
     * transaction; NPCs without the line it changes are skipped and counted. Runs on the file lane.
     */
    static String lines(NpcRepository npcs, List<String> ids, LineEdit edit) throws IOException {
        List<NpcRepository.Draft> drafts = new ArrayList<>();
        int skipped = 0;
        for (String id : ids) {
            NpcRepository.Draft draft = npcs.draft(id);
            JsonObject document = draft.document();
            List<JsonElement> before = lines(document);
            Optional<List<JsonElement>> after = edit.apply(new ArrayList<>(before));
            if (after.isEmpty()) { skipped++; continue; }
            if (after.get().equals(before)) continue;
            JsonObject hologram = document.has("hologram") && document.get("hologram").isJsonObject() ? document.getAsJsonObject("hologram") : new JsonObject();
            JsonArray array = new JsonArray();
            for (JsonElement line : after.get()) array.add(line.deepCopy());
            hologram.add("lines", array);
            if (array.isEmpty() && hologram.size() == 1) document.remove("hologram"); else document.add("hologram", hologram);
            drafts.add(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        }
        String skip = skipped == 0 ? "" : ", " + skipped + " skipped (no such line)";
        if (drafts.isEmpty()) return "Hologram text already set on " + ToolkitFiles.named(npcs, ids) + skip;
        return "Hologram text on " + ToolkitFiles.named(drafts) + skip + "; tx " + npcs.applyAll(drafts);
    }

    /** The {@code hologram.lines} of a definition, top first. */
    static List<JsonElement> lines(JsonObject document) {
        JsonElement hologram = document.get("hologram");
        if (hologram == null || !hologram.isJsonObject() || !hologram.getAsJsonObject().has("lines") || !hologram.getAsJsonObject().get("lines").isJsonArray()) return List.of();
        return hologram.getAsJsonObject().getAsJsonArray("lines").asList();
    }

    /** A line's text, whether it is a string or an object with margins. */
    static String text(JsonElement line) {
        if (line.isJsonPrimitive()) return line.getAsString();
        JsonElement text = line.isJsonObject() ? line.getAsJsonObject().get("text") : null;
        return text != null && text.isJsonPrimitive() ? text.getAsString() : "";
    }

    /** The line with new text, keeping its margins. */
    static JsonElement with(JsonElement line, String text) {
        if (!line.isJsonObject()) return new JsonPrimitive(text);
        JsonObject changed = line.getAsJsonObject().deepCopy();
        changed.addProperty("text", text);
        return changed;
    }

    /** The saved hologram the NPC shows through {@code animus:gui}, when it shows one. */
    static Optional<JsonObject> saved(JsonObject document) {
        return ShopFiles.module(document, GuiBinding.MODULE).filter((JsonObject module) -> module.has("hologram") && module.get("hologram").isJsonObject())
                .map((JsonObject module) -> module.getAsJsonObject("hologram"));
    }

    static String id(JsonObject hologram) { return hologram.has("id") && hologram.get("id").isJsonPrimitive() ? hologram.get("id").getAsString() : ""; }

    /** A saved hologram's offset from the feet, when it has one. */
    static Optional<double[]> offset(JsonObject hologram) {
        if (!hologram.has("offset") || !hologram.get("offset").isJsonArray() || hologram.getAsJsonArray("offset").size() != 3) return Optional.empty();
        JsonArray vector = hologram.getAsJsonArray("offset");
        return Optional.of(new double[]{vector.get(0).getAsDouble(), vector.get(1).getAsDouble(), vector.get(2).getAsDouble()});
    }

    /** {@code 2.4}, or {@code 3} for a whole number. */
    static String number(double value) { return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value); }

    /** One line: edit its text, move it, or remove it, on every selected NPC that has that line. */
    static final class LineScreen<P, S> extends ToolScreen<P, S> {
        static final int HEADER = 4, EDIT = 20, UP = 21, DOWN = 23, REMOVE = 35;
        private int index;

        LineScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, int index) {
            super(toolkit, viewer, commands);
            this.index = index;
        }

        @Override String title() { return "Line " + (index + 1); }
        @Override String node() { return PermissionNodes.NPC_EDIT; }
        @Override int rows() { return 6; }

        @Override void draw() {
            back();
            help("Edit the text, move the line up or down, or remove it. & colour codes such as &a are drawn in colour.", "04-npc-definitions", "names-and-text-lines");
            List<String> ids = toolkit.selected(viewer.id());
            List<List<JsonElement>> all = ids.stream().map((String id) -> lines(NpcScreen.stored(toolkit, id))).toList();
            int at = index, longest = all.stream().mapToInt(List::size).max().orElse(0);
            if (at >= longest) { icon(HEADER, "minecraft:paper", "&7This line is gone", List.of("&7Go back to see the lines"), Optional.empty()); return; }
            boolean mixed = mixed(all, at);
            String text = all.stream().filter((List<JsonElement> lines) -> lines.size() > at).findFirst().map((List<JsonElement> lines) -> text(lines.get(at))).orElse("");
            icon(HEADER, "minecraft:paper", title(title()), List.of(mixed ? "&fNow: Mixed" : "&f" + text), Optional.empty());
            button(EDIT, "minecraft:writable_book", title("Edit text"), any(mixed ? "type it for all" : "type the text"), (GuiModel.Click click) -> retext(this, EDIT, at));
            if (at > 0) button(UP, "minecraft:arrow", title("Move up"), any("move it up"), (GuiModel.Click click) -> move(UP, -1));
            if (at < longest - 1) button(DOWN, "minecraft:arrow", title("Move down"), any("move it down"), (GuiModel.Click click) -> move(DOWN, 1));
            confirm(REMOVE, "minecraft:lava_bucket", "Remove", "Removes line " + (at + 1) + " from " + named(ids) + "; Undo in chat brings it back", ids.size(), true, () -> {
                chosen().ifPresent((List<String> chosen) -> toolkit.write(viewer, (ToolkitFiles ignored) -> lines(toolkit.npcs(), chosen, (List<JsonElement> lines) -> {
                    if (at >= lines.size()) return Optional.empty();
                    lines.remove(at);
                    return Optional.of(lines);
                })));
                viewer.back();
            });
        }

        /** Swaps the line with its neighbour on every NPC that has both; the screen follows it. */
        private void move(int slot, int by) {
            int from = index, to = from + by;
            edit(this, slot, "Moves line " + (from + 1) + " of " + named(toolkit.selected(viewer.id())), false, (List<JsonElement> lines) -> {
                if (to < 0 || to >= lines.size()) return Optional.empty();
                lines.add(to, lines.remove(from));
                return Optional.of(lines);
            }, () -> index = to);
        }
    }
}
