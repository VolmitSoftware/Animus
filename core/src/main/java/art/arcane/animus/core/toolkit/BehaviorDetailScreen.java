package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.text.ChatLine;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One behavior's controls. Run, Trace and Cancel go through {@code /animus behaviors} for each selected NPC, so the
 * replies are the commands' own. Cancel takes a run id: it traces the selection first, so the ids are in chat, then
 * asks for one.
 */
final class BehaviorDetailScreen<P, S> extends ToolScreen<P, S> {
    static final int SUMMARY = 4, RUN = 10, TRACE = 11, CANCEL = 12, VALIDATE = 13, CONVERSATION = 15, ATTACH = 16;
    private final BehaviorFiles.Behavior behavior;

    BehaviorDetailScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, BehaviorFiles.Behavior behavior) {
        super(toolkit, viewer, commands);
        this.behavior = behavior;
    }

    @Override String title() { return behavior.id(); }
    @Override int rows() { return 6; }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        String id = behavior.id();
        icon(SUMMARY, "minecraft:book", title(id), List.of("&7" + behavior.path(), "&7" + behavior.triggers(), "&f" + ToolkitFiles.count(selected.size()) + "&7 selected"), Optional.empty());
        guarded(RUN, "minecraft:lime_dye", "Run", "Runs it once on each selected NPC", selected.size(), false,
                () -> chosen().ifPresent((List<String> ids) -> { for (String npc : ids) command("animus behaviors run npc=" + npc + " behavior=" + id); }));
        guarded(TRACE, "minecraft:spyglass", "Trace", "Closes the window; chat lists running and recent runs", selected.size(), false,
                () -> chosen().ifPresent((List<String> ids) -> { viewer.close(); trace(ids); }));
        button(CANCEL, "minecraft:barrier", title("Cancel a run"), lore(List.of("&7Traces the selection so the run ids", "&7show in chat, then asks for one"), any("pick a run id")),
                (GuiModel.Click click) -> chosen().ifPresent((List<String> ids) -> {
                    trace(ids);
                    prompt("Run id to cancel", "", (String typed) -> {
                        try { command("animus behaviors cancel execution=" + UUID.fromString(typed.trim())); }
                        catch (IllegalArgumentException invalid) { toolkit.say(viewer.player(), ChatLine.error(typed.trim() + " is not a run id; trace lists them")); }
                    });
                }));
        button(VALIDATE, "minecraft:name_tag", title("Validate"), lore(List.of("&7Checks the file without loading it"), any("validate")),
                (GuiModel.Click click) -> command("animus behaviors validate behavior=" + id));
        if (behavior.misfit().isEmpty())
            button(CONVERSATION, "minecraft:writable_book", title("Open as conversation"), lore(List.of("&7Lines, questions and answers"), any("edit it")),
                    (GuiModel.Click click) -> toolkit.later(viewer, (ToolkitFiles ignored) -> BehaviorScreen.files(toolkit).conversation(id),
                            (BehaviorFiles.Loaded loaded) -> viewer.push(new ConversationScreen<>(toolkit, viewer, commands, loaded))));
        else icon(CONVERSATION, "minecraft:paper", "&7Not a conversation", List.of("&7" + behavior.misfit().get(), "&7Edit its nodes in the web editor"), Optional.empty());
        guarded(ATTACH, "minecraft:lead", "Attach to selection", "Adds it to each NPC's behaviors", selected.size(), false,
                () -> chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles ignored) -> BehaviorScreen.files(toolkit).attach(id, ids, true))));
        back();
    }

    private void trace(List<String> ids) { for (String npc : ids) command("animus behaviors trace npc=" + npc); }

    private void command(String line) { viewer.host().command(Optional.of(viewer.player()), line); }
}
