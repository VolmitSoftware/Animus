package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.behavior.conversation.ConversationDocument;
import art.arcane.animus.core.behavior.conversation.ConversationDocument.Choices;
import art.arcane.animus.core.behavior.conversation.ConversationDocument.Line;
import art.arcane.animus.core.behavior.conversation.ConversationDocument.Option;
import art.arcane.animus.core.behavior.conversation.ConversationDocument.Step;
import art.arcane.animus.core.behavior.conversation.ConversationDocument.StartKind;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * A conversation as a linear editor, one block at a time: its lines and other steps in order, then the answers of its
 * question, each leading to the block it opens. Edits stay in this window until Save writes the behavior in one
 * transaction; condition, action and go-to steps are kept as written and only move or go. Lines may use
 * {@code {npc.name}} and {@code {player_name}}.
 */
final class ConversationScreen<P, S> extends ToolScreen<P, S> {
    static final int START = 0, RADIUS = 1, BLOCK = 4, ATTACH = 7, SAVE = 8, STEPS = 9, STEP_SLOTS = 27, OPTIONS = 36, ADD_CHOICE = 44, ADD_LINE = 46;
    private static final int WRAP = 36, TITLE = 30;
    private BehaviorFiles.Loaded loaded;
    private ConversationDocument working;
    private String block;
    private final Deque<String> trail = new ArrayDeque<>();
    /** The destructive click waiting for a second click, such as {@code step 3} or {@code leave}. */
    private String armed = "";

    ConversationScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, BehaviorFiles.Loaded loaded) {
        super(toolkit, viewer, commands);
        this.loaded = loaded;
        working = loaded.conversation();
        block = working.entry;
    }

    /** The behavior id on its entry block; any other block adds its name, or shows only the block when both do not fit. */
    @Override String title() {
        if (block.equals(working.entry)) return loaded.id();
        String both = loaded.id() + " > " + block;
        return both.length() <= TITLE ? both : ValueScreen.clip("Block " + block, TITLE);
    }
    @Override int rows() { return 6; }

    private boolean unsaved() { return !written().equals(loaded.behavior()); }

    private JsonObject written() { return working.write(loaded.behavior()); }

    @Override void draw() {
        if (!working.blocks.containsKey(block)) { block = working.entry; trail.clear(); }
        boolean unsaved = unsaved();
        start();
        info(unsaved);
        List<String> selected = toolkit.selected(viewer.id());
        guarded(ATTACH, "minecraft:lead", "Attach to selection", "Adds " + loaded.id() + " to each NPC's behaviors", selected.size(), false,
                () -> chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles ignored) -> BehaviorScreen.files(toolkit).attach(loaded.id(), ids, true))));
        button(SAVE, unsaved ? "minecraft:emerald" : "minecraft:emerald_block", title(unsaved ? "Save" : "Saved"),
                lore(List.of("&7" + loaded.path(), unsaved ? "&eUnsaved changes" : "&7Nothing to save"), any("save and reload behaviors")), (GuiModel.Click click) -> { armed = ""; save(); });
        steps();
        options();
        button(BACK, "minecraft:oak_door", title(armed.equals("leave") ? "&cClick again to leave unsaved" : trail.isEmpty() ? "Back" : "Back to " + trail.peek()),
                any(trail.isEmpty() ? (unsaved ? "leave; asks first, edits are unsaved" : "previous screen") : "parent block"), (GuiModel.Click click) -> back(unsaved));
        button(ADD_LINE, "minecraft:paper", title("Add line"), lore(List.of("&7At the end of " + block, "&7Use {npc.name} and {player_name}"), any("type it")),
                (GuiModel.Click click) -> { armed = ""; typeLine(working.blocks.get(block).steps.size()); });
    }

    private void start() {
        StartKind kind = working.start.kind;
        button(START, "minecraft:lever", title("Starts on " + kind.label()), lore(List.of("&7What makes the NPC begin"), List.of("&fLeft &7next start", "&fRight &7previous start")),
                (GuiModel.Click click) -> { armed = ""; working.start(cycle(click, List.of(StartKind.values()), kind)); });
        if (kind == StartKind.PROXIMITY)
            button(RADIUS, "minecraft:compass", title("Within " + format(working.start.radius) + " blocks"), lore(List.of("&7How near a player walks"), hints("1 block wider", "1 block narrower", "10 blocks")),
                    (GuiModel.Click click) -> { armed = ""; working.start.radius = step(click, working.start.radius, 1, 1, 64); });
    }

    private void info(boolean unsaved) {
        int reaching = working.incoming().getOrDefault(block, 0);
        List<String> lines = new ArrayList<>(List.of(block.equals(working.entry) ? "&7Entry block, run by the start" : "&7Reached from &f" + reaching + "&7 answers or go-tos",
                "&f" + working.blocks.get(block).steps.size() + "&7 steps", unsaved ? "&eUnsaved changes" : "&7Saved"));
        List<String> errors = working.errors();
        for (String error : errors.subList(0, Math.min(3, errors.size()))) lines.add("&c" + error);
        if (errors.size() > 3) lines.add("&c" + (errors.size() - 3) + " more problems");
        icon(BLOCK, "minecraft:book", title("Block " + block), lines, Optional.empty());
    }

    private void steps() {
        List<Step> steps = working.blocks.get(block).steps;
        Optional<Choices> question = working.question(block);
        int shown = steps.size() > STEP_SLOTS ? STEP_SLOTS - 1 : steps.size();
        for (int index = 0; index < shown; index++) {
            Step step = steps.get(index);
            step(STEPS + index, index, step, question.map((Choices asked) -> asked == step).orElse(false));
        }
        if (shown < steps.size())
            icon(STEPS + shown, "minecraft:paper", "&7" + (steps.size() - shown) + " more steps", List.of("&7Edit long blocks in the web editor"), Optional.empty());
    }

    private void step(int slot, int index, Step step, boolean editedQuestion) {
        String key = "step " + index;
        boolean waiting = armed.equals(key);
        List<String> lines = new ArrayList<>();
        String icon, name;
        switch (step) {
            case Line line -> {
                icon = "minecraft:paper";
                name = "Line " + (index + 1);
                lines.addAll(wrap(line.text));
                if (line.delayTicks != null) lines.add("&7After " + line.delayTicks + " ticks");
                if (line.sound != null) lines.add("&7Sound " + line.sound);
                if (line.animation != null) lines.add("&7Animation " + line.animation);
            }
            case Choices choices -> {
                icon = "minecraft:oak_sign";
                name = editedQuestion ? "Question" : "Question " + choices.dialogue;
                lines.addAll(wrap(choices.prompt));
                lines.add(editedQuestion ? "&7Its answers are in the row below" : "&7A second question; answers edit in the web editor");
            }
            case ConversationDocument.Branch branch -> {
                icon = "minecraft:comparator";
                name = "Condition " + (branch.expression != null ? branch.expression : String.valueOf(branch.capability));
                lines.add("&7Kept as written; edit it in the web editor");
            }
            case ConversationDocument.Action action -> {
                icon = "minecraft:redstone";
                name = "Action " + action.capability;
                lines.add("&7Kept as written; edit it in the web editor");
            }
            case ConversationDocument.Goto jump -> {
                icon = "minecraft:ender_pearl";
                name = "Go to " + jump.target;
                lines.add("&7Kept as written; must stay last");
            }
            case ConversationDocument.End end -> {
                icon = "minecraft:barrier";
                name = "End";
                lines.add("&7Kept as written; must stay last");
            }
        }
        boolean editable = step instanceof Line || editedQuestion;
        List<String> clicks = List.of(editable ? "&fLeft &7edit the text" : "&fLeft &7nothing; edit it in the web editor", "&fRight &7insert a line after",
                "&fSneak-left &7move up", waiting ? "&cSneak-right again to delete" : "&fSneak-right &7delete" + (editedQuestion ? " with its answers" : ""));
        button(slot, icon, title(waiting ? "&cDelete " + name + "?" : name), lore(lines, clicks), (GuiModel.Click click) -> {
            List<Step> steps = working.blocks.get(block).steps;
            if (click == GuiModel.Click.SHIFT_RIGHT) {
                if (!armed.equals(key)) { armed = key; return; }
                armed = "";
                if (step instanceof Choices choices && editedQuestion) while (!choices.options.isEmpty()) working.removeOption(choices, 0);
                steps.remove(step);
                return;
            }
            armed = "";
            if (click == GuiModel.Click.SHIFT_LEFT) { if (index > 0) steps.add(index - 1, steps.remove(index)); }
            else if (right(click)) typeLine(index + 1);
            else if (step instanceof Line line) prompt("Line", line.text, (String text) -> changed(text, () -> line.text = text));
            else if (step instanceof Choices choices && editedQuestion) prompt("Question", choices.prompt, (String text) -> changed(text, () -> choices.prompt = text));
        });
    }

    private void options() {
        Optional<Choices> question = working.question(block);
        List<Option> options = question.map((Choices choices) -> choices.options).orElse(List.of());
        for (int index = 0; index < options.size() && index < ConversationDocument.MAXIMUM_OPTIONS; index++) option(OPTIONS + index, question.get(), index);
        if (options.size() < ConversationDocument.MAXIMUM_OPTIONS)
            button(ADD_CHOICE, "minecraft:lime_dye", title("Add choice"), lore(List.of(question.isEmpty() ? "&7Adds a question to this block" : "&7" + options.size() + " of " + ConversationDocument.MAXIMUM_OPTIONS + " answers",
                    "&7Leads to a new empty block"), any("type the label")), (GuiModel.Click click) -> {
                armed = "";
                prompt("Choice label", "", (String text) -> changed(text, () -> working.addOption(block, text, "Choose one.")));
            });
    }

    private void option(int slot, Choices choices, int index) {
        Option option = choices.options.get(index);
        String key = "option " + index;
        boolean waiting = armed.equals(key);
        List<String> lines = new ArrayList<>(wrap(option.text));
        lines.add(option.next == null ? "&7Ends the conversation" : "&7Leads to block &f" + option.next);
        List<String> clicks = List.of("&fLeft &7edit the label", "&fRight &7open its block" + (option.next == null ? ", made empty" : ""),
                waiting ? "&cSneak again to delete it and blocks only it reaches" : "&fSneak &7delete it and blocks only it reaches");
        button(slot, "minecraft:light_blue_dye", title(waiting ? "&cDelete " + option.id + "?" : "Choice: " + clip(option.text)), lore(lines, clicks), (GuiModel.Click click) -> {
            if (sneak(click)) {
                if (!armed.equals(key)) { armed = key; return; }
                armed = "";
                List<String> removed = working.removeOption(choices, index);
                if (!removed.isEmpty()) toolkit.say(viewer.player(), ChatLine.reply("Removed blocks " + String.join(", ", removed) + "; Save to keep it"));
                return;
            }
            armed = "";
            if (right(click)) { trail.push(block); block = working.followUp(choices, option); }
            else prompt("Choice label", option.text, (String text) -> changed(text, () -> option.text = text));
        });
    }

    private void back(boolean unsaved) {
        if (!trail.isEmpty()) {
            armed = "";
            while (!trail.isEmpty() && !working.blocks.containsKey(trail.peek())) trail.pop();
            block = trail.isEmpty() ? working.entry : trail.pop();
            return;
        }
        if (unsaved && !armed.equals("leave")) { armed = "leave"; return; }
        armed = "";
        viewer.back();
    }

    private void typeLine(int at) {
        prompt("New line", "", (String text) -> changed(text, () -> {
            List<Step> steps = working.blocks.get(block).steps;
            steps.add(Math.min(at, steps.size()), new Line(text));
        }));
    }

    private void save() {
        List<String> errors = working.errors();
        if (!errors.isEmpty()) {
            toolkit.say(viewer.player(), ChatLine.error("Not saved: " + String.join("; ", errors.subList(0, Math.min(5, errors.size())))));
            return;
        }
        JsonObject next = written();
        BehaviorFiles.Loaded from = loaded;
        toolkit.later(viewer, (ToolkitFiles ignored) -> BehaviorScreen.files(toolkit).save(from, next), (BehaviorFiles.Saved saved) -> {
            boolean changed = saved.line().contains("; tx ");
            toolkit.say(viewer.player(), ChatLine.parse(changed ? Palette.success(saved.line()) : Palette.reply(saved.line())));
            if (changed) viewer.host().command(Optional.of(viewer.player()), "animus behaviors reload");
            loaded = saved.loaded();
            working = loaded.conversation();
            viewer.refresh();
        });
    }

    /** Applies a typed answer and redraws; an answer that is only spaces changes nothing and says so. */
    private void changed(String text, Runnable edit) {
        if (text.isBlank()) { toolkit.say(viewer.player(), ChatLine.error("Empty text; nothing changed")); return; }
        edit.run();
        viewer.refresh();
    }

    static List<String> wrap(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (!line.isEmpty() && line.length() + word.length() + 1 > WRAP) { lines.add("&f" + line); line.setLength(0); }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) lines.add("&f" + line);
        if (lines.size() > 6) { List<String> cut = new ArrayList<>(lines.subList(0, 5)); cut.add("&7..."); return cut; }
        return lines;
    }

    private static String clip(String text) { return text.length() > 24 ? text.substring(0, 23) + "..." : text; }

    private static String format(double value) { return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value); }
}
