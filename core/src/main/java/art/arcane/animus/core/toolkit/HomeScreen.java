package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The Animus menu's first screen ({@code /animus gui}, {@code /animus tools} with nothing selected, or the wand on air): make an NPC, find
 * one, take the wand, pair the web editor. Advanced adds the library row. A running route capture or recording shows
 * its finish button on top.
 */
final class HomeScreen<P, S> extends ToolScreen<P, S> {
    static final int RUNNING = 4, NEW = 10, NPCS = 12, WAND = 14, EDITOR = 16,
            FOLDERS = 18, BEHAVIORS = 19, SHOPS = 20, ROUTES = 21, RECORDINGS = 22, HOLOGRAMS = 23, GUIS = 24, SKINS = 25, GROUPS = 26;

    HomeScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }

    @Override String title() { return "Animus"; }
    @Override int rows() { return advanced() ? 4 : 3; }

    @Override void draw() {
        running(this, RUNNING);
        gated(NEW, PermissionNodes.NPC_CREATE, "minecraft:egg", "New NPC", lore(List.of("&7Make one where you stand"), any("name it")), (GuiModel.Click click) -> CreateFlow.start(this));
        button(NPCS, "minecraft:compass", title("NPCs"), lore(List.of("&7Find and pick"), any("open")), (GuiModel.Click click) -> push(new NpcsScreen<>(toolkit, viewer, commands)));
        button(WAND, Toolkit.WAND_ITEM, title("Wand"), lore(List.of("&7Right-click an NPC to edit it"), any("take one")), (GuiModel.Click click) -> toolkit.giveWand(viewer.player()));
        gated(EDITOR, PermissionNodes.EDITOR_PAIR, "minecraft:ender_eye", "Web editor", lore(List.of("&7Pair your browser", "&7Shows in chat"), any("get a link")), (GuiModel.Click click) -> {
            if (!allows(PermissionNodes.EDITOR_PAIR)) return;
            viewer.close();
            commands.pair().accept(toolkit.actor(viewer.player()));
        });
        if (advanced()) {
            library(FOLDERS, "minecraft:chest", "Folders", "Where NPC files are kept", () -> new FolderScreen<>(toolkit, viewer, commands));
            library(BEHAVIORS, "minecraft:lectern", "Behaviors", "What NPCs do when something happens", () -> new BehaviorScreen<>(toolkit, viewer, commands));
            library(SHOPS, "minecraft:emerald", "Shops", "Trade windows NPCs open", () -> new ShopBindScreen<>(toolkit, viewer, commands));
            library(ROUTES, "minecraft:lead", "Routes", "Walks point to point.", () -> new RouteScreen<>(toolkit, viewer, commands));
            library(RECORDINGS, "minecraft:music_disc_13", "Recordings", "Replays what you did.", () -> new RecordingScreen<>(toolkit, viewer, commands));
            library(HOLOGRAMS, "minecraft:armor_stand", "Holograms", "Floating text above NPCs", () -> new SavedHologramScreen<>(toolkit, viewer, commands));
            library(GUIS, "minecraft:item_frame", "GUIs", "Chest windows you design", () -> new GuiBindScreen<>(toolkit, viewer, commands));
            library(SKINS, HEAD, "Skins", "Player looks for player bodies", () -> new SkinScreen<>(toolkit, viewer, commands));
            library(GROUPS, "minecraft:book", "Trait groups", "Settings shared by many NPCs", () -> new GroupScreen<>(toolkit, viewer, commands));
        }
        closeButton();
        modeLever();
        help("Make an NPC with New NPC, or pick one in NPCs to change it. /animus gui opens this menu; the wand selects NPCs in the world.", "21-in-game-toolkit", "");
    }

    /** While the player captures a route or records: the button that finishes it, on Home and on the NPC screen alike. */
    static <P, S> void running(ToolScreen<P, S> screen, int slot) {
        ScreenManager<P, S>.Viewer viewer = screen.viewer;
        Optional<WaypointCapture> capture = screen.toolkit.captures().capture(viewer.id());
        if (capture.isPresent()) {
            int points = capture.get().points().size();
            screen.opens(slot, "minecraft:lead", "Finish route (" + MovementScreen.points(points) + ")", lore(List.of("&7Check the points, then save"), any("open")),
                    () -> new PointsScreen<>(screen.toolkit, viewer, screen.commands));
        } else if (screen.commands.recording().test(viewer.id()))
            screen.button(slot, "minecraft:red_dye", title("Stop recording"), lore(List.of("&7Saves what you recorded"), any("stop")),
                    (GuiModel.Click click) -> {
                        viewer.host().command(Optional.of(viewer.player()), "animus record stop");
                        viewer.schedule(RecordingScreen.SAVE_DELAY, viewer::refresh);
                    });
    }

    private void library(int slot, String icon, String name, String what, Supplier<ToolScreen<P, S>> next) {
        opens(slot, icon, name, lore(List.of("&7" + what), any("open")), next);
    }
}
