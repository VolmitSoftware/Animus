package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.behavior.conversation.ConversationDocument;
import art.arcane.animus.core.behavior.conversation.ConversationDocument.Choices;
import art.arcane.animus.core.behavior.conversation.ConversationDocument.Line;
import art.arcane.animus.core.behavior.conversation.ConversationDocument.Option;
import art.arcane.animus.core.behavior.conversation.ConversationDocument.StartKind;
import art.arcane.animus.core.behavior.conversation.ConversationDocument.Step;
import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * A conversation as a linear editor, one block at a time: its lines and other steps in order, then the answers of its
 * question, each leading to the block it opens. The question stays the last step of its block, because the runtime shows
 * it and runs on: a line after it would play before any answer. A new answer comes with its own block holding one line.
 * Changes stay in this window until Save writes the behavior in one transaction; condition, action and go-to steps are
 * kept as written and only move or go. A right-click on a step or an answer opens {@link ConversationStepScreen}. Lines
 * may use {@code {npc.name}} and {@code {player_name}}.
 */
final class ConversationScreen<P, S> extends ToolScreen<P, S> {
    static final int START = 0, RADIUS = 1, BLOCK = 4, ADD_LINE = 8, STEPS = 9, STEP_SLOTS = 27, OPTIONS = 36, ADD_CHOICE = 44;
    private static final int WRAP = 36, TITLE = 30;
    /** The line a new answer's block starts with, for the author to change. */
    static final String ANSWER_PROMPT = "NPC's answer";
    static final String QUESTION_LAST = "The question stays last";
    private BehaviorFiles.Loaded loaded;
    private ConversationDocument working;
    private String block;
    private final Deque<String> trail = new ArrayDeque<>();
    /** The page of a block with more steps than {@link #STEP_SLOTS}. */
    private int stepPage;
    /** Changes since the last save or discard; the draft counts at least one while the file would change. */
    private int edits;
    /** Counts every change and discard, so a save that returns can tell whether the player changed more meanwhile. */
    private int version;
    private boolean saving;

    ConversationScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, BehaviorFiles.Loaded loaded) {
        super(toolkit, viewer, commands);
        this.loaded = loaded;
        working = loaded.conversation();
        block = working.entry;
    }

    @Override String node() { return PermissionNodes.BEHAVIORS; }

    /**
     * The behavior id on its entry block, by the name of the NPC it was made for when one has the id it names; any other
     * block adds its name, or shows only the block when both do not fit.
     */
    @Override String title() {
        String label = label(loaded.id()), name = toolkit.npcs().find(label).map(NpcsScreen::name).orElse(label);
        if (block.equals(working.entry)) return ValueScreen.clip("Talk: " + name, TITLE);
        String both = name + " > " + block;
        return both.length() <= TITLE ? both : ValueScreen.clip("Block " + block, TITLE);
    }

    /** The name a window title shows for a behavior id: {@code town:greeter-talk} and {@code greeter:talk} both read {@code greeter}. */
    static String label(String id) {
        int colon = id.indexOf(':');
        String space = colon < 0 ? "" : id.substring(0, colon), path = id.substring(colon + 1);
        if (path.equals("talk") && !space.isEmpty()) return space;
        return path.endsWith("-talk") && path.length() > 5 ? path.substring(0, path.length() - 5) : path;
    }
    @Override int rows() { return 6; }

    ConversationDocument working() { return working; }
    String block() { return block; }

    private boolean unsaved() { return !working.write(loaded.behavior()).equals(loaded.behavior()); }

    @Override void draw() {
        if (!working.blocks.containsKey(block)) { block = working.entry; trail.clear(); stepPage = 0; }
        start();
        info();
        button(ADD_LINE, "minecraft:paper", title("Add line"), lore(advanced() ? List.of("&7{npc.name} and {player_name} are filled in") : List.of("&7Something the NPC says"), any("type it")),
                (GuiModel.Click click) -> prompt("New line", "", (String text) -> insert(working.blocks.get(block).steps.size(), text)));
        steps();
        options();
        draft(unsaved() ? Math.max(1, edits) : 0, 1, trail.isEmpty(), this::save, this::discard);
        if (!trail.isEmpty())
            button(bottom(BACK), "minecraft:oak_door", title("Back"), lore(List.of("&7To block " + trail.peek()), any("parent block")), (GuiModel.Click click) -> up());
        help("Each block is lines the NPC says, then answers that lead to other blocks. Changes stay here until you click Save.", "21-in-game-toolkit", "conversations");
    }

    private void start() {
        StartKind kind = working.start.kind;
        button(START, "minecraft:lever", title("Starts on"), lore(List.of("&fNow: " + kind.label()), hints("next", "previous", "")),
                (GuiModel.Click click) -> { working.start(cycle(click, List.of(StartKind.values()), kind)); edited(); });
        if (kind == StartKind.PROXIMITY)
            button(RADIUS, "minecraft:compass", title("Distance"), lore(numberRow("How near a player walks", number(working.start.radius) + " blocks"), hints("1 more", "1 less", "10 at a time")),
                    (GuiModel.Click click) -> { working.start.radius = step(click, working.start.radius, 1, 1, 64); edited(); });
    }

    private void info() {
        int reaching = working.incoming().getOrDefault(block, 0);
        boolean entry = block.equals(working.entry);
        List<String> lines = new ArrayList<>(List.of(entry ? "&7Runs first, when it starts" : "&7Reached from &f" + reaching + "&7 answers or go-tos"));
        if (entry && shopToo()) lines.add("&eAlso has a shop: players get both");
        List<String> errors = new ArrayList<>(working.errors());
        if (afterQuestion()) errors.addFirst("Lines after the question play before any answer");
        for (String error : errors.subList(0, Math.min(2, errors.size()))) lines.add("&c" + error);
        if (errors.size() > 2) lines.add("&c" + (errors.size() - 2) + " more problems");
        icon(BLOCK, "minecraft:book", title(entry ? "Start" : "Block " + block), lines, Optional.empty());
    }

    /**
     * Whether a selected NPC that has this conversation also opens a shop on the right-click that starts it: the
     * runtime runs both, the conversation first, so one click talks and opens the shop.
     */
    private boolean shopToo() {
        if (working.start.kind != StartKind.CLICK) return false;
        for (String id : toolkit.selected(viewer.id())) {
            JsonObject npc = NpcScreen.stored(toolkit, id);
            JsonElement behaviors = npc.get("behaviors");
            boolean has = behaviors != null && behaviors.isJsonArray() && behaviors.getAsJsonArray().contains(new JsonPrimitive(loaded.id()));
            if (has && ShopFiles.opensOnRightClick(npc)) return true;
        }
        return false;
    }

    /** Whether the shown block runs anything after its question, as files written before the question stayed last do. */
    private boolean afterQuestion() {
        List<Step> steps = working.blocks.get(block).steps;
        return working.question(block).map((Choices asked) -> steps.indexOf(asked) < steps.size() - 1).orElse(false);
    }

    private void steps() {
        List<Step> steps = working.blocks.get(block).steps;
        Optional<Choices> question = working.question(block);
        int pages = Math.max(1, (steps.size() + STEP_SLOTS - 1) / STEP_SLOTS);
        stepPage = Math.clamp(stepPage, 0, pages - 1);
        if (steps.isEmpty()) icon(22, "minecraft:paper", "&7No lines yet. Click Add line", List.of(), Optional.empty());
        for (int index = stepPage * STEP_SLOTS, slot = STEPS; index < steps.size() && slot < STEPS + STEP_SLOTS; index++, slot++) {
            Step step = steps.get(index);
            step(slot, index, step, question.map((Choices asked) -> asked == step).orElse(false));
        }
        if (stepPage > 0) button(bottom(PREVIOUS), "minecraft:arrow", title("Previous page"), any("page " + stepPage), (GuiModel.Click click) -> stepPage--);
        if (stepPage < pages - 1) button(bottom(NEXT), "minecraft:arrow", title("Next page"), any("page " + (stepPage + 2)), (GuiModel.Click click) -> stepPage++);
        if (pages > 1) icon(bottom(PAGE), "minecraft:paper", "&7Page &f" + (stepPage + 1) + "&7 of &f" + pages, List.of("&7" + steps.size() + " steps"), Optional.empty());
    }

    private void step(int slot, int index, Step step, boolean question) {
        List<String> lines = new ArrayList<>();
        String icon;
        switch (step) {
            case Line line -> {
                icon = "minecraft:paper";
                lines.addAll(said(line.text));
                if (line.delayTicks != null) lines.add("&7After " + seconds(line.delayTicks));
                if (line.sound != null) lines.add(advanced() ? "&8Sound " + line.sound : "&7With a sound");
                if (line.animation != null) lines.add(advanced() ? "&8Animation " + line.animation : "&7With an animation");
            }
            case Choices choices -> {
                icon = "minecraft:oak_sign";
                lines.addAll(said(choices.prompt));
                lines.add(question ? "&7Its answers are in the row below" : "&7Its answers edit in the web editor");
            }
            case ConversationDocument.Branch branch -> {
                icon = "minecraft:comparator";
                if (advanced()) lines.add("&8" + (branch.expression != null ? branch.expression : String.valueOf(branch.capability)));
                lines.add("&7Edit it in the web editor");
            }
            case ConversationDocument.Action action -> {
                icon = "minecraft:redstone";
                if (advanced()) lines.add("&8" + action.capability);
                lines.add("&7Edit it in the web editor");
            }
            case ConversationDocument.Goto jump -> {
                icon = "minecraft:ender_pearl";
                lines.add("&7Must stay last");
            }
            case ConversationDocument.End end -> {
                icon = "minecraft:barrier";
                lines.add("&7Must stay last");
            }
        }
        boolean editable = step instanceof Line || question;
        button(slot, icon, title(name(step, index)), lore(lines, editable ? hints("edit the text", "details", "") : any("details")), (GuiModel.Click click) -> {
            if (editable && !right(click)) prompt(step instanceof Line ? "Line" : "Question", text(step), (String typed) -> retext(step, typed));
            else push(new ConversationStepScreen<>(toolkit, viewer, commands, this, step));
        });
    }

    private void options() {
        Optional<Choices> question = working.question(block);
        List<Option> options = question.map((Choices choices) -> choices.options).orElse(List.of());
        for (int index = 0; index < options.size() && index < ConversationDocument.MAXIMUM_OPTIONS; index++) option(OPTIONS + index, question.get(), options.get(index));
        if (options.size() < ConversationDocument.MAXIMUM_OPTIONS)
            button(ADD_CHOICE, "minecraft:lime_dye", title("Add choice"), lore(List.of(question.isEmpty() ? "&7Adds a question to this block" : "&7An answer players can pick",
                    "&fNow: " + options.size() + " of " + ConversationDocument.MAXIMUM_OPTIONS), any("type the label")),
                    (GuiModel.Click click) -> prompt("Choice label", "", (String label) -> {
                        if (label.isBlank()) { refuse("Empty text; nothing changed"); return; }
                        prompt(ANSWER_PROMPT, "", "What it says when the choice is picked", (String said) -> changed(said, () -> {
                            String answer = working.addOption(block, label, "Choose one.");
                            working.blocks.get(answer).steps.add(new Line(said.strip()));
                        }));
                    }));
    }

    /**
     * A choice: its full text when the title cuts it, then what follows: in Simple the NPC's first answer line, in
     * Advanced the block it leads to.
     */
    private void option(int slot, Choices choices, Option option) {
        List<String> lines = new ArrayList<>(clip(option.text).equals(option.text) ? List.of() : said(option.text));
        if (option.next == null) lines.add("&7Ends the conversation");
        else if (advanced()) lines.add("&fLeads to: " + option.next);
        else lines.add("&fThen: " + clip(firstLine(option.next).orElse("the block it opens")));
        button(slot, "minecraft:light_blue_dye", title("Choice: " + clip(option.text)), lore(lines, hints(advanced() ? "open its block" : "follow it", "details", "")), (GuiModel.Click click) -> {
            if (right(click)) { push(new ConversationStepScreen<>(toolkit, viewer, commands, this, choices, option)); return; }
            if (option.next == null) edited();
            trail.push(block);
            block = working.followUp(choices, option);
            stepPage = 0;
        });
    }

    /** The first line the NPC says in a block, if it says one. */
    private Optional<String> firstLine(String id) {
        ConversationDocument.Block found = working.blocks.get(id);
        if (found == null) return Optional.empty();
        return found.steps.stream().filter((Step step) -> step instanceof Line).map((Step step) -> ((Line) step).text).findFirst();
    }

    /** Back from a block reached through an answer: to the block it came from, skipping blocks deleted meanwhile. */
    private void up() {
        while (!trail.isEmpty() && !working.blocks.containsKey(trail.peek())) trail.pop();
        block = trail.isEmpty() ? working.entry : trail.pop();
        stepPage = 0;
    }

    /**
     * Writes the conversation as it is at the click. Changes made while the file lane works stay in the window on top of
     * the saved file, still counted as unsaved, instead of being replaced by what was saved.
     */
    private void save() {
        if (saving) { refuse("Still saving; wait a moment"); return; }
        List<String> errors = working.errors();
        if (!errors.isEmpty()) {
            refuse("Not saved: " + String.join("; ", errors.subList(0, Math.min(5, errors.size()))));
            return;
        }
        JsonObject next = working.write(loaded.behavior());
        BehaviorFiles.Loaded from = loaded;
        int clicked = version, counted = edits;
        saving = true;
        toolkit.later(viewer, (ToolkitFiles ignored) -> BehaviorScreen.files(toolkit).save(from, next), (BehaviorFiles.Saved saved) -> {
            saving = false;
            boolean changed = saved.line().contains("; tx ");
            toolkit.say(viewer.player(), ChatLine.parse(changed ? Palette.success(saved.line()) : Palette.reply(saved.line())));
            if (changed) viewer.host().command(Optional.empty(), BehaviorScreen.RELOAD_COMMAND);
            loaded = saved.loaded();
            if (version == clicked) {
                working = loaded.conversation();
                edits = 0;
            } else edits = Math.max(0, edits - counted);
            viewer.refresh();
        }, (String reason) -> saving = false);
    }

    /** Drops every change since the last save: the conversation is read again from the file as it was loaded. */
    private void discard() {
        working = ((ConversationDocument.Fits) ConversationDocument.read(loaded.behavior())).conversation();
        edits = 0;
        version++;
    }

    private void edited() {
        edits++;
        version++;
    }

    /** Applies a typed answer; one that is only spaces changes nothing and says so. False when nothing changed. */
    boolean changed(String text, Runnable edit) {
        if (text.isBlank()) { refuse("Empty text; nothing changed"); return false; }
        edit.run();
        edited();
        return true;
    }

    /** A new line at {@code at} in the shown block, but never after its question, then the page that shows it. */
    boolean insert(int at, String text) {
        List<Step> steps = working.blocks.get(block).steps;
        int index = Math.min(at, working.question(block).map(steps::indexOf).orElse(steps.size()));
        if (!changed(text, () -> steps.add(index, new Line(text)))) return false;
        stepPage = index / STEP_SLOTS;
        return true;
    }

    /** The text of a line, or the prompt of a question. */
    static String text(Step step) { return step instanceof Line line ? line.text : step instanceof Choices choices ? choices.prompt : ""; }

    boolean retext(Step step, String text) {
        return changed(text, () -> {
            if (step instanceof Line line) line.text = text;
            else if (step instanceof Choices choices) choices.prompt = text;
        });
    }

    /** Moves a step one place up ({@code by} -1) or down (1) in the shown block, unless {@link #unmovable} says why not. */
    void move(Step step, int by) {
        if (unmovable(step, by).isPresent()) return;
        List<Step> steps = working.blocks.get(block).steps;
        int from = steps.indexOf(step);
        steps.add(from + by, steps.remove(from));
        edited();
    }

    /** Why the step cannot move one place up or down in the shown block: an end of the block, or the question, which stays last. */
    Optional<String> unmovable(Step step, int by) {
        List<Step> steps = working.blocks.get(block).steps;
        int from = steps.indexOf(step), to = from + by;
        Optional<Choices> question = working.question(block);
        if (question.isPresent() && (question.get() == step || steps.get(Math.clamp(to, 0, steps.size() - 1)) == question.get())) return Optional.of(QUESTION_LAST);
        if (from < 0 || to < 0) return Optional.of("Already first");
        if (to >= steps.size()) return Optional.of("Already last");
        return Optional.empty();
    }

    /** Deletes a step; the block's own question takes its answers, and the blocks only they reached, with it. */
    void delete(Step step) {
        if (step instanceof Choices choices && working.question(block).map((Choices asked) -> asked == choices).orElse(false))
            while (!choices.options.isEmpty()) working.removeOption(choices, 0);
        working.blocks.get(block).steps.remove(step);
        edited();
    }

    /** Deletes an answer and the blocks only it reached, naming them in chat. */
    void delete(Choices choices, Option option) {
        int index = choices.options.indexOf(option);
        if (index < 0) return;
        List<String> removed = working.removeOption(choices, index);
        if (!removed.isEmpty()) toolkit.say(viewer.player(), ChatLine.reply("Removed blocks " + String.join(", ", removed) + "; Discard brings them back"));
        edited();
    }

    /** {@code Line 2}, {@code Question}, {@code Condition}, {@code Go to shop}: what a step row and its menu are called. */
    static String name(Step step, int index) {
        return switch (step) {
            case Line line -> "Line " + (index + 1);
            case Choices choices -> "Question";
            case ConversationDocument.Branch branch -> "Condition";
            case ConversationDocument.Action action -> "Action";
            case ConversationDocument.Goto jump -> "Go to " + jump.target;
            case ConversationDocument.End end -> "End";
        };
    }

    /** Typed text cut into white lore lines, six at most. */
    static List<String> said(String text) {
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

    /** {@code 1 second}, {@code 1.5 seconds}: a delay in ticks as people read it. */
    static String seconds(int ticks) {
        double value = ticks / 20d;
        return number(value) + (value == 1 ? " second" : " seconds");
    }

    static String clip(String text) { return text.length() > 24 ? text.substring(0, 23) + "..." : text; }

    private static String number(double value) { return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value); }
}
