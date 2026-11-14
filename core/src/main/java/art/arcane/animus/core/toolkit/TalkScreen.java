package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import java.util.List;
import java.util.Optional;

/**
 * NPC > Talk. With no conversation yet it makes one that says Hello on a right click, attaches it and opens the
 * conversation editor; with one it opens that; with several it lists them to pick. The editor takes this screen's
 * place, so Back from it returns to the NPC screen. Nothing is read or made until the screen shows, and a result that
 * comes back after the player moved on only reports in chat.
 */
final class TalkScreen<P, S> extends ToolScreen<P, S> {
    private List<BehaviorFiles.Behavior> talks;
    private String failed;

    TalkScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }

    @Override String title() { return "Talk"; }
    @Override String node() { return PermissionNodes.BEHAVIORS; }
    @Override int rows() { return 6; }

    @Override void begin() {
        List<String> ids = toolkit.selected(viewer.id());
        if (ids.isEmpty()) { talks = List.of(); return; }
        toolkit.later(viewer, (ToolkitFiles ignored) -> BehaviorScreen.files(toolkit).talks(ids), (List<BehaviorFiles.Behavior> found) -> {
            if (!viewer.shows(this)) return;
            if (found.isEmpty()) make(ids);
            else if (found.size() == 1) open(found.getFirst().id(), true);
            else { talks = found; viewer.refresh(); }
        }, this::failed);
    }

    @Override void draw() {
        if (failed != null) icon(22, "minecraft:barrier", "&cConversation not ready", List.of("&7" + failed, "&7Back, then try again"), Optional.empty());
        else if (talks == null) icon(22, "minecraft:clock", "&7Getting the conversation ready", List.of(), Optional.empty());
        else list(talks, BehaviorFiles.Behavior::id, "Nothing selected. Pick an NPC in NPCs", (Integer slot, BehaviorFiles.Behavior talk) ->
                button(slot, "minecraft:writable_book", title(talk.id()), lore(List.of("&7Conversation"), any("edit it")), (GuiModel.Click click) -> open(talk.id(), false)));
        back();
        help("Talk edits what the NPC says and the answers players pick. With several conversations, pick the one to edit.", "21-in-game-toolkit", "conversations");
    }

    private void failed(String reason) {
        failed = reason;
        viewer.refresh();
    }

    /** Makes the first conversation and attaches it in one change, then opens it in this screen's place while it still shows. */
    private void make(List<String> ids) {
        toolkit.later(viewer, (ToolkitFiles ignored) -> {
            BehaviorFiles.Saved saved = BehaviorScreen.files(toolkit).talk(ids);
            toolkit.living().refresh();
            return saved;
        }, (BehaviorFiles.Saved saved) -> {
            toolkit.say(viewer.player(), ChatLine.parse(Palette.success(saved.line())));
            viewer.host().command(Optional.empty(), BehaviorScreen.RELOAD_COMMAND);
            replace(new ConversationScreen<>(toolkit, viewer, commands, saved.loaded()));
        }, this::failed);
    }

    /** Opens the conversation in this screen's place when Talk went straight to it, or over the list; only while Talk still shows. */
    private void open(String id, boolean instead) {
        toolkit.later(viewer, (ToolkitFiles ignored) -> BehaviorScreen.files(toolkit).conversation(id), (BehaviorFiles.Loaded loaded) -> {
            ConversationScreen<P, S> editor = new ConversationScreen<>(toolkit, viewer, commands, loaded);
            if (instead) replace(editor);
            else if (viewer.shows(this)) push(editor);
        }, this::failed);
    }
}
