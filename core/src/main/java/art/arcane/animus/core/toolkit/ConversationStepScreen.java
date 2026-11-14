package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.behavior.conversation.ConversationDocument.Choices;
import art.arcane.animus.core.behavior.conversation.ConversationDocument.Line;
import art.arcane.animus.core.behavior.conversation.ConversationDocument.Option;
import art.arcane.animus.core.behavior.conversation.ConversationDocument.Step;
import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The details of one conversation step or answer, from a right-click in {@link ConversationScreen}: named buttons for
 * what a click on the row does not do. Changes go into the editor's unsaved changes; Save there writes them.
 */
final class ConversationStepScreen<P, S> extends ToolScreen<P, S> {
    static final int HEADER = 4, EDIT = 10, INSERT = 11, UP = 12, DOWN = 13, DELETE = 16;
    private final ConversationScreen<P, S> editor;
    private final String block;
    private final Optional<Step> step;
    private final Optional<Choices> choices;
    private final Optional<Option> option;

    /** The details of a step of the editor's shown block. */
    ConversationStepScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, ConversationScreen<P, S> editor, Step step) {
        this(toolkit, viewer, commands, editor, Optional.of(step), Optional.empty(), Optional.empty());
    }

    /** The details of an answer of the shown block's question. */
    ConversationStepScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, ConversationScreen<P, S> editor, Choices choices, Option option) {
        this(toolkit, viewer, commands, editor, Optional.empty(), Optional.of(choices), Optional.of(option));
    }

    private ConversationStepScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, ConversationScreen<P, S> editor,
                                   Optional<Step> step, Optional<Choices> choices, Optional<Option> option) {
        super(toolkit, viewer, commands);
        this.editor = editor;
        this.block = editor.block();
        this.step = step;
        this.choices = choices;
        this.option = option;
    }

    @Override String title() { return option.map((Option answer) -> "Choice: " + ConversationScreen.clip(answer.text)).orElseGet(() -> ConversationScreen.name(step.orElseThrow(), index())); }
    @Override String node() { return PermissionNodes.BEHAVIORS; }
    @Override int rows() { return 3; }

    private List<Step> steps() { return editor.working().blocks.containsKey(block) ? editor.working().blocks.get(block).steps : List.of(); }

    private int index() { return step.map((Step shown) -> steps().indexOf(shown)).orElse(-1); }

    @Override void draw() {
        back();
        help("Change one step of the conversation. Changes stay in the editor until you click Save there.", "21-in-game-toolkit", "conversations");
        if (option.isPresent()) { answer(choices.orElseThrow(), option.get()); return; }
        Step shown = step.orElseThrow();
        int index = index(), count = steps().size();
        if (index < 0) { icon(HEADER, "minecraft:paper", "&7This step is gone", List.of("&7Go back to the editor"), Optional.empty()); return; }
        boolean question = editor.working().question(block).map((Choices asked) -> asked == shown).orElse(false);
        List<String> lines = new ArrayList<>(ConversationScreen.said(ConversationScreen.text(shown)));
        lines.add("&fNow: step " + (index + 1) + " of " + count);
        icon(HEADER, shown instanceof Line ? "minecraft:paper" : "minecraft:oak_sign", title(ConversationScreen.name(shown, index)), lines, Optional.empty());
        if (shown instanceof Line || question)
            button(EDIT, "minecraft:writable_book", title("Edit text"), any("type it"),
                    (GuiModel.Click click) -> prompt(shown instanceof Line ? "Line" : "Question", ConversationScreen.text(shown), (String typed) -> { if (editor.retext(shown, typed)) viewer.back(); }));
        button(INSERT, "minecraft:paper", title("Insert after"), lore(List.of("&7A new line after this one"), any("type it")),
                (GuiModel.Click click) -> prompt("New line", "", (String typed) -> { if (editor.insert(steps().indexOf(shown) + 1, typed)) viewer.back(); }));
        move(UP, "Move up", shown, -1);
        move(DOWN, "Move down", shown, 1);
        confirm(DELETE, "minecraft:lava_bucket", "Delete", (question ? "Deletes the question and its answers" : "Deletes this step") + "; Discard brings it back", 0, true, () -> {
            editor.delete(shown);
            viewer.back();
        });
    }

    private void answer(Choices asked, Option answer) {
        if (!asked.options.contains(answer)) { icon(HEADER, "minecraft:paper", "&7This answer is gone", List.of("&7Go back to the editor"), Optional.empty()); return; }
        List<String> lines = new ArrayList<>(ConversationScreen.said(answer.text));
        lines.add(answer.next == null ? "&7Ends the conversation" : "&fLeads to: " + answer.next);
        icon(HEADER, "minecraft:light_blue_dye", title("Choice: " + ConversationScreen.clip(answer.text)), lines, Optional.empty());
        button(EDIT, "minecraft:writable_book", title("Edit label"), any("type it"),
                (GuiModel.Click click) -> prompt("Choice label", answer.text, (String typed) -> { if (editor.changed(typed, () -> answer.text = typed)) viewer.back(); }));
        confirm(DELETE, "minecraft:lava_bucket", "Delete", "Deletes the answer and blocks only it reaches; Discard brings them back", 0, true, () -> {
            editor.delete(asked, answer);
            viewer.back();
        });
    }

    private void move(int slot, String name, Step shown, int by) {
        Optional<String> why = editor.unmovable(shown, by);
        if (why.isEmpty()) button(slot, "minecraft:arrow", title(name), any(name.toLowerCase(Locale.ROOT)), (GuiModel.Click click) -> editor.move(shown, by));
        else icon(slot, "minecraft:gray_dye", "&7" + name, List.of("&7" + why.get()), Optional.empty());
    }
}
