package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every behavior file, loose and in packs (Advanced). A click puts one on the selection in one transaction, asking
 * once more above {@link #BULK} NPCs; a right-click, or any click with nothing selected, opens its details.
 */
final class BehaviorScreen<P, S> extends ToolScreen<P, S> {
    static final int CREATE = ACTIONS, RELOAD = ACTIONS + 1, TRIGGERS = ACTIONS + 2;
    static final String RELOAD_COMMAND = "animus behaviors reload";
    private List<BehaviorFiles.Behavior> behaviors;

    BehaviorScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) {
        super(toolkit, viewer, commands);
    }

    @Override void begin() { reload(); }

    /** Reads {@code behaviors/} and {@code packs/} again; queued after any write so the counts show it. */
    private void reload() {
        List<String> selected = toolkit.selected(viewer.id());
        toolkit.later(viewer, (ToolkitFiles ignored) -> files(toolkit).behaviors(selected), (List<BehaviorFiles.Behavior> found) -> { behaviors = found; viewer.refresh(); });
    }

    static BehaviorFiles files(Toolkit<?, ?> toolkit) { return new BehaviorFiles(toolkit.npcs(), toolkit.documents()); }

    @Override String title() { return "Behaviors"; }
    @Override String node() { return PermissionNodes.BEHAVIORS; }
    @Override int rows() { return 6; }
    @Override boolean searchable() { return true; }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        if (behaviors == null) icon(22, "minecraft:clock", "&7Reading behaviors", List.of(), Optional.empty());
        else list(behaviors, BehaviorFiles.Behavior::id, "None yet. Make one with New conversation", (Integer slot, BehaviorFiles.Behavior behavior) -> entry(slot, behavior, selected));
        button(CREATE, "minecraft:writable_book", title("New conversation"), lore(List.of("&7Says Hello on a right click"), any("name it")), (GuiModel.Click click) -> create());
        button(RELOAD, "minecraft:repeater", title("Reload"), lore(List.of("&7Reads changed behavior files"), any("reload")), (GuiModel.Click click) -> {
            command(RELOAD_COMMAND);
            reload();
        });
        button(TRIGGERS, "minecraft:tripwire_hook", title("Trigger bindings"), lore(List.of("&7What each NPC listens for", "&7Shows in chat"), any("list them")), (GuiModel.Click click) -> {
            viewer.close();
            command("animus behaviors triggers");
        });
        back();
        help("A behavior is what an NPC does when something happens. Click one to put it on the selected NPC, right-click for details.", "21-in-game-toolkit", "behaviors");
    }

    private void entry(int slot, BehaviorFiles.Behavior behavior, List<String> selected) {
        List<String> lines = new ArrayList<>(List.of(behavior.misfit().isEmpty() ? "&7Conversation" : "&7Node graph"));
        if (!selected.isEmpty())
            lines.add("&fNow: on " + behavior.listed() + " of " + selected.size() + (behavior.grouped() == 0 ? "" : ", " + behavior.grouped() + " by trait group"));
        String icon = behavior.misfit().isEmpty() ? "minecraft:writable_book" : behavior.pack().isPresent() ? "minecraft:enchanted_book" : "minecraft:book";
        List<String> clicks = selected.isEmpty() ? any("details") : hints("use on " + named(selected), "details", "");
        button(slot, icon, title(behavior.id()), lore(lines, clicks), (GuiModel.Click click) -> {
            if (selected.isEmpty() || right(click)) { push(new BehaviorDetailScreen<>(toolkit, viewer, commands, behavior, this::reload)); return; }
            chosen().ifPresent((List<String> ids) -> ask(slot, "Puts " + behavior.id() + " on " + named(ids), ids.size(), false, () -> {
                toolkit.write(viewer, (ToolkitFiles ignored) -> files(toolkit).attach(behavior.id(), ids, true));
                reload();
            }));
        });
    }

    private void create() {
        prompt("Behavior id", "", (String id) -> toolkit.later(viewer, (ToolkitFiles ignored) -> files(toolkit).create(id.trim()), (BehaviorFiles.Saved saved) -> {
            toolkit.say(viewer.player(), ChatLine.parse(Palette.success(saved.line())));
            viewer.host().command(Optional.empty(), RELOAD_COMMAND);
            reload();
            push(new ConversationScreen<>(toolkit, viewer, commands, saved.loaded()));
        }));
    }

    private void command(String line) { viewer.host().command(Optional.of(viewer.player()), line); }
}
