package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import java.util.List;
import java.util.Optional;

/**
 * Actions on the current selection. Delete asks twice: the first click arms it and the lore says so, the second click
 * on the same screen deletes; any other button disarms it.
 */
final class ManageScreen<P, S> extends ToolScreen<P, S> {
    static final int SUMMARY = 4, SPAWN = 10, DESPAWN = 11, CLONE = 12, DELETE = 13, TELEPORT = 14, INSPECT = 15, EDITOR = 16,
            GROUPS = 19, FOLDER = 20, ROUTE = 21, SKIN = 22, CLEAR = 25;
    private boolean armed;

    ManageScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }

    @Override String title() { return "Manage selection"; }
    @Override int rows() { return 4; }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        icon(SUMMARY, "minecraft:name_tag", title(ToolkitFiles.count(selected.size()) + " selected"),
                List.of("&7" + (selected.isEmpty() ? "Click NPCs with the wand, or use Find" : names(selected, 8))), Optional.empty());
        back();
        if (selected.isEmpty()) { armed = false; return; }
        action(SPAWN, "minecraft:lime_concrete", "Spawn", "Checks each NPC can run here first", () -> toolkit.spawned(viewer, selected, true));
        action(DESPAWN, "minecraft:gray_concrete", "Despawn", "Definitions stay", () -> toolkit.spawned(viewer, selected, false));
        action(CLONE, "minecraft:paper", "Clone", "Dormant copies named <id>-copy", () -> toolkit.write(viewer, (ToolkitFiles files) -> files.copies(selected)));
        button(DELETE, "minecraft:red_concrete", title(armed ? "&cClick again to delete" : "Delete"),
                lore(List.of(armed ? "&c" + ToolkitFiles.count(selected.size()) + " will be deleted" : "&7Asks once more; rollback restores", "&7" + names(selected, 4)),
                        any(armed ? "delete now" : "arm delete")),
                (GuiModel.Click click) -> {
                    if (!armed) { armed = true; return; }
                    armed = false;
                    toolkit.write(viewer, (ToolkitFiles files) -> files.delete(selected));
                });
        if (selected.size() == 1)
            action(TELEPORT, "minecraft:ender_pearl", "Teleport to it", "Takes you to " + selected.getFirst(), () -> toolkit.visit(viewer.player(), selected.getFirst()));
        action(INSPECT, "minecraft:spyglass", "Inspect", "Chat shows " + selected.getFirst(), () -> {
            viewer.close();
            commands.inspect().accept(toolkit.actor(viewer.player()), selected.getFirst());
        });
        action(EDITOR, "minecraft:writable_book", "Open in editor", "Sends a pairing link", () -> {
            viewer.close();
            commands.pair().accept(toolkit.actor(viewer.player()));
        });
        screen(GROUPS, "minecraft:book", "Trait groups", () -> new GroupScreen<>(toolkit, viewer, commands));
        screen(FOLDER, "minecraft:chest", "Folder", () -> new FolderScreen<>(toolkit, viewer, commands));
        screen(ROUTE, "minecraft:lead", "Route", () -> new RouteScreen<>(toolkit, viewer, commands));
        screen(SKIN, HEAD, "Skin", () -> new SkinScreen<>(toolkit, viewer, commands));
        action(CLEAR, "minecraft:bucket", "Clear selection", "Stops the outline", () -> toolkit.clear(viewer.player()));
    }

    private void action(int slot, String icon, String name, String detail, Runnable run) {
        button(slot, icon, title(name), lore(List.of("&7" + detail), any(name.toLowerCase(java.util.Locale.ROOT))),
                (GuiModel.Click click) -> { armed = false; run.run(); });
    }

    private void screen(int slot, String icon, String name, java.util.function.Supplier<ToolScreen<P, S>> next) {
        button(slot, icon, title(name), any("open"), (GuiModel.Click click) -> { armed = false; viewer.push(next.get()); });
    }
}
