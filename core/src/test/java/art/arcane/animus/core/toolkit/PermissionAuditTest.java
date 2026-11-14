package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.RichText;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A player who holds only {@code animus.tools}. Every screen that needs more refuses to open and starts no work; every
 * button on the screens they may open, clicked every way and followed two screens deep, writes nothing, runs nothing and
 * never shows a screen they could not use. What they may not use is greyed with "No permission".
 */
class PermissionAuditTest extends ToolkitHarness {
    /** The titles of the screens {@code animus.tools} alone opens: Home, NPCs, the NPC screen, Movement and the folder picks. */
    private static final Set<String> OPEN = Set.of("Animus", "NPCs", "NPC", "guard", "baker", "2 NPCs", "Movement", "By folder", "Invert in folder");
    private static final List<Click> CLICKS = List.of(Click.LEFT, Click.RIGHT, Click.SHIFT_LEFT);
    private final List<String> asked = new ArrayList<>();

    private record Step(int slot, Click click) {}

    private record Refused(String name, String node, Toolkit.ScreenFactory<Player, Stack> factory) {}

    @BeforeEach void setUp() throws IOException {
        start(List.of(npcFile("guard", GUARD, ",\"tags\":[\"vip\"],\"groups\":[\"guards\"],\"behaviors\":[\"town:talk\"],\"hologram\":{\"lines\":[\"Welcome\"]},"
                        + "\"modules\":{\"animus:navigation\":{\"enabled\":true,\"route\":\"market\"}}"),
                new DocumentStore.Change("npcs/town/baker.json", "missing", npc("baker", BAKER, "").replace("minecraft:player", ShopFiles.VILLAGER)),
                file("groups/guards.json", "{\"schemaVersion\":2,\"revision\":1,\"id\":\"guards\",\"description\":\"Gate watch\",\"values\":{\"tags\":[\"armed\"]}}"),
                file("behaviors/town/talk.json", BehaviorScreensTest.fixture("talk")), file("shops/town/bakery.json", ToolkitShopScreensTest.BAKERY),
                file("gui/inventories/town/board.json", "{\"schemaVersion\":1,\"title\":\"Notice board\"}"),
                file("gui/holograms/town/sign.json", "{\"schemaVersion\":1,\"lines\":[\"Town board\"]}"),
                file("routes/market.json", ToolkitRoutesTest.MARKET), file("recordings/wave.json", ToolkitRoutesTest.recording("wave"))), true);
        active.put(GUARD, 3.0);
        active.put(BAKER, 6.0);
        alex.permissions.clear();
        alex.permissions.add(PermissionNodes.TOOLS);
        runs = (String npc) -> { asked.add(npc); return CompletableFuture.completedFuture(List.of()); };
    }

    /** Every file under the data folder with its hash, leaving out each player's saved menu mode, which the Mode lever writes. */
    private Map<String, String> files() throws IOException {
        Map<String, String> found = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (!relative.startsWith("state/toolkit/")) found.put(relative, JsonDocuments.hash(Files.readString(path)));
            }
        }
        return found;
    }

    @Test void everyScreenThatNeedsMoreStaysShutAndStartsNoWork() throws IOException {
        toolkit.selectOnly(alex.id, "guard");
        Map<String, String> before = files();
        ShopFiles.Gui board = new ShopFiles.Gui(DocumentKind.INVENTORY, "town:board", "gui/inventories/town/board.json", Optional.empty());
        ShopFiles.Gui sign = new ShopFiles.Gui(DocumentKind.HOLOGRAM, "town:sign", "gui/holograms/town/sign.json", Optional.empty());
        List<Refused> screens = List.of(
                new Refused("Talk", PermissionNodes.BEHAVIORS, TalkScreen::new),
                new Refused("Behaviors", PermissionNodes.BEHAVIORS, BehaviorScreen::new),
                new Refused("Runs", PermissionNodes.BEHAVIORS, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) ->
                        new BehaviorRunsScreen<>(kit, viewer, lent, "town:talk", List.of("guard"))),
                new Refused("Shops", PermissionNodes.SHOPS, ShopBindScreen::new),
                new Refused("GUIs", PermissionNodes.GUI, GuiBindScreen::new),
                new Refused("GUI", PermissionNodes.GUI, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) ->
                        new GuiDetailScreen<>(kit, viewer, lent, board, () -> {})),
                new Refused("Saved holograms", PermissionNodes.GUI, SavedHologramScreen::new),
                new Refused("Saved hologram", PermissionNodes.GUI, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) ->
                        new SavedHologramScreen.Detail<>(kit, viewer, lent, sign, () -> {})),
                new Refused("Routes", PermissionNodes.NPC_MOVE, RouteScreen::new),
                new Refused("Route", PermissionNodes.NPC_MOVE, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) ->
                        new RouteDetailScreen<>(kit, viewer, lent, "market", () -> {})),
                new Refused("Points", PermissionNodes.NPC_MOVE, PointsScreen::new),
                new Refused("Walk once", PermissionNodes.NPC_MOVE, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) ->
                        new PickScreen<>(kit, viewer, lent, PermissionNodes.NPC_MOVE, "Walk once", List.of("market"), (String route) -> new PickScreen.Look("minecraft:lead", route, ""), (String route) -> {})),
                new Refused("Recordings", PermissionNodes.RECORD, RecordingScreen::new),
                new Refused("Recording", PermissionNodes.RECORD, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) ->
                        new RecordingDetailScreen<>(kit, viewer, lent, "wave", () -> {})),
                new Refused("Skins", PermissionNodes.NPC_SKIN, SkinScreen::new),
                new Refused("Combat", PermissionNodes.COMBAT, CombatScreen::new),
                new Refused("Combat settings", PermissionNodes.COMBAT, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) ->
                        new SettingsScreen<>(kit, viewer, lent, CombatFields.combat("melee"))),
                new Refused("Target rules", PermissionNodes.COMBAT, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) ->
                        new TargetRulesScreen<>(kit, viewer, lent, "targets")),
                new Refused("Riding", PermissionNodes.NPC_EDIT, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) ->
                        new SettingsScreen<>(kit, viewer, lent, CombatFields.riding())),
                new Refused("Mounts", PermissionNodes.NPC_EDIT, MountScreen::new),
                new Refused("Equipment", PermissionNodes.NPC_EDIT, EquipmentScreen::new),
                new Refused("Hologram", PermissionNodes.NPC_EDIT, HologramScreen::new),
                new Refused("Hologram line", PermissionNodes.NPC_EDIT, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) ->
                        new HologramScreen.LineScreen<>(kit, viewer, lent, 0)),
                new Refused("Body", PermissionNodes.NPC_EDIT, BodyScreen::new),
                new Refused("Folders", PermissionNodes.NPC_EDIT, FolderScreen::new),
                new Refused("Trait groups", PermissionNodes.NPC_EDIT, GroupScreen::new),
                new Refused("Trait group", PermissionNodes.NPC_EDIT, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) ->
                        new GroupDetailScreen<>(kit, viewer, lent, "guards")),
                new Refused("Members", PermissionNodes.NPC_EDIT, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) ->
                        new GroupMembersScreen<>(kit, viewer, lent, "guards")),
                new Refused("All fields", PermissionNodes.NPC_EDIT, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) ->
                        new PropertyScreen<>(kit, viewer, lent, List.of("guard"))),
                new Refused("Attributes", PermissionNodes.NPC_EDIT, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) ->
                        new AttributeScreen<>(kit, viewer, lent, "Attributes", NpcScreen.scope(kit, List.of("guard")))),
                new Refused("Movement settings", PermissionNodes.NPC_EDIT, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) -> {
                    PropertyScreen.Section movement = PropertyScreen.SECTIONS.stream().filter((PropertyScreen.Section section) -> section.name().equals("Movement")).findFirst().orElseThrow();
                    return new FieldScreen<>(kit, viewer, lent, "Movement settings", PropertyScreen.section(PropertyScreen.SCHEMA, movement, Optional.empty()), NpcScreen.scope(kit, List.of("guard")), movement.path());
                }),
                new Refused("Modules", PermissionNodes.NPC_EDIT, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) ->
                        new ModuleScreen<>(kit, viewer, lent, "Modules", PropertyScreen.modules(PropertyScreen.SCHEMA), NpcScreen.scope(kit, List.of("guard")))),
                new Refused("Choice", PermissionNodes.NPC_EDIT, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) ->
                        new ChoiceScreen<>(kit, viewer, lent, "Team color", ChoiceScreen.values(List.of(new JsonPrimitive("red"))), null, (com.google.gson.JsonElement chosen) -> {})));
        for (Refused screen : screens) {
            alex.messages.clear();
            toolkit.open(alex.id, screen.factory());
            drain();
            assertNull(alex.window, screen.name() + " opened for a player without " + screen.node());
            assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("No permission: " + screen.node())), screen.name() + ": " + alex.messages);
        }
        toolkit.selectOnly(alex.id, "baker");
        toolkit.open(alex.id, TalkScreen::new);
        drain();
        assertNull(alex.window, "Talk for an NPC without a conversation makes nothing for a player without animus.behaviors");
        assertEquals(before, files(), "a refused screen writes nothing");
        assertTrue(host.console.isEmpty(), "nor runs anything as the console: " + host.console);
        assertTrue(asked.isEmpty(), "nor asks for runs: " + asked);
    }

    @Test void theNpcScreenGreysAttributesMovementSettingsAndTalkAndTheyDoNothing() throws IOException {
        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
        toolkit.selectOnly(alex.id, "guard");
        Map<String, String> before = files();
        toolkit.open(alex.id);
        drain();
        assertEquals("guard", title());
        for (Map.Entry<Integer, String> greyed : Map.of(NpcScreen.ATTRIBUTES, PermissionNodes.NPC_EDIT, NpcScreen.TALK, PermissionNodes.BEHAVIORS,
                NpcScreen.FIELDS, PermissionNodes.NPC_EDIT, NpcScreen.DELETE, PermissionNodes.NPC_DELETE).entrySet()) {
            assertEquals("No permission\n" + greyed.getValue(), lore(greyed.getKey()), name(greyed.getKey()));
            click(greyed.getKey(), Click.LEFT);
            drain();
            assertEquals("guard", title(), name(greyed.getKey()) + " opened something");
        }
        click("Movement", Click.LEFT);
        drain();
        assertEquals("Movement", title());
        for (Map.Entry<Integer, String> greyed : Map.of(MovementScreen.SETTINGS, PermissionNodes.NPC_EDIT, MovementScreen.ROUTE, PermissionNodes.NPC_MOVE,
                MovementScreen.RECORDING, PermissionNodes.RECORD, MovementScreen.STILL, PermissionNodes.NPC_MOVE).entrySet()) {
            assertEquals("No permission\n" + greyed.getValue(), lore(greyed.getKey()), name(greyed.getKey()));
            click(greyed.getKey(), Click.LEFT);
            click(greyed.getKey(), Click.LEFT);
            drain();
            assertEquals("Movement", title(), name(greyed.getKey()) + " opened something");
        }
        assertEquals(before, files());
        assertTrue(host.console.isEmpty(), host.console.toString());
    }

    @Test void everyButtonTheToolsNodeShowsIsSafeTwoScreensDeep() throws IOException {
        Map<String, String> before = files();
        List<String> broken = new ArrayList<>();
        for (ToolkitMode mode : ToolkitMode.values()) {
            for (String start : List.of("home", "npc", "wand", "npcs")) explore(mode, start, List.of(), 0, broken);
        }
        assertTrue(broken.isEmpty(), String.join("\n", broken));
        assertEquals(before, files(), "nothing was written");
        assertTrue(host.console.isEmpty(), "nothing ran as the console: " + host.console);
        assertTrue(alex.commands.isEmpty(), "nothing ran as the player: " + alex.commands);
        assertTrue(calls.isEmpty(), "no NPC was moved, stopped or revived: " + calls);
    }

    @Test void theHiddenRouteChipsNeedTheMoveNode() {
        for (String step : List.of("add", "undo", "save", "cancel")) {
            toolkit.route(alex.id, step, "");
            assertTrue(alex.messages.getLast().contains("No permission: animus.npc.move"), step + ": " + alex.messages.getLast());
        }
        assertTrue(toolkit.captures().capture(alex.id).isEmpty());
    }

    /** Opens the start screen, replays {@code path}, then clicks every button every way and follows the screens it may open. */
    private void explore(ToolkitMode mode, String start, List<Step> path, int depth, List<String> broken) {
        Map<Integer, String> shown = replay(mode, start, path);
        if (shown == null) return;
        String here = title();
        for (int slot : shown.keySet()) {
            for (Click click : CLICKS) {
                if (replay(mode, start, path) == null) return;
                click(slot, click);
                drain();
                if (alex.prompt != null) {
                    MockScreenHost.answer(alex, Optional.of("audit"));
                    drain();
                }
                if (alex.window == null) continue;
                String now = title();
                List<Step> next = new ArrayList<>(path);
                next.add(new Step(slot, click));
                if (!OPEN.contains(now)) { broken.add(mode + " " + start + " " + next + " opened " + now + " from " + here + " via " + shown.get(slot)); continue; }
                if (depth < 2 && !now.equals(here)) explore(mode, start, next, depth + 1, broken);
            }
        }
    }

    /** The start screen with {@code path} clicked again, or null when the path no longer leads to an open window. */
    private Map<Integer, String> replay(ToolkitMode mode, String start, List<Step> path) {
        toolkit.mode(alex.id, mode);
        toolkit.selectOnly(alex.id, "guard");
        manager.close(alex);
        alex.prompt = null;
        now += Toolkit.REPEAT_NANOS;
        switch (start) {
            case "home" -> toolkit.home(alex.id);
            case "npc" -> toolkit.open(alex.id);
            case "wand" -> toolkit.interacted(alex, GUARD, false, false);
            default -> toolkit.open(alex.id, NpcsScreen::new);
        }
        drain();
        for (Step step : path) {
            if (alex.window == null) return null;
            click(step.slot(), step.click());
            drain();
            if (alex.prompt != null) {
                MockScreenHost.answer(alex, Optional.of("audit"));
                drain();
            }
        }
        if (alex.window == null) return null;
        Map<Integer, String> shown = new TreeMap<>();
        for (Map.Entry<Integer, ScreenFrame.Item> item : alex.frame.items().entrySet())
            if (!item.getValue().item().id().equals(ToolScreen.FILLER)) shown.put(item.getKey(), item.getValue().name().map(RichText::plain).orElse(""));
        return shown;
    }
}
