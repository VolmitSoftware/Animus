package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Actions on the current selection: the lifecycle row (spawn, despawn, clone, delete, teleport, inspect, editor) and a
 * row of the tools that edit it. Delete asks twice through {@link #guarded}.
 */
final class ManageScreen<P, S> extends ToolScreen<P, S> {
    static final int SUMMARY = 4, SPAWN = 10, DESPAWN = 11, CLONE = 12, DELETE = 13, TELEPORT = 14, INSPECT = 15, EDITOR = 16,
            EDIT = 18, EQUIPMENT = 19, COMBAT = 20, MOUNTS = 21, SKIN = 22, GROUPS = 23, FOLDER = 24, ROUTE = 25, BEHAVIORS = 26, CLEAR = 35;

    ManageScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }

    @Override String title() { return "Manage selection"; }
    @Override int rows() { return 4; }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        icon(SUMMARY, "minecraft:name_tag", title(ToolkitFiles.count(selected.size()) + " selected"),
                List.of("&7" + (selected.isEmpty() ? "Click NPCs with the wand, or use Find" : names(selected, 8))), Optional.empty());
        back();
        if (selected.isEmpty()) return;
        action(SPAWN, "minecraft:lime_concrete", "Spawn", "Checks each NPC can run here first", () -> toolkit.spawned(viewer, selected, true));
        action(DESPAWN, "minecraft:gray_concrete", "Despawn", "Definitions stay", () -> toolkit.spawned(viewer, selected, false));
        action(CLONE, "minecraft:paper", "Clone", "Dormant copies named <id>-copy", () -> toolkit.write(viewer, (ToolkitFiles files) -> files.copies(selected)));
        guarded(DELETE, "minecraft:red_concrete", "Delete", "Rollback restores: " + names(selected, 4), selected.size(), true,
                () -> toolkit.write(viewer, (ToolkitFiles files) -> files.delete(selected)));
        if (selected.size() == 1)
            action(TELEPORT, "minecraft:ender_pearl", "Teleport to it", "Takes you to " + selected.getFirst(), () -> toolkit.visit(viewer.player(), selected.getFirst()));
        action(INSPECT, "minecraft:spyglass", "Inspect", "Chat shows " + selected.getFirst(), () -> {
            viewer.close();
            commands.inspect().accept(toolkit.actor(viewer.player()), selected.getFirst());
        });
        action(EDITOR, "minecraft:ender_eye", "Open in editor", "Sends a pairing link", () -> {
            viewer.close();
            commands.pair().accept(toolkit.actor(viewer.player()));
        });
        screen(EDIT, "minecraft:writable_book", "Edit", () -> new PropertyScreen<>(toolkit, viewer, commands, selected));
        screen(EQUIPMENT, "minecraft:armor_stand", "Equipment", () -> new EquipmentScreen<>(toolkit, viewer, commands));
        screen(COMBAT, "minecraft:iron_sword", "Combat", () -> new CombatScreen<>(toolkit, viewer, commands));
        screen(MOUNTS, "minecraft:saddle", "Mounts", () -> new MountScreen<>(toolkit, viewer, commands));
        screen(SKIN, HEAD, "Skin", () -> new SkinScreen<>(toolkit, viewer, commands));
        screen(GROUPS, "minecraft:book", "Trait groups", () -> new GroupScreen<>(toolkit, viewer, commands));
        screen(FOLDER, "minecraft:chest", "Folder", () -> new FolderScreen<>(toolkit, viewer, commands));
        screen(ROUTE, "minecraft:lead", "Route", () -> new RouteScreen<>(toolkit, viewer, commands));
        screen(BEHAVIORS, "minecraft:lectern", "Behaviors", () -> new BehaviorScreen<>(toolkit, viewer, commands));
        action(CLEAR, "minecraft:bucket", "Clear selection", "Stops the outline", () -> toolkit.clear(viewer.player()));
    }

    private void action(int slot, String icon, String name, String detail, Runnable run) {
        button(slot, icon, title(name), lore(List.of("&7" + detail), any(name.toLowerCase(Locale.ROOT))), (GuiModel.Click click) -> run.run());
    }

    private void screen(int slot, String icon, String name, Supplier<ToolScreen<P, S>> next) {
        button(slot, icon, title(name), any("open"), (GuiModel.Click click) -> viewer.push(next.get()));
    }
}
