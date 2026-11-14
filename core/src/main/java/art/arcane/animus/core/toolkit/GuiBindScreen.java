package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The chest GUIs, menus and text animations on this server, one tab each (Advanced); it opens on chest GUIs. A click
 * opens a chest GUI or menu from the selection's click in one transaction; a right-click, a text animation, or any
 * click with nothing selected opens its details. Layouts are drawn in the web editor.
 */
final class GuiBindScreen<P, S> extends ToolScreen<P, S> {
    static final int RELOAD = ACTIONS + 8;
    static final List<DocumentKind> TABS = List.of(DocumentKind.INVENTORY, DocumentKind.MENU, DocumentKind.TEXT_ANIMATION);
    static final String RELOAD_COMMAND = "animus gui reload";
    private DocumentKind kind = DocumentKind.INVENTORY;
    private List<ShopFiles.Gui> guis;

    GuiBindScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) {
        super(toolkit, viewer, commands);
    }

    @Override void begin() { reload(); }

    /** Reads the shown tab's folder again; queued after any file change so the list shows it. */
    void reload() {
        DocumentKind reading = kind;
        toolkit.later(viewer, (ToolkitFiles ignored) -> files().guis(reading), (List<ShopFiles.Gui> found) -> {
            if (reading != kind) return;
            guis = found;
            viewer.refresh();
        });
    }

    ShopFiles files() { return new ShopFiles(toolkit.npcs(), toolkit.documents()); }

    @Override String title() { return tab(kind); }
    @Override String node() { return PermissionNodes.GUI; }
    @Override int rows() { return 6; }
    @Override boolean searchable() { return true; }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        if (guis == null) icon(22, "minecraft:clock", "&7Reading " + tab(kind).toLowerCase(Locale.ROOT), List.of(), Optional.empty());
        else list(guis, (ShopFiles.Gui gui) -> gui.id() + " " + gui.title().orElse(""), "None yet. Draw one in the web editor", (Integer slot, ShopFiles.Gui gui) -> entry(slot, gui, selected));
        for (int index = 0; index < TABS.size(); index++) {
            DocumentKind tab = TABS.get(index);
            boolean shown = tab == kind;
            button(ACTIONS + index, icon(tab), title(tab(tab)), lore(List.of(shown ? "&fNow: shown" : "&7" + what(tab)), any(shown ? "read them again" : "show them")), (GuiModel.Click click) -> {
                kind = tab;
                page = 0;
                guis = null;
                reload();
            });
        }
        button(RELOAD, "minecraft:repeater", title("Reload"), lore(List.of("&7Reads changed GUI files"), any("reload")), (GuiModel.Click click) -> {
            viewer.host().command(Optional.of(viewer.player()), RELOAD_COMMAND);
            reload();
        });
        back();
        help("A GUI is a chest window you draw in the web editor. Click one to open it from the selected NPC's click.", "14-web-editor", "gui-editors");
    }

    private void entry(int slot, ShopFiles.Gui gui, List<String> selected) {
        List<String> lines = new ArrayList<>();
        boolean animation = kind == DocumentKind.TEXT_ANIMATION;
        lines.add("&7" + (animation ? "Text shows it as |animation." + gui.id() + "|" : gui.title().orElse(what(kind))));
        if (!animation && !selected.isEmpty()) lines.add("&fNow: on " + using(selected, gui.id()) + " of " + selected.size());
        lines.add("&8" + gui.path());
        boolean details = animation || selected.isEmpty();
        button(slot, icon(kind), title(gui.id()), lore(lines, details ? any("details") : hints("use on " + named(selected), "details", "")), (GuiModel.Click click) -> {
            if (details || right(click)) { push(new GuiDetailScreen<>(toolkit, viewer, commands, gui, this::reload)); return; }
            chosen().ifPresent((List<String> ids) -> ask(slot, "Opens " + gui.id() + " from " + named(ids), ids.size(), false,
                    () -> toolkit.write(viewer, (ToolkitFiles ignored) -> files().bindOpen(kind, gui.id(), ids))));
        });
    }

    /** Shows a chest GUI or menu to the viewer through its {@code /animus gui} command. */
    static <P, S> void preview(ScreenManager<P, S>.Viewer viewer, ShopFiles.Gui gui) {
        viewer.close();
        viewer.host().command(Optional.of(viewer.player()), (gui.kind() == DocumentKind.MENU ? "animus gui menu " : "animus gui open ") + gui.id());
    }

    static String tab(DocumentKind kind) {
        return switch (kind) { case INVENTORY -> "Chest GUIs"; case MENU -> "Menus"; case HOLOGRAM -> "Holograms"; default -> "Text animations"; };
    }

    static String icon(DocumentKind kind) {
        return switch (kind) { case INVENTORY -> "minecraft:chest"; case MENU -> "minecraft:item_frame"; case HOLOGRAM -> "minecraft:armor_stand"; default -> "minecraft:clock"; };
    }

    /** The one line of what a kind is, for its tab and for an entry without a title. */
    static String what(DocumentKind kind) {
        return switch (kind) {
            case INVENTORY -> "A chest window you design";
            case MENU -> "A GUI built from a list of buttons";
            case HOLOGRAM -> "Floating text above an NPC";
            default -> "Moving text other GUIs show";
        };
    }

    /** How many selected NPCs open or show {@code id} through {@code animus:gui}. */
    private int using(List<String> selected, String id) {
        Set<String> chosen = new HashSet<>(selected);
        int count = 0;
        for (NpcDefinition definition : toolkit.npcs().stored()) if (chosen.contains(definition.id()) && ShopFiles.usesGui(definition.json(), kind, id)) count++;
        return count;
    }
}
