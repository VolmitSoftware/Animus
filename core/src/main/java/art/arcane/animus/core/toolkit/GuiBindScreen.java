package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The chest GUIs, menus, holograms and text animations on this server, one tab each. A click binds a chest GUI or menu
 * to the selection's click, or a hologram above it, in one transaction; a right click shows a chest GUI or menu to you.
 * Layouts are painted in the web editor.
 */
final class GuiBindScreen<P, S> extends ToolScreen<P, S> {
    static final int RELOAD = 53;
    static final int[] TABS = {46, 47, 51, 52};
    static final String RELOAD_COMMAND = "animus gui reload";
    private DocumentKind kind = DocumentKind.HOLOGRAM;
    private List<ShopFiles.Gui> guis;

    GuiBindScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) {
        super(toolkit, viewer, commands);
        reload();
    }

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

    @Override String title() { return "GUIs: " + tab(kind); }
    @Override int rows() { return 6; }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        if (guis == null) icon(22, "minecraft:clock", "&7Reading " + kind.folder() + "/", List.of(), Optional.empty());
        else list(guis, "No " + tab(kind).toLowerCase(java.util.Locale.ROOT) + " in " + kind.folder() + "/; paint one in the web editor", (Integer slot, ShopFiles.Gui gui) -> {
            List<String> lines = new ArrayList<>();
            gui.title().ifPresent((String shown) -> lines.add("&f" + shown));
            lines.add("&7" + gui.path());
            if (kind == DocumentKind.TEXT_ANIMATION) lines.add("&7Text shows it as |animation." + gui.id() + "|");
            else lines.add("&7In the selection &f" + using(selected, gui.id()) + "&7 of &f" + selected.size());
            button(slot, icon(kind), title(gui.id()), lore(lines, clicks(kind)), (GuiModel.Click click) -> {
                if (sneak(click) || kind == DocumentKind.TEXT_ANIMATION || (right(click) && kind == DocumentKind.HOLOGRAM))
                    viewer.push(new GuiDetailScreen<>(toolkit, viewer, commands, gui, this::reload));
                else if (right(click)) preview(viewer, gui);
                else chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles ignored) ->
                        kind == DocumentKind.HOLOGRAM ? files().bindHologram(gui.id(), ids) : files().bindOpen(kind, gui.id(), ids)));
            });
        });
        back();
        for (int index = 0; index < ShopFiles.GUIS.size(); index++) {
            DocumentKind tab = ShopFiles.GUIS.get(index);
            boolean shown = tab == kind;
            button(TABS[index], icon(tab), title(tab(tab) + (shown ? " (shown)" : "")), lore(List.of("&7" + tab.folder() + "/"), any(shown ? "read the folder again" : "list them")), (GuiModel.Click click) -> {
                kind = tab;
                page = 0;
                guis = null;
                reload();
            });
        }
        button(RELOAD, "minecraft:repeater", title("Reload GUIs"), lore(List.of("&7After editing files by hand"), any("reload GUIs, menus, holograms and shops")), (GuiModel.Click click) -> {
            viewer.host().command(Optional.of(viewer.player()), RELOAD_COMMAND);
            reload();
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

    private static List<String> clicks(DocumentKind kind) {
        return switch (kind) {
            case INVENTORY, MENU -> hints("open it on the selection's click", "show it to you now", "settings");
            case HOLOGRAM -> hints("bind it above the selection", "settings", "settings");
            default -> any("settings");
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
