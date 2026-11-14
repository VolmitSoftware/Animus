package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The saved holograms, the hologram files of the library. A click shows one above the selected NPCs and goes back to
 * Hologram; a right-click opens it, where it can also be deleted once nothing shows it.
 */
final class SavedHologramScreen<P, S> extends ToolScreen<P, S> {
    /** Loads the hologram catalog again after a file is deleted. */
    static final String RELOAD = "animus gui reload";
    private List<ShopFiles.Gui> holograms;

    SavedHologramScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) {
        super(toolkit, viewer, commands);
    }

    @Override void begin() { reload(); }

    void reload() { toolkit.later(viewer, (ToolkitFiles ignored) -> files(toolkit).guis(DocumentKind.HOLOGRAM), (List<ShopFiles.Gui> found) -> { holograms = found; viewer.refresh(); }); }

    static ShopFiles files(Toolkit<?, ?> toolkit) { return new ShopFiles(toolkit.npcs(), toolkit.documents()); }

    @Override String title() { return "Saved holograms"; }
    @Override String node() { return PermissionNodes.GUI; }
    @Override int rows() { return 6; }
    @Override boolean searchable() { return true; }

    @Override void draw() {
        back();
        help("A saved hologram is a hologram file, made in the web editor or with /animus hologram create. Click one to show it above the selected NPCs.",
                "11-guis-holograms-menus", "npc-bindings");
        List<String> ids = toolkit.selected(viewer.id());
        if (holograms == null) { icon(22, "minecraft:clock", "&7Reading holograms", List.of(), Optional.empty()); return; }
        list(holograms, ShopFiles.Gui::id, "No saved holograms yet; the web editor makes them", (Integer slot, ShopFiles.Gui hologram) -> {
            List<String> lines = new ArrayList<>();
            hologram.title().ifPresent((String shown) -> lines.add("&7" + shown));
            if (!ids.isEmpty() && ShopFiles.usesGui(NpcScreen.stored(toolkit, ids.getFirst()), DocumentKind.HOLOGRAM, hologram.id())) lines.add("&fNow: shown above it");
            button(slot, "minecraft:armor_stand", title(hologram.id()), lore(lines, hints(ids.isEmpty() ? "details" : "show it above " + named(ids), "details", "")), (GuiModel.Click click) -> {
                if (click.right() || toolkit.selected(viewer.id()).isEmpty()) { push(new Detail<>(toolkit, viewer, commands, hologram, this::reload)); return; }
                chosen().ifPresent((List<String> chosen) -> ask(slot, "Shows " + hologram.id() + " above " + named(chosen), chosen.size(), false, () -> {
                    toolkit.write(viewer, (ToolkitFiles ignored) -> files(toolkit).bindHologram(hologram.id(), chosen));
                    viewer.back();
                }));
            });
        });
    }

    /** One saved hologram: who shows it, show it above the selection, or delete the file once nothing shows it. */
    static final class Detail<P, S> extends ToolScreen<P, S> {
        static final int HEADER = 4, USE = 20, DELETE = 35;
        private final ShopFiles.Gui hologram;
        private final Runnable changed;
        private List<String> users;

        Detail(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, ShopFiles.Gui hologram, Runnable changed) {
            super(toolkit, viewer, commands);
            this.hologram = hologram; this.changed = changed;
        }

        @Override void begin() { reload(); }

        private void reload() { toolkit.later(viewer, (ToolkitFiles ignored) -> files(toolkit).guiUsers(DocumentKind.HOLOGRAM, hologram.id()), (List<String> found) -> { users = found; viewer.refresh(); }); }

        @Override String title() { return hologram.id(); }
        @Override String node() { return PermissionNodes.GUI; }
        @Override int rows() { return 6; }

        @Override void draw() {
            List<String> lines = new ArrayList<>();
            hologram.title().ifPresent((String shown) -> lines.add("&7" + shown));
            lines.add("&fNow: " + (users == null ? "reading" : users.isEmpty() ? "shown by no NPC" : "shown by " + npcNames(users, 4)));
            icon(HEADER, "minecraft:armor_stand", title(hologram.id()), lines, Optional.empty());
            button(USE, "minecraft:oak_sign", title("Use it"), lore(List.of("&7Shows it above the selection"), any("use it")), (GuiModel.Click click) -> chosen().ifPresent((List<String> ids) ->
                    ask(USE, "Shows " + hologram.id() + " above " + named(ids), ids.size(), false, () -> {
                        toolkit.write(viewer, (ToolkitFiles ignored) -> files(toolkit).bindHologram(hologram.id(), ids));
                        reload();
                        changed.run();
                    })));
            confirm(DELETE, "minecraft:lava_bucket", "Delete", users != null && !users.isEmpty() ? "Refused while NPCs show it; remove it from them first" : "Deletes this hologram; Undo in chat brings it back",
                    0, true, () -> ShopFiles.save(toolkit, viewer, (ToolkitFiles ignored) -> files(toolkit).deleteGui(DocumentKind.HOLOGRAM, hologram.id()), RELOAD, () -> { changed.run(); viewer.back(); }));
            back();
            help("Use it shows this hologram above the selected NPCs. Delete works once no NPC or trait group shows it.", "11-guis-holograms-menus", "holograms");
        }
    }
}
