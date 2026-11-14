package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.gui.GuiBinding;
import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.shop.ShopBinding;
import art.arcane.animus.core.config.ShopSettings;
import art.arcane.animus.core.shop.Prices;
import art.arcane.animus.core.shop.PricesTest;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The shop list, a shop's details and offers, and the Advanced GUI screens. */
class ToolkitShopScreensTest extends ToolkitHarness {
    static final String BAKERY = "{\"schemaVersion\":1,\"revision\":1,\"id\":\"town:bakery\",\"title\":\"Bakery\",\"style\":\"chest\",\"pages\":[{\"name\":\"Bread\",\"buy\":{"
            + "\"0\":{\"id\":\"bread\",\"type\":\"static\",\"item\":{\"id\":\"minecraft:bread\"}},\"1\":{\"id\":\"cake\",\"type\":\"static\",\"item\":{\"id\":\"minecraft:cake\"}}}}]}";
    @BeforeEach void setUp() throws IOException {
        String villager = npc("baker", BAKER, "").replace("minecraft:player", ShopFiles.VILLAGER);
        start(List.of(npcFile("guard", GUARD, ""),
                new DocumentStore.Change("npcs/town/baker.json", "missing", villager),
                new DocumentStore.Change("shops/town/bakery.json", "missing", BAKERY),
                new DocumentStore.Change("gui/inventories/town/board.json", "missing", "{\"schemaVersion\":1,\"title\":\"Notice board\"}"),
                new DocumentStore.Change("gui/menus/town/main.json", "missing", "{\"schemaVersion\":1}"),
                new DocumentStore.Change("gui/holograms/town/sign.json", "missing", "{\"schemaVersion\":1,\"lines\":[\"|animation.blink| Open\"]}"),
                file("gui/text-animations/town/blink.json", "{\"schemaVersion\":1,\"frames\":[\"a\",\"b\"]}")), false);
        active.put(GUARD, 3.0);
        active.put(BAKER, 6.0);
    }

    void selectBoth() {
        toolkit.selectOnly(alex.id, "guard");
        toolkit.selection().toggle(alex.id, "baker");
        assertEquals(List.of("guard", "baker"), toolkit.selected(alex.id));
    }

    void type(String text) { MockScreenHost.answer(alex, Optional.of(text)); }

    JsonObject definition(String id) { return npcs.stored().stream().filter((NpcDefinition found) -> found.id().equals(id)).findFirst().orElseThrow().json(); }

    JsonObject module(String id, String module) { return definition(id).getAsJsonObject("modules").getAsJsonObject(module); }

    String last() { return alex.messages.getLast(); }

    void bind(String id) throws IOException { new ShopFiles(npcs, store).bindShop("town:bakery", List.of(id)); }

    void details() {
        toolkit.open(alex.id, ShopBindScreen::new);
        click("Bakery", Click.RIGHT);
        assertEquals("Bakery", title());
    }

    @Test void theListShowsNameOffersAndWhoUsesItAndAdvancedAddsReload() {
        toolkit.selectOnly(alex.id, "guard");
        toolkit.open(alex.id, ShopBindScreen::new);
        assertEquals("Shops", title());
        assertEquals(Map.of(0, "Bakery", ShopBindScreen.CREATE, "New shop", ToolScreen.BACK, "Back", ToolScreen.SEARCH, "Search", ToolScreen.HELP, "Help"), buttons());
        assertEquals("2 offers, on no NPC\nClick: use on guard\nRight-click: details", lore("Bakery"), "a shop nothing opens says so");
        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
        toolkit.open(alex.id, ShopBindScreen::new);
        assertEquals("Reload", name(ShopBindScreen.RELOAD));
        assertTrue(lore("Bakery").contains("town:bakery"), lore("Bakery"));
        click("Reload", Click.LEFT);
        assertEquals(ShopBindScreen.RELOAD_COMMAND, alex.commands.getLast());
    }

    @Test void aClickPutsTheShopOnTheSelectionKeepingItsSettings() throws IOException {
        String withStyle = npc("guard", GUARD, ",\"modules\":{\"animus:shop\":{\"shop\":\"town:old\",\"openOn\":\"left\",\"style\":\"native\"}}");
        store.commit(List.of(new DocumentStore.Change("npcs/town/guard.json", JsonDocuments.hash(store.read("npcs/town/guard.json")), withStyle)));
        assertTrue(npcs.reload().isEmpty());
        selectBoth();
        toolkit.open(alex.id, ShopBindScreen::new);
        int bakery = MockScreenHost.slot(alex, "Bakery");
        click("Bakery", Click.LEFT);
        assertEquals(ToolScreen.CONFIRM, name(bakery), "guard opens another shop, so it asks first");
        assertTrue(lore(bakery).contains("Bakery replaces town:old"), lore(bakery));
        assertEquals("town:old", module("guard", ShopBinding.MODULE).get("shop").getAsString(), "nothing changed yet");
        click(bakery, Click.LEFT);
        assertTrue(last().contains("Shop Bakery on 2 NPCs"), "the shop by its title: " + alex.messages);
        JsonObject guard = module("guard", ShopBinding.MODULE);
        assertEquals("town:bakery", guard.get("shop").getAsString());
        assertEquals("left", guard.get("openOn").getAsString(), "an existing open click stays");
        assertEquals("native", guard.get("style").getAsString(), "an existing style stays");
        assertEquals("town:bakery", ShopBinding.parse(definition("baker")).orElseThrow().shop());
        assertTrue(lore("Bakery").contains("Now: on 2 of 2"), lore("Bakery"));
    }

    @Test void theDetailsGreySettingsUntilTheShopIsOnAnNpcAndOpenRunsTheCommand() {
        toolkit.selectOnly(alex.id, "guard");
        details();
        assertEquals(Map.ofEntries(Map.entry(ShopDetailScreen.HEADER, "Bakery"), Map.entry(ShopDetailScreen.ADD, "Add held item"), Map.entry(ShopDetailScreen.OFFERS, "Offers"),
                Map.entry(ShopDetailScreen.OPEN, "Open"), Map.entry(ShopDetailScreen.STYLE, "Window style"), Map.entry(ShopDetailScreen.OPEN_ON, "Opens on"),
                Map.entry(ShopDetailScreen.RESET, "Reset stock"), Map.entry(ShopDetailScreen.REMOVE, "Remove from NPC"), Map.entry(ShopDetailScreen.DELETE, "Delete"),
                Map.entry(ToolScreen.BACK, "Back"), Map.entry(ToolScreen.HELP, "Help")), buttons());
        assertEquals("Put it on an NPC first", lore("Window style"));
        assertEquals("Put it on an NPC first", lore("Opens on"));
        click("Window style", Click.LEFT);
        assertTrue(alex.messages.isEmpty(), "a grey setting does nothing");
        click(ShopDetailScreen.OPEN, Click.LEFT);
        assertEquals("animus shop open town:bakery", alex.commands.getLast());
        assertNull(alex.window, "the menu closes so the shop can open");
    }

    @Test void openShowsTheWindowANpcGivesFromItsMenuOrTheShopList() throws IOException {
        bind("baker");
        toolkit.selectOnly(alex.id, "baker");
        details();
        assertEquals("Opens it as players see it\nFrom: baker\nClick: open it", lore(ShopDetailScreen.OPEN));
        click(ShopDetailScreen.OPEN, Click.LEFT);
        assertEquals("animus shop open town:bakery npc=baker", alex.commands.getLast(), "the NPC's own window style and villager look");
        toolkit.selection().clear(alex.id);
        details();
        click(ShopDetailScreen.OPEN, Click.LEFT);
        assertEquals("animus shop open town:bakery npc=baker", alex.commands.getLast(), "from the shop list, as the NPC that opens it");
        bind("guard");
        selectBoth();
        details();
        click(ShopDetailScreen.OPEN, Click.LEFT);
        assertEquals("animus shop open town:bakery npc=guard", alex.commands.getLast(), "the first selected NPC that opens it");
    }

    @Test void settingsCycleOnTheNpcsThatUseItAndVillagerLooksOnlyForVillagers() {
        selectBoth();
        toolkit.open(alex.id, ShopBindScreen::new);
        click("Bakery", Click.LEFT);
        click("Bakery", Click.RIGHT);
        assertTrue(lore("Window style").contains("Now: Shop default"), lore("Window style"));
        click("Window style", Click.LEFT);
        assertEquals("chest", module("guard", ShopBinding.MODULE).get("style").getAsString());
        assertTrue(lore("Window style").contains("Now: Chest window"), lore("Window style"));
        click("Window style", Click.LEFT);
        assertTrue(last().contains("Window set to Villager trades on 2 NPCs"), last());
        assertEquals(Optional.of(ShopDocument.Style.NATIVE), ShopBinding.parse(definition("baker")).orElseThrow().style());
        click("Window style", Click.RIGHT);
        assertEquals("chest", module("guard", ShopBinding.MODULE).get("style").getAsString(), "a right-click steps back");
        click("Opens on", Click.LEFT);
        assertTrue(lore("Opens on").contains("Now: Left-click"), lore("Opens on"));
        assertEquals(ShopBinding.OpenOn.LEFT, ShopBinding.parse(definition("guard")).orElseThrow().openOn());
        assertTrue(last().contains("Opens on set to Left-click on 2 NPCs"), last());

        click("Profession", Click.LEFT);
        assertTrue(last().contains("1 skipped"), "the player body is skipped: " + last());
        assertNull(module("guard", ShopBinding.MODULE).get("villager"));
        click("Level", Click.SHIFT_LEFT);
        assertEquals(new ShopBinding.Villager("minecraft:armorer", 5), ShopBinding.parse(definition("baker")).orElseThrow().villager().orElseThrow());
        assertTrue(lore("Level").contains("Now: 5"), lore("Level"));
        assertTrue(lore("Profession").contains("Now: Armorer"), lore("Profession"));

        toolkit.selectOnly(alex.id, "guard");
        click(ToolScreen.BACK, Click.LEFT);
        click("Bakery", Click.RIGHT);
        assertFalse(shows("Profession"), "a player body has no villager looks");
    }

    @Test void addHeldItemRefusesTheWandThenAsksThePriceAndNamesTheItem() throws IOException {
        details();
        assertTrue(lore("Add held item").contains("Hold the item, then open /animus gui"), "back to this NPC's screen: " + lore("Add held item"));
        assertTrue(lore("Add held item").contains("Click: type the price"), lore("Add held item"));
        click("Add held item", Click.LEFT);
        assertNull(alex.prompt, "the wand is what Alex holds");
        assertTrue(last().endsWith("Hold the item it should sell"), last());
        alex.wand = false;
        alex.worn.put(art.arcane.animus.core.npc.EquipmentSlot.MAIN_HAND, new art.arcane.animus.core.npc.NpcItem("minecraft:diamond_sword", 1, Optional.empty(), Optional.empty()));
        click("Add held item", Click.LEFT);
        assertEquals("Price in Coins", alex.prompt.label(), "the prompt names the server's money");
        assertEquals("One number; 2 reads as 2 Coins", alex.prompt.hint());
        type("1 2");
        assertTrue(last().contains("The price is one number"), last());
        click("Add held item", Click.LEFT);
        type("5");
        assertTrue(last().startsWith("[Animus]: Added Diamond sword for 5 Coins"), last());
        assertFalse(last().contains("slot") || last().contains("page") || last().contains("diamond_sword"), "no internal words: " + last());
        assertTrue(store.read("shops/town/bakery.json").contains("minecraft:diamond_sword"));
        assertEquals(ShopBindScreen.RELOAD_COMMAND, host.console.getLast());
        assertTrue(alex.commands.isEmpty(), "nothing typed as the player");
        assertTrue(lore("Offers").contains("Now: 3 offers"), lore("Offers"));
    }

    @Test void withoutAnEconomyThePriceIsStillAskedAndSaysPlayersCannotPay() {
        economy = art.arcane.animus.core.inventory.Economy.none("Vault is not installed");
        details();
        assertTrue(lore("Add held item").contains("No economy plugin, so players cannot pay"), lore("Add held item"));
        assertTrue(lore("Add held item").lines().count() <= ToolScreen.SIMPLE_LINES, "Simple: " + lore("Add held item"));
        alex.wand = false;
        alex.worn.put(art.arcane.animus.core.npc.EquipmentSlot.MAIN_HAND, new art.arcane.animus.core.npc.NpcItem("minecraft:bread", 1, Optional.empty(), Optional.empty()));
        click("Add held item", Click.LEFT);
        assertEquals("Price", alex.prompt.label());
        assertEquals("No economy plugin, so players cannot pay", alex.prompt.hint());
        type("2");
        assertTrue(last().startsWith("[Animus]: Added Bread for 2"), "priceFormat writes it: " + last());
        alex.worn.put(art.arcane.animus.core.npc.EquipmentSlot.MAIN_HAND, new art.arcane.animus.core.npc.NpcItem("minecraft:bread", 4, Optional.empty(), Optional.empty()));
        click("Add held item", Click.LEFT);
        type("2");
        assertTrue(last().startsWith("[Animus]: Added 4 Bread for 2"), "a stack reads as the Offers list names it: " + last());
    }

    @Test void thePricePromptNamesTheMoneyOrItsSymbol() {
        assertEquals("Price in Coins", ShopDetailScreen.priceLabel(new Prices(PricesTest.coins("Coins"), ShopSettings.DEFAULT)));
        assertEquals("Price ($)", ShopDetailScreen.priceLabel(new Prices(PricesTest.coins("$"), ShopSettings.DEFAULT)), "EssentialsX names its money $");
        assertEquals("Price", ShopDetailScreen.priceLabel(new Prices(PricesTest.coins("Platinum Sovereigns"), ShopSettings.DEFAULT)), "too long for the anvil title");
        assertEquals("Price", ShopDetailScreen.priceLabel(new Prices(art.arcane.animus.core.inventory.Economy.none("Vault is not installed"), ShopSettings.DEFAULT)));
    }

    @Test void offersReadTheirPriceInTheEconomysFormat() {
        ShopDocument shop = ShopDocument.parse("town:x", "{\"schemaVersion\":1,\"revision\":1,\"id\":\"town:x\",\"pages\":[{\"name\":\"a\",\"buy\":{"
                + "\"0\":{\"id\":\"bread\",\"type\":\"buy\",\"item\":{\"id\":\"minecraft:bread\"},\"price\":2}}}]}").shop().orElseThrow();
        ShopFiles.Offer offer = new ShopFiles.Offer(0, ShopDocument.Mode.BUY, 0, shop.pages().getFirst().grid(ShopDocument.Mode.BUY).get(0));
        assertEquals("Players buy it for 2 Coins", ShopOffersScreen.deal(offer, commands.prices().get()));
    }

    @Test void aConversationOnTheSameNpcIsNamedOnTheShopList() throws IOException {
        store.commit(List.of(file("behaviors/town/talk.json", BehaviorScreensTest.fixture("talk")),
                file("npcs/town/crier.json", npc("crier", java.util.UUID.fromString("72345678-1234-1234-1234-123456789abc"), ",\"behaviors\":[\"town:talk\"]"))));
        npcs.reload();
        toolkit.selectOnly(alex.id, "crier");
        toolkit.open(alex.id, ShopBindScreen::new);
        assertFalse(buttons().containsKey(ShopBindScreen.NOTE), "no shop on it yet");
        bind("crier");
        toolkit.open(alex.id, ShopBindScreen::new);
        assertEquals("Also has a conversation", name(ShopBindScreen.NOTE));
        assertEquals("Players get both", lore(ShopBindScreen.NOTE));
        click("Bakery", Click.RIGHT);
        click("Opens on", Click.LEFT);
        assertTrue(lore("Opens on").contains("Now: Left-click"), lore("Opens on"));
        click(ToolScreen.BACK, Click.LEFT);
        assertEquals("Shops", title());
        assertFalse(buttons().containsKey(ShopBindScreen.NOTE), "a left-click shop no longer shares the right-click with the talk");
    }

    @Test void offersListWhatTheShopTradesAndAClickTakesOneOutAfterAsking() throws IOException {
        details();
        assertTrue(lore("Offers").contains("Now: 2 offers"), lore("Offers"));
        click("Offers", Click.LEFT);
        assertEquals("Offers", title());
        assertEquals(Map.of(0, "Bread", 1, "Cake", ToolScreen.BACK, "Back", ToolScreen.HELP, "Help"), buttons());
        assertEquals("Display only\nClick: remove it", lore("Bread"));
        click("Bread", Click.LEFT);
        assertEquals(ToolScreen.CONFIRM, name(0));
        click(0, Click.LEFT);
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("Removed bread from town:bakery")), alex.messages.toString());
        JsonObject saved = JsonDocuments.object(store.read("shops/town/bakery.json"));
        assertFalse(saved.getAsJsonArray("pages").get(0).getAsJsonObject().getAsJsonObject("buy").has("0"));
        assertEquals(2, saved.get("revision").getAsInt());
        assertEquals(Map.of(0, "Cake", ToolScreen.BACK, "Back", ToolScreen.HELP, "Help"), buttons());
        assertEquals(ShopBindScreen.RELOAD_COMMAND, host.console.getLast());
        click(ToolScreen.BACK, Click.LEFT);
        assertTrue(lore("Offers").contains("Now: 1 offer"), lore("Offers"));
    }

    @Test void newShopAsksForANameAndOpensItsDetails() throws IOException {
        toolkit.selectOnly(alex.id, "guard");
        toolkit.open(alex.id, ShopBindScreen::new);
        click("New shop", Click.LEFT);
        assertEquals("Shop name", alex.prompt.label());
        type("Smith's Forge");
        assertEquals("Smith's Forge", title());
        ShopDocument made = ShopDocument.parse("town:smith-s-forge", store.read("shops/town/smith-s-forge.json")).shop().orElseThrow();
        assertEquals("Smith's Forge", made.title());
        assertEquals(1, made.pages().size());
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("Made shop Smith's Forge on guard")), alex.messages.toString());
        assertEquals(List.of(ShopBindScreen.RELOAD_COMMAND), host.console);
        assertEquals("town:smith-s-forge", module("guard", ShopBinding.MODULE).get("shop").getAsString(), "made from an NPC, it goes on that NPC");
        assertEquals(1, changes().size(), "one Undo takes the shop and its place on guard back");
        assertTrue(lore(ShopDetailScreen.HEADER).contains("Now: on guard"), lore(ShopDetailScreen.HEADER));
        assertTrue(lore("Window style").contains("Now: Shop default"), lore("Window style"));
        click(ToolScreen.BACK, Click.LEFT);
        click("New shop", Click.LEFT);
        type("Smith's Forge");
        assertNotNull(store.read("shops/town/smith-s-forge-2.json"), "a clash gets a number");
        click(ToolScreen.BACK, Click.LEFT);
        click("New shop", Click.LEFT);
        type("!!!");
        assertTrue(last().contains("needs a letter or a digit"), last());
    }

    @Test void aShopIsNamedAfterAnNpcInNoFolderAndOneForNoNpcGoesUnderShops() throws IOException {
        store.commit(List.of(new DocumentStore.Change("npcs/hermit.json", "missing",
                npc("hermit", java.util.UUID.fromString("52345678-1234-1234-1234-123456789abc"), "").replace("\"group\":\"town\"", "\"group\":\"\""))));
        npcs.reload();
        toolkit.selectOnly(alex.id, "hermit");
        toolkit.open(alex.id, ShopBindScreen::new);
        click("New shop", Click.LEFT);
        type("Fish Stall");
        assertNotNull(store.read("shops/hermit/fish-stall.json"), "hermit:fish-stall, not npcs:fish-stall");
        toolkit.selection().clear(alex.id);
        toolkit.open(alex.id, ShopBindScreen::new);
        click("New shop", Click.LEFT);
        type("Fish Stall");
        assertNotNull(store.read("shops/shops/fish-stall.json"), "with no NPC: shops:fish-stall");
    }

    @Test void villagerLooksComeFromTheVillagersAndEachClickChangesOnlyItsOwnKey() throws IOException {
        bind("guard");
        bind("baker");
        new ShopFiles(npcs, store).shopField("town:bakery", List.of("baker"), List.of("villager"), Optional.of(JsonDocuments.object("{\"profession\":\"minecraft:farmer\",\"level\":2}")), "Farmer");
        selectBoth();
        details();
        assertTrue(lore("Level").contains("Now: 2"), "the player body is not read for villager looks: " + lore("Level"));
        assertTrue(lore("Profession").contains("Now: Farmer"), lore("Profession"));
        click("Level", Click.LEFT);
        assertEquals(new ShopBinding.Villager("minecraft:farmer", 3), ShopBinding.parse(definition("baker")).orElseThrow().villager().orElseThrow(), "the level click keeps farmer");
    }

    @Test void twoVillagersThatDifferReadMixedAndAClickSetsTheStatedValueOnBothAfterAsking() throws IOException {
        String smith = npc("smith", java.util.UUID.fromString("62345678-1234-1234-1234-123456789abc"), "").replace("minecraft:player", ShopFiles.VILLAGER);
        store.commit(List.of(new DocumentStore.Change("npcs/town/smith.json", "missing", smith)));
        npcs.reload();
        bind("baker");
        bind("smith");
        ShopFiles files = new ShopFiles(npcs, store);
        files.shopField("town:bakery", List.of("baker"), List.of("villager", "profession"), Optional.of(new com.google.gson.JsonPrimitive("minecraft:farmer")), "Farmer");
        files.shopField("town:bakery", List.of("smith"), List.of("villager", "profession"), Optional.of(new com.google.gson.JsonPrimitive("minecraft:armorer")), "Armorer");
        files.shopField("town:bakery", List.of("smith"), List.of("villager", "level"), Optional.of(new com.google.gson.JsonPrimitive(4)), "4");
        toolkit.selectOnly(alex.id, "baker");
        toolkit.selection().toggle(alex.id, "smith");
        details();
        assertTrue(lore("Profession").contains("Now: Mixed"), lore("Profession"));
        assertTrue(lore("Profession").contains("Click: set all to None"), lore("Profession"));
        click("Level", Click.LEFT);
        assertEquals(ToolScreen.CONFIRM, name(ShopDetailScreen.LEVEL), "a Mixed row asks first");
        click(ShopDetailScreen.LEVEL, Click.LEFT);
        assertEquals(new ShopBinding.Villager("minecraft:farmer", 1), ShopBinding.parse(definition("baker")).orElseThrow().villager().orElseThrow());
        assertEquals(new ShopBinding.Villager("minecraft:armorer", 1), ShopBinding.parse(definition("smith")).orElseThrow().villager().orElseThrow(),
                "each keeps its own profession");
    }

    @Test void aTraitGroupThatUsesTheShopIsNamedAsATraitGroup() throws IOException {
        store.commit(List.of(new DocumentStore.Change("groups/sellers.json", "missing",
                "{\"schemaVersion\":2,\"revision\":1,\"id\":\"sellers\",\"values\":{\"modules\":{\"animus:shop\":{\"shop\":\"town:bakery\"}}}}")));
        npcs.reload();
        details();
        assertTrue(lore(ShopDetailScreen.HEADER).contains("Now: on trait group sellers"), lore(ShopDetailScreen.HEADER));
        assertTrue(lore(ShopDetailScreen.DELETE).contains("On trait group sellers"), lore(ShopDetailScreen.DELETE));
        assertFalse(lore(ShopDetailScreen.HEADER).contains("group:") || lore(ShopDetailScreen.DELETE).contains("group:"));
    }

    @Test void resetAsksOnceAndDeleteWaitsUntilNothingUsesTheShop() throws IOException {
        selectBoth();
        toolkit.open(alex.id, ShopBindScreen::new);
        click("Bakery", Click.LEFT);
        click("Bakery", Click.RIGHT);
        click(ShopDetailScreen.RESET, Click.LEFT);
        assertEquals(ToolScreen.CONFIRM, name(ShopDetailScreen.RESET));
        assertTrue(alex.commands.isEmpty(), "reset asks once");
        click(ShopDetailScreen.RESET, Click.LEFT);
        assertEquals(List.of("animus shop reset town:bakery"), alex.commands);
        assertTrue(lore(ShopDetailScreen.DELETE).contains("On baker, guard; remove it first"), lore(ShopDetailScreen.DELETE));
        click("Remove from NPC", Click.LEFT);
        assertTrue(last().contains("Unbound Bakery from 2 NPCs"), "the shop by its title: " + alex.messages);
        assertNull(definition("guard").get("modules"), "an empty modules object is removed");
        click(ShopDetailScreen.DELETE, Click.LEFT);
        assertEquals(ToolScreen.CONFIRM, name(ShopDetailScreen.DELETE));
        click(ShopDetailScreen.DELETE, Click.LEFT);
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("Deleted shops/town/bakery.json")), alex.messages.toString());
        assertNull(store.read("shops/town/bakery.json"));
        assertEquals("Shops", title(), "back on the list");
        assertTrue(shows("None yet. Make one with New shop"));
    }

    @Test void guisOpenOnChestGuisWithThreeTabsAndFollowTheClickRule() {
        selectBoth();
        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
        toolkit.open(alex.id, GuiBindScreen::new);
        assertEquals("Chest GUIs", title());
        assertEquals(Map.of(0, "town:board", 36, "Chest GUIs", 37, "Menus", 38, "Text animations", GuiBindScreen.RELOAD, "Reload",
                ToolScreen.BACK, "Back", ToolScreen.SEARCH, "Search", ToolScreen.HELP, "Help"), buttons());
        assertTrue(lore("town:board").contains("Notice board") && lore("town:board").contains("Click: use on 2 NPCs") && lore("town:board").contains("Right-click: details"), lore("town:board"));
        click("town:board", Click.LEFT);
        assertTrue(last().contains("Opens inventory town:board on 2 NPCs"), alex.messages.toString());
        assertEquals(new GuiBinding.Open(GuiBinding.Kind.INVENTORY, "town:board", GuiBinding.OpenOn.RIGHT), GuiBinding.parse(definition("guard")).orElseThrow().open().orElseThrow());
        click("town:board", Click.RIGHT);
        assertEquals("Chest GUI town:board", title());
        click("Show to me", Click.LEFT);
        assertEquals(List.of("animus gui open town:board"), alex.commands);

        toolkit.open(alex.id, GuiBindScreen::new);
        click("Menus", Click.LEFT);
        assertEquals("Menus", title());
        click("town:main", Click.LEFT);
        assertEquals(GuiBinding.Kind.MENU, GuiBinding.parse(definition("baker")).orElseThrow().open().orElseThrow().kind());
        click("Text animations", Click.LEFT);
        click("town:blink", Click.LEFT);
        assertEquals("Text animation town:blink", title(), "a text animation has only details");
    }

    @Test void anEmptyTabPointsAtTheWebEditor() throws IOException {
        store.commit(List.of(new DocumentStore.Change("gui/menus/town/main.json", JsonDocuments.hash(store.read("gui/menus/town/main.json")), null)));
        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
        toolkit.open(alex.id, GuiBindScreen::new);
        click("Menus", Click.LEFT);
        assertTrue(shows("None yet. Draw one in the web editor"), buttons().toString());
        click(ToolScreen.HELP, Click.LEFT);
        assertTrue(last().contains("draw in the web editor"), last());
    }

    @Test void guiDetailsCycleTheClickAndDeleteWaitsUntilUnused() throws IOException {
        selectBoth();
        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
        toolkit.open(alex.id, GuiBindScreen::new);
        click("town:board", Click.LEFT);
        click("town:board", Click.RIGHT);
        click("Opens on", Click.LEFT);
        assertEquals(GuiBinding.OpenOn.LEFT, GuiBinding.parse(definition("baker")).orElseThrow().open().orElseThrow().openOn());
        assertTrue(lore("Opens on").contains("Now: Left-click"), lore("Opens on"));
        assertTrue(lore(GuiDetailScreen.DELETE).contains("Used by baker, guard"), lore(GuiDetailScreen.DELETE));
        click("Remove from NPC", Click.LEFT);
        assertTrue(GuiBinding.parse(definition("guard")).isEmpty());
        click(GuiDetailScreen.DELETE, Click.LEFT);
        click(GuiDetailScreen.DELETE, Click.LEFT);
        assertNull(store.read("gui/inventories/town/board.json"));
    }

    @Test void aTextAnimationInUseCannotBeDeleted() {
        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
        toolkit.open(alex.id, GuiBindScreen::new);
        click("Text animations", Click.LEFT);
        assertTrue(lore("town:blink").contains("|animation.town:blink|"), lore("town:blink"));
        click("town:blink", Click.LEFT);
        assertTrue(lore(GuiDetailScreen.DELETE).contains("Used by town:sign"), lore(GuiDetailScreen.DELETE));
    }
}
