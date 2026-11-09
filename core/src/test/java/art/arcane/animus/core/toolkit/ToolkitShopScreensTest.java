package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.gui.FakeNpcRuntime;
import art.arcane.animus.core.gui.GuiBinding;
import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.shop.ShopBinding;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.RichText;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The shop and GUI binding screens: module writes, settings, commands they run and guarded deletes. */
class ToolkitShopScreensTest {
    static final UUID GUARD = UUID.fromString("12345678-1234-1234-1234-123456789abc"), BAKER = UUID.fromString("22345678-1234-1234-1234-123456789abc");
    static final String BAKERY = "{\"schemaVersion\":1,\"revision\":1,\"id\":\"town:bakery\",\"style\":\"chest\",\"pages\":[{\"name\":\"Bread\",\"buy\":{"
            + "\"0\":{\"id\":\"bread\",\"type\":\"static\",\"item\":{\"id\":\"minecraft:bread\"}},\"1\":{\"id\":\"cake\",\"type\":\"static\",\"item\":{\"id\":\"minecraft:cake\"}}}}]}";
    @TempDir Path root;
    DocumentStore store;
    NpcRepository npcs;
    final MockScreenHost host = new MockScreenHost();
    final Player alex = new Player("Alex");
    final ScreenManager<Player, Stack> manager = new ScreenManager<>(host, (UUID npc) -> true, (String warning) -> { throw new AssertionError(warning); });
    Toolkit<Player, Stack> toolkit;
    final Toolkit.Commands commands = new Toolkit.Commands((CommandActor actor, String id) -> {}, (CommandActor actor) -> {}, (CommandActor actor, String id) -> {});

    @BeforeEach void setUp() throws IOException {
        store = new DocumentStore(root);
        String villager = ToolkitScreensTest.npc("baker", BAKER, "").replace("minecraft:player", ShopFiles.VILLAGER);
        store.commit(List.of(
                new DocumentStore.Change("npcs/town/guard.json", "missing", ToolkitScreensTest.npc("guard", GUARD, "")),
                new DocumentStore.Change("npcs/town/baker.json", "missing", villager),
                new DocumentStore.Change("shops/town/bakery.json", "missing", BAKERY),
                new DocumentStore.Change("gui/inventories/town/board.json", "missing", "{\"schemaVersion\":1,\"title\":\"Notice board\"}"),
                new DocumentStore.Change("gui/menus/town/main.json", "missing", "{\"schemaVersion\":1}"),
                new DocumentStore.Change("gui/holograms/town/sign.json", "missing", "{\"schemaVersion\":1,\"lines\":[\"|animation.blink| Open\"]}"),
                new DocumentStore.Change("gui/text-animations/town/blink.json", "missing", "{\"schemaVersion\":1,\"frames\":[\"a\",\"b\"]}")));
        npcs = new NpcRepository(store);
        assertTrue(npcs.reload().isEmpty());
        alex.permissions.add(Toolkit.ADMIN);
        alex.pose = new ScreenHost.Pose(Set.of("world"), 0, 64, 0, 65.62, 0, 0, false);
        alex.wand = true;
        FakeNpcRuntime<Player> living = new FakeNpcRuntime<>((UUID id) -> id.equals(alex.id) ? Optional.of(alex) : Optional.empty()) {
            @Override public Optional<ActiveNpc> active(UUID npc) {
                if (npc.equals(GUARD)) return Optional.of(new ActiveNpc("guard", "guard", ToolkitScreensTest.at(GUARD, 3)));
                if (npc.equals(BAKER)) return Optional.of(new ActiveNpc("baker", "baker", ToolkitScreensTest.at(BAKER, 6)));
                return Optional.empty();
            }
        };
        toolkit = Toolkit.install(new Toolkit.Parts<>(() -> npcs, () -> store, manager, living, Runnable::run, (Player player) -> Optional.empty(), Optional.empty(), System::nanoTime));
    }

    @AfterEach void tearDown() throws IOException { toolkit.close(); store.close(); }

    void selectBoth() {
        assertTrue(toolkit.interacted(alex, GUARD, false, false));
        assertTrue(toolkit.interacted(alex, BAKER, false, true));
        assertEquals(List.of("guard", "baker"), toolkit.selected(alex.id));
    }

    void click(String name, Click click) { click(MockScreenHost.slot(alex, name), click); }

    void click(int slot, Click click) { manager.click(alex, alex.window, slot, click); }

    /** A guarded button: the first click arms it, the second runs it. */
    void twice(int slot) { click(slot, Click.LEFT); click(slot, Click.LEFT); }

    String name(String part) { return RichText.plain(alex.frame.items().get(MockScreenHost.slot(alex, part)).name().orElseThrow()); }

    String lore(String name) {
        ScreenFrame.Item item = alex.frame.items().get(MockScreenHost.slot(alex, name));
        return String.join("\n", item.lore().stream().map(RichText::plain).toList());
    }

    JsonObject definition(String id) { return npcs.stored().stream().filter((art.arcane.animus.core.npc.NpcDefinition found) -> found.id().equals(id)).findFirst().orElseThrow().json(); }

    JsonObject module(String id, String module) { return definition(id).getAsJsonObject("modules").getAsJsonObject(module); }

    String last() { return alex.messages.getLast(); }

    @Test void aShopBindsToTheSelectionInOneTransactionKeepingItsSettings() throws IOException {
        String withStyle = ToolkitScreensTest.npc("guard", GUARD, ",\"modules\":{\"animus:shop\":{\"shop\":\"town:old\",\"openOn\":\"left\",\"style\":\"native\"}}");
        store.commit(List.of(new DocumentStore.Change("npcs/town/guard.json", JsonDocuments.hash(store.read("npcs/town/guard.json")), withStyle)));
        assertTrue(npcs.reload().isEmpty());
        selectBoth();
        toolkit.open(alex.id, commands, ShopBindScreen::new);
        assertEquals("Shops", RichText.plain(alex.frame.title()));
        assertTrue(lore("town:bakery").contains("Offers 2, window chest"), lore("town:bakery"));
        assertTrue(lore("town:bakery").contains("In the selection 0 of 2"), lore("town:bakery"));
        assertTrue(lore("town:bakery").contains("Left bind to the selection") && lore("town:bakery").contains("Right open it for you now") && lore("town:bakery").contains("Sneak settings"));
        click("town:bakery", Click.LEFT);
        assertTrue(last().contains("Shop town:bakery on 2 NPCs; tx "), alex.messages.toString());
        JsonObject guard = module("guard", ShopBinding.MODULE);
        assertEquals("town:bakery", guard.get("shop").getAsString());
        assertEquals("left", guard.get("openOn").getAsString(), "an existing open click stays");
        assertEquals("native", guard.get("style").getAsString(), "an existing style stays");
        ShopBinding binding = ShopBinding.parse(definition("guard")).orElseThrow();
        assertEquals(ShopBinding.OpenOn.LEFT, binding.openOn());
        assertEquals("town:bakery", ShopBinding.parse(definition("baker")).orElseThrow().shop());
        assertTrue(lore("town:bakery").contains("In the selection 2 of 2"), lore("town:bakery"));
    }

    @Test void aRightClickOpensTheShopAndTheBottomRowReloads() {
        toolkit.open(alex.id, commands, ShopBindScreen::new);
        click("town:bakery", Click.RIGHT);
        assertEquals(List.of("animus shop open town:bakery"), alex.commands);
        assertNull(alex.window, "the toolkit closes so the shop can open");
        toolkit.open(alex.id, commands, ShopBindScreen::new);
        click("Reload shops", Click.LEFT);
        assertEquals("animus shop reload", alex.commands.getLast());
    }

    @Test void settingsCycleStyleAndOpenClickAndVillagerLooks() {
        selectBoth();
        toolkit.open(alex.id, commands, ShopBindScreen::new);
        click("town:bakery", Click.LEFT);
        click("town:bakery", Click.SHIFT_LEFT);
        assertEquals("Shop town:bakery", RichText.plain(alex.frame.title()));
        assertEquals("Window: default", name("Window"));
        click("Window", Click.LEFT);
        assertEquals("native", module("guard", ShopBinding.MODULE).get("style").getAsString());
        assertEquals(Optional.of(ShopDocument.Style.NATIVE), ShopBinding.parse(definition("baker")).orElseThrow().style());
        click("Window", Click.RIGHT);
        assertEquals("Window: default", name("Window"));
        assertNull(module("guard", ShopBinding.MODULE).get("style"), "default removes the field");
        click("Opens on", Click.LEFT);
        assertEquals("Opens on: left", name("Opens on"));
        assertEquals(ShopBinding.OpenOn.LEFT, ShopBinding.parse(definition("guard")).orElseThrow().openOn());
        click("Opens on", Click.RIGHT);
        click("Opens on", Click.RIGHT);
        assertEquals(ShopBinding.OpenOn.BOTH, ShopBinding.parse(definition("baker")).orElseThrow().openOn());

        click("Profession", Click.LEFT);
        assertTrue(last().contains("1 skipped"), "the player body is skipped: " + last());
        assertNull(module("guard", ShopBinding.MODULE).get("villager"));
        click("Level", Click.SHIFT_LEFT);
        ShopBinding.Villager looks = ShopBinding.parse(definition("baker")).orElseThrow().villager().orElseThrow();
        assertEquals(new ShopBinding.Villager("minecraft:armorer", 5), looks);
        assertEquals("Level: 5", name("Level"));
    }

    @Test void resetAndOpenRunTheShopCommands() {
        toolkit.open(alex.id, commands, ShopBindScreen::new);
        click("town:bakery", Click.SHIFT_LEFT);
        click(ShopDetailScreen.RESET, Click.LEFT);
        assertTrue(alex.commands.isEmpty(), "reset asks twice");
        click(ShopDetailScreen.RESET, Click.LEFT);
        assertEquals(List.of("animus shop reset town:bakery"), alex.commands);
        click("Open now", Click.LEFT);
        assertEquals("animus shop open town:bakery", alex.commands.getLast());
    }

    @Test void unbindAndDeleteRefusedWhileBound() throws IOException {
        selectBoth();
        toolkit.open(alex.id, commands, ShopBindScreen::new);
        click("town:bakery", Click.LEFT);
        click("town:bakery", Click.SHIFT_LEFT);
        twice(ShopDetailScreen.DELETE);
        assertTrue(last().contains("is bound to baker, guard"), alex.messages.toString());
        assertNotNull(store.read("shops/town/bakery.json"));
        click("Unbind from selection", Click.LEFT);
        assertTrue(last().contains("Unbound town:bakery from 2 NPCs; tx "), alex.messages.toString());
        assertTrue(ShopBinding.parse(definition("guard")).isEmpty());
        assertNull(definition("guard").get("modules"), "an empty modules object is removed");
        twice(ShopDetailScreen.DELETE);
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("Deleted shops/town/bakery.json; tx ")), alex.messages.toString());
        assertNull(store.read("shops/town/bakery.json"));
        assertEquals("animus shop reload", alex.commands.getLast());
        assertEquals("Shops", RichText.plain(alex.frame.title()), "back on the list");
        assertTrue(lore("No shops yet").isEmpty());
    }

    @Test void aNewShopIsTheSmallestValidDocument() throws IOException {
        toolkit.open(alex.id, commands, ShopBindScreen::new);
        assertTrue(lore("New shop").contains("add offers") && lore("New shop").contains("web editor"), lore("New shop"));
        click("New shop", Click.LEFT);
        assertEquals("Shop id", alex.prompt.label());
        MockScreenHost.answer(alex, Optional.of("town:smith"));
        String written = store.read("shops/town/smith.json");
        assertNotNull(written);
        ShopDocument.Result parsed = ShopDocument.parse("town:smith", written);
        assertEquals(List.of(), parsed.problems());
        assertEquals(1, parsed.shop().orElseThrow().pages().size());
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("Created shops/town/smith.json; add offers in the web editor; tx ")), alex.messages.toString());
        assertEquals("animus shop reload", alex.commands.getLast());
        assertTrue(lore("town:smith").contains("Offers 0"), lore("town:smith"));

        click("New shop", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("Not An Id"));
        assertTrue(alex.messages.getLast().contains("is not a valid shop id"), alex.messages.toString());
        click("New shop", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("town:smith"));
        assertTrue(alex.messages.getLast().contains("already exists"), alex.messages.toString());
    }

    @Test void guisBindByKindAndPreviewThroughTheGuiCommands() {
        selectBoth();
        toolkit.open(alex.id, commands, GuiBindScreen::new);
        assertEquals("GUIs: Holograms", RichText.plain(alex.frame.title()), "holograms come first");
        click("Chest GUIs", Click.LEFT);
        assertEquals("GUIs: Chest GUIs", RichText.plain(alex.frame.title()));
        assertTrue(lore("town:board").contains("Notice board"), lore("town:board"));
        click("town:board", Click.LEFT);
        assertTrue(last().contains("Opens inventory town:board on 2 NPCs; tx "), alex.messages.toString());
        GuiBinding.Open open = GuiBinding.parse(definition("guard")).orElseThrow().open().orElseThrow();
        assertEquals(new GuiBinding.Open(GuiBinding.Kind.INVENTORY, "town:board", GuiBinding.OpenOn.RIGHT), open);
        click("town:board", Click.RIGHT);
        assertEquals(List.of("animus gui open town:board"), alex.commands);

        toolkit.open(alex.id, commands, GuiBindScreen::new);
        click("Menus", Click.LEFT);
        assertEquals("GUIs: Menus", RichText.plain(alex.frame.title()));
        click("town:main", Click.LEFT);
        assertEquals(GuiBinding.Kind.MENU, GuiBinding.parse(definition("baker")).orElseThrow().open().orElseThrow().kind());
        click("town:main", Click.RIGHT);
        assertEquals("animus gui menu town:main", alex.commands.getLast());

        toolkit.open(alex.id, commands, GuiBindScreen::new);
        click("Holograms", Click.LEFT);
        click("town:sign", Click.LEFT);
        GuiBinding both = GuiBinding.parse(definition("guard")).orElseThrow();
        assertEquals("town:sign", both.hologram().orElseThrow().id());
        assertEquals("town:main", both.open().orElseThrow().id(), "the hologram sits beside the open target");
        click("Reload GUIs", Click.LEFT);
        assertEquals("animus gui reload", alex.commands.getLast());
    }

    @Test void guiDetailsCycleTheClickStepTheOffsetAndGuardDeletes() throws IOException {
        selectBoth();
        toolkit.open(alex.id, commands, GuiBindScreen::new);
        click("Chest GUIs", Click.LEFT);
        click("town:board", Click.LEFT);
        click("town:board", Click.SHIFT_LEFT);
        assertEquals("Chest GUI town:board", RichText.plain(alex.frame.title()));
        click("Opens on", Click.LEFT);
        assertEquals(GuiBinding.OpenOn.LEFT, GuiBinding.parse(definition("baker")).orElseThrow().open().orElseThrow().openOn());
        twice(GuiDetailScreen.DELETE);
        assertTrue(last().contains("inventory town:board is used by baker, guard"), alex.messages.toString());
        click("Unbind from selection", Click.LEFT);
        assertTrue(GuiBinding.parse(definition("guard")).isEmpty());
        manager.click(alex, alex.window, MockScreenHost.slot(alex, "Back"), Click.LEFT);
        click("Holograms", Click.LEFT);
        click("town:sign", Click.LEFT);
        click("town:sign", Click.RIGHT);
        assertEquals("Hologram town:sign", RichText.plain(alex.frame.title()));
        assertEquals("Offset Y: auto", name("Offset Y"));
        click("Offset Y", Click.LEFT);
        assertTrue(last().contains("Hologram town:sign offset y 2.5 on 2 NPCs; tx "), last());
        double[] offset = GuiBinding.parse(definition("guard")).orElseThrow().hologram().orElseThrow().offset().orElseThrow();
        assertEquals(List.of(0.0, 2.5, 0.0), List.of(offset[0], offset[1], offset[2]));
        click("Offset X", Click.SHIFT_RIGHT);
        offset = GuiBinding.parse(definition("baker")).orElseThrow().hologram().orElseThrow().offset().orElseThrow();
        assertEquals(List.of(-1.0, 2.5, 0.0), List.of(offset[0], offset[1], offset[2]));
        click("Above the head", Click.LEFT);
        assertTrue(GuiBinding.parse(definition("guard")).orElseThrow().hologram().orElseThrow().offset().isEmpty());
    }

    @Test void aTextAnimationInUseIsNotDeleted() {
        toolkit.open(alex.id, commands, GuiBindScreen::new);
        click("Text animations", Click.LEFT);
        assertTrue(lore("town:blink").contains("|animation.town:blink|"), lore("town:blink"));
        click("town:blink", Click.LEFT);
        assertTrue(lore("town:blink").contains("Used by town:sign"), lore("town:blink"));
        twice(GuiDetailScreen.DELETE);
        assertTrue(last().contains("is used by town:sign"), alex.messages.toString());
    }
}
