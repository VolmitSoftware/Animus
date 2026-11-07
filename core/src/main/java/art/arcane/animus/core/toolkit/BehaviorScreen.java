package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import java.util.List;
import java.util.Optional;

/**
 * Every behavior file, loose and in packs, with what starts it and how many selected NPCs have it. A left click puts it
 * on the selection, a right click takes it off, each in one transaction; more than {@link #BULK} NPCs ask twice. A
 * sneak click opens the run, trace and conversation controls.
 */
final class BehaviorScreen<P, S> extends ToolScreen<P, S> {
    static final int TRIGGERS = 46, RELOAD = 47, CREATE = 52;
    private List<BehaviorFiles.Behavior> behaviors;
    /** The behavior and direction a bulk change is waiting on a second click for. */
    private String armed = "";

    BehaviorScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) {
        super(toolkit, viewer, commands);
        reload();
    }

    /** Reads {@code behaviors/} and {@code packs/} again; queued after any write so the counts show it. */
    private void reload() {
        List<String> selected = toolkit.selected(viewer.id());
        toolkit.later(viewer, (ToolkitFiles ignored) -> files(toolkit).behaviors(selected), (List<BehaviorFiles.Behavior> found) -> { behaviors = found; viewer.refresh(); });
    }

    static BehaviorFiles files(Toolkit<?, ?> toolkit) { return new BehaviorFiles(toolkit.npcs(), toolkit.documents()); }

    @Override String title() { return "Behaviors"; }
    @Override int rows() { return 6; }

    @Override void draw() {
        int selected = toolkit.selected(viewer.id()).size();
        if (behaviors == null) icon(22, "minecraft:clock", "&7Reading behaviors/", List.of(), Optional.empty());
        else list(behaviors, "No behaviors yet; make a conversation below", (Integer slot, BehaviorFiles.Behavior behavior) -> entry(slot, behavior, selected));
        back();
        button(TRIGGERS, "minecraft:tripwire_hook", title("Trigger bindings"), lore(List.of("&7Closes the window; chat lists what", "&7each NPC listens for and how often"), any("list them")),
                (GuiModel.Click click) -> { armed = ""; viewer.close(); command("animus behaviors triggers"); });
        button(RELOAD, "minecraft:repeater", title("Reload behaviors"), lore(List.of("&7Loads changed files from disk"), any("reload")),
                (GuiModel.Click click) -> { armed = ""; command("animus behaviors reload"); reload(); });
        button(CREATE, "minecraft:writable_book", title("New conversation"), lore(List.of("&7Asks for an id like town:greeter", "&7Says Hello on a right click"), any("name it")),
                (GuiModel.Click click) -> { armed = ""; create(); });
    }

    private void entry(int slot, BehaviorFiles.Behavior behavior, int selected) {
        boolean waiting = armed.startsWith(behavior.id() + " ");
        String counted = "&f" + behavior.listed() + "&7 of &f" + selected + "&7 selected list it" + (behavior.grouped() == 0 ? "" : ", &f" + behavior.grouped() + "&7 more through a trait group");
        List<String> lines = List.of("&7" + behavior.path(), "&7" + behavior.pack().map((String pack) -> "Pack " + pack).orElse("Loose file"), "&7" + behavior.triggers(),
                behavior.misfit().isEmpty() ? "&aConversation" : "&7Node graph; nodes edit in the web editor", counted);
        List<String> shown = waiting ? List.of("&c" + ToolkitFiles.count(selected) + " affected", "&7Same click again to " + (armed.endsWith(" on") ? "attach" : "detach"), "&7Any other click cancels")
                : lines;
        String icon = behavior.misfit().isEmpty() ? "minecraft:writable_book" : behavior.pack().isPresent() ? "minecraft:enchanted_book" : "minecraft:book";
        button(slot, icon, title(waiting ? "&cClick again: " + behavior.id() : behavior.id()), lore(shown, hints("attach to the selection", "detach from the selection", "run, trace, conversation")),
                (GuiModel.Click click) -> {
                    if (sneak(click)) { armed = ""; viewer.push(new BehaviorDetailScreen<>(toolkit, viewer, commands, behavior)); return; }
                    Optional<List<String>> ids = chosen();
                    if (ids.isEmpty()) { armed = ""; return; }
                    boolean attach = !right(click);
                    String key = behavior.id() + (attach ? " on" : " off");
                    if (ids.get().size() > BULK && !armed.equals(key)) { armed = key; return; }
                    armed = "";
                    toolkit.write(viewer, (ToolkitFiles ignored) -> files(toolkit).attach(behavior.id(), ids.get(), attach));
                    reload();
                });
    }

    private void create() {
        prompt("Behavior id", "", (String id) -> toolkit.later(viewer, (ToolkitFiles ignored) -> files(toolkit).create(id.trim()), (BehaviorFiles.Saved saved) -> {
            toolkit.say(viewer.player(), ChatLine.parse(Palette.success(saved.line())));
            command("animus behaviors reload");
            reload();
            viewer.push(new ConversationScreen<>(toolkit, viewer, commands, saved.loaded()));
        }));
    }

    private void command(String line) { viewer.host().command(Optional.of(viewer.player()), line); }
}
