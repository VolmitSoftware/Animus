package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import java.util.List;

/** {@code /animus tools}: one button per tool, and the selection they act on. */
final class PaletteScreen<P, S> extends ToolScreen<P, S> {
    static final int SELECTION = 4, WAND = 10, MANAGE = 11, GROUPS = 12, FOLDERS = 13, ROUTES = 14, SKINS = 15, FIND = 16, CLOSE = 22;

    PaletteScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }

    @Override String title() { return "Animus tools"; }
    @Override int rows() { return 3; }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        button(SELECTION, "minecraft:spyglass", title(selected.size() + " selected"), lore(List.of("&7" + names(selected, 8)), hints("manage the selection", "manage the selection", "clear the selection")),
                (GuiModel.Click click) -> {
                    if (sneak(click)) toolkit.clear(viewer.player());
                    else viewer.push(new ManageScreen<>(toolkit, viewer, commands));
                });
        button(WAND, "minecraft:blaze_rod", title("Selection wand"), lore(List.of("&7Click an NPC with it to select it"), hints("take a wand", "take a wand", "take a wand")),
                (GuiModel.Click click) -> toolkit.giveWand(viewer.player()));
        button(MANAGE, "minecraft:name_tag", title("Manage"), lore(List.of("&7Spawn, despawn, clone, delete, edit"), hints("open", "inspect the first selected", "open")),
                (GuiModel.Click click) -> {
                    if (right(click) && !selected.isEmpty()) { viewer.close(); commands.inspect().accept(toolkit.actor(viewer.player()), selected.getFirst()); }
                    else viewer.push(new ManageScreen<>(toolkit, viewer, commands));
                });
        button(GROUPS, "minecraft:book", title("Trait groups"), lore(List.of("&7Assign groups to the selection"), hints("open", "open", "open")),
                (GuiModel.Click click) -> viewer.push(new GroupScreen<>(toolkit, viewer, commands)));
        button(FOLDERS, "minecraft:chest", title("Folders"), lore(List.of("&7Move the selection to a folder"), hints("open", "open", "open")),
                (GuiModel.Click click) -> viewer.push(new FolderScreen<>(toolkit, viewer, commands)));
        button(ROUTES, "minecraft:lead", title("Routes"), lore(List.of("&7Assign, walk and capture routes"), hints("open", "add a route point here", "remove the last point")),
                (GuiModel.Click click) -> {
                    if (sneak(click)) toolkit.undoPoint(viewer.player());
                    else if (right(click)) toolkit.point(viewer.player());
                    else viewer.push(new RouteScreen<>(toolkit, viewer, commands));
                });
        head(SKINS, title("Skins"), lore(List.of("&7Player bodies only"), hints("open", "put your skin on the selection", "open")), viewer.host().name(viewer.player()),
                (GuiModel.Click click) -> {
                    if (right(click)) chosen().ifPresent((List<String> ids) -> toolkit.mySkin(viewer, ids));
                    else viewer.push(new SkinScreen<>(toolkit, viewer, commands));
                });
        button(FIND, "minecraft:map", title("Find"), lore(List.of("&7Nearest NPCs; teleport or bring"), hints("open", "open", "open")),
                (GuiModel.Click click) -> viewer.push(new FindScreen<>(toolkit, viewer, commands)));
        button(CLOSE, "minecraft:barrier", title("Close"), hints("close", "close", "close"), (GuiModel.Click click) -> viewer.close());
    }
}
