package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /animus tools}: one button per tool, grouped by row. The top row is the selection, then the NPCs themselves
 * (wand, new, find, manage, edit, folders, groups), then what they look like and do (skins, equipment, combat, mounts,
 * routes, recordings), then what they open and run (behaviors, shops, GUIs), and Close.
 */
final class PaletteScreen<P, S> extends ToolScreen<P, S> {
    static final int SELECTION = 4, WAND = 10, NEW = 11, FIND = 12, MANAGE = 13, EDIT = 14, FOLDERS = 15, GROUPS = 16,
            SKINS = 19, EQUIPMENT = 20, COMBAT = 21, MOUNTS = 22, ROUTES = 23, RECORDINGS = 24, BEHAVIORS = 30, SHOPS = 31, GUIS = 32, CLOSE = 40;
    static final String PLAYER = ToolkitFiles.PLAYER;

    PaletteScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }

    @Override String title() { return "Animus tools"; }
    @Override int rows() { return 5; }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        button(SELECTION, "minecraft:spyglass", title(selected.size() + " selected"), lore(List.of("&7" + names(selected, 8)), hints("list the selection", "manage the selection", "clear the selection")),
                (GuiModel.Click click) -> {
                    if (sneak(click)) toolkit.clear(viewer.player());
                    else if (right(click)) push(new ManageScreen<>(toolkit, viewer, commands));
                    else push(new SelectionScreen<>(toolkit, viewer, commands));
                });
        button(WAND, "minecraft:blaze_rod", title("Animus wand"), lore(List.of("&7Click an NPC with it to select it"), any("put one in your inventory")),
                (GuiModel.Click click) -> toolkit.giveWand(viewer.player()));
        button(NEW, "minecraft:egg", title("New NPC"), lore(List.of("&7Saved in front of you and selected"), hints("player body, spawned", "choose the body type", "player body, kept dormant")),
                (GuiModel.Click click) -> {
                    if (!allows(PermissionNodes.NPC_CREATE)) return;
                    if (right(click)) prompt("Body type", PLAYER, (String type) -> prompt("New NPC id", "", (String id) -> toolkit.create(viewer, id.trim(), type.trim(), true)));
                    else { boolean spawn = !sneak(click); prompt("New NPC id", "", (String id) -> toolkit.create(viewer, id.trim(), PLAYER, spawn)); }
                });
        open(FIND, "minecraft:map", "Find", "Nearest NPCs; teleport or bring", () -> new FindScreen<>(toolkit, viewer, commands));
        button(MANAGE, "minecraft:name_tag", title("Manage"), lore(List.of("&7Spawn, despawn, clone, delete"), hints("open the manage screen", "inspect the first selected", "open the manage screen")),
                (GuiModel.Click click) -> {
                    if (right(click) && !selected.isEmpty()) { if (allows(PermissionNodes.NPC_VIEW)) { viewer.close(); commands.inspect().accept(toolkit.actor(viewer.player()), selected.getFirst()); } }
                    else push(new ManageScreen<>(toolkit, viewer, commands));
                });
        open(EDIT, "minecraft:writable_book", "Edit", "Change any field of the selection", () -> new PropertyScreen<>(toolkit, viewer, commands));
        open(FOLDERS, "minecraft:chest", "Folders", "Move the selection to a folder", () -> new FolderScreen<>(toolkit, viewer, commands));
        open(GROUPS, "minecraft:book", "Trait groups", "Assign groups to the selection", () -> new GroupScreen<>(toolkit, viewer, commands));

        head(SKINS, title("Skins"), lore(List.of("&7Player bodies only"), hints("open the skin list", "put your skin on the selection", "open the skin list")), viewer.host().name(viewer.player()),
                (GuiModel.Click click) -> {
                    if (right(click)) chosen().ifPresent((List<String> ids) -> toolkit.mySkin(viewer, ids));
                    else push(new SkinScreen<>(toolkit, viewer, commands));
                });
        open(EQUIPMENT, "minecraft:armor_stand", "Equipment", "Hands and armor from your own", () -> new EquipmentScreen<>(toolkit, viewer, commands));
        open(COMBAT, "minecraft:iron_sword", "Combat", "Fighting, guarding and targets", () -> new CombatScreen<>(toolkit, viewer, commands));
        open(MOUNTS, "minecraft:saddle", "Mounts", "Ride, carry and player riding", () -> new MountScreen<>(toolkit, viewer, commands));
        button(ROUTES, "minecraft:lead", title("Routes"), lore(List.of("&7Assign, walk and capture routes"), hints("open the route list", "add a route point here", "remove the last point")),
                (GuiModel.Click click) -> {
                    if (sneak(click)) toolkit.undoPoint(viewer.player());
                    else if (right(click)) toolkit.point(viewer.player());
                    else push(new RouteScreen<>(toolkit, viewer, commands));
                });
        open(RECORDINGS, "minecraft:music_disc_13", "Recordings", "Record, preview and play recordings", () -> new RecordingScreen<>(toolkit, viewer, commands));

        open(BEHAVIORS, "minecraft:lectern", "Behaviors", "Attach, run and trace behaviors", () -> new BehaviorScreen<>(toolkit, viewer, commands));
        open(SHOPS, "minecraft:emerald", "Shops", "Bind, open and reset shops", () -> new ShopBindScreen<>(toolkit, viewer, commands));
        open(GUIS, "minecraft:item_frame", "GUIs", "Chest GUIs, menus and holograms", () -> new GuiBindScreen<>(toolkit, viewer, commands));

        button(CLOSE, "minecraft:barrier", title("Close"), any("close the window"), (GuiModel.Click click) -> viewer.close());
    }

    /** A button every click opens the same way. */
    private void open(int slot, String icon, String name, String detail, Supplier<ToolScreen<P, S>> next) {
        button(slot, icon, title(name), lore(List.of("&7" + detail), any("open it")), (GuiModel.Click click) -> push(next.get()));
    }
}
