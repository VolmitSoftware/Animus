package art.arcane.animus.core.shop;

import art.arcane.animus.core.config.ShopSettings;
import art.arcane.animus.core.gui.GuiDocument;
import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.gui.Screen;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.gui.Screens;
import art.arcane.animus.core.inventory.MockInventory;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.RichText;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShopStyleTest {
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    private final MockScreenHost host = new MockScreenHost();
    private final MockEconomy economy = new MockEconomy();
    private final ShopLedger ledger = new ShopLedger(change -> {});
    private final ScreenManager<Player, Stack> manager = new ScreenManager<>(host, npc -> true, warning -> { throw new AssertionError(warning); });
    private final ShopScreen.Context<Stack> context = new ShopScreen.Context<>(ShopSettings.DEFAULT,
            new ShopTransactions<>(MockInventory.OPS, economy, ledger, () -> NOW), Optional.empty(), () -> NOW);
    private final Player alex = new Player("Alex");

    static final String MARKET = """
            {"schemaVersion": 1, "revision": 1, "id": "town:market", "title": "&6Market", "modes": ["trade", "buy", "sell"],
             "pages": [
               {"name": "One", "rows": 3,
                "trade": {"4": {"id": "bread-for-wheat", "type": "trade", "item": {"id": "minecraft:bread"},
                                "needs": [{"item": {"id": "minecraft:wheat"}, "amount": 3}, {"item": {"id": "minecraft:egg"}, "amount": 1}, {"item": {"id": "minecraft:sugar"}, "amount": 2}],
                                "gives": [{"item": {"id": "minecraft:bread"}, "amount": 2}, {"item": {"id": "minecraft:cookie"}, "amount": 1}],
                                "limit": {"perPlayer": 2}},
                          "0": {"id": "apple-swap", "type": "trade", "item": {"id": "minecraft:apple"},
                                "needs": [{"item": {"id": "minecraft:stick"}, "amount": 1}], "gives": [{"item": {"id": "minecraft:apple"}, "amount": 1}]}},
                "buy": {"0": {"id": "cake", "type": "buy", "item": {"id": "minecraft:cake"}, "price": 30},
                        "1": {"id": "sign", "type": "static", "item": {"id": "minecraft:oak_sign"}},
                        "2": {"id": "gift", "type": "buy", "item": {"id": "minecraft:flower_pot"}, "price": 0}},
                "sell": {"0": {"id": "wheat", "type": "sell", "item": {"id": "minecraft:wheat", "count": 8}, "price": 2}}}
             ]}
            """;

    private static ShopDocument parse(String json) {
        ShopDocument.Result result = ShopDocument.parse("town:market", json);
        assertEquals(List.of(), result.problems());
        return result.shop().orElseThrow();
    }

    private boolean open(ShopDocument shop, ShopScreen.View<Player, Stack> view) {
        return manager.open(alex, Optional.empty(), (ScreenManager<Player, Stack>.Viewer viewer) -> ShopScreen.open(shop, viewer, context, view));
    }
    private static ShopScreen.View<Player, Stack> nativeView() { return new ShopScreen.View<>(ShopDocument.Style.NATIVE, Optional.empty(), Optional.empty(), Optional.empty()); }
    private void click(int slot, Click click) { manager.click(alex, alex.window, slot, click); }
    private List<String> lore(ScreenFrame.Item item) { return item.lore().stream().map(RichText::plain).toList(); }

    @Test void styleAndLayoutAreReadAndChecked() {
        ShopDocument shop = parse(MARKET.replace("\"title\"", "\"style\": \"native\", \"layout\": \"town:frame\", \"title\""));
        assertEquals(Optional.of(ShopDocument.Style.NATIVE), shop.style());
        assertEquals(Optional.of("town:frame"), shop.layout());
        assertEquals(Optional.empty(), parse(MARKET).style());
        assertTrue(ShopDocument.parse("town:market", MARKET.replace("\"title\"", "\"style\": \"villager\", \"title\"")).problems().contains("$.style: expected native or chest"));
        assertTrue(ShopDocument.parse("town:market", MARKET.replace("\"title\"", "\"layout\": \"Frame\", \"title\"")).problems().contains("$.layout: expected a chest GUI id such as town:shop-frame"));
    }

    @Test void theServerDefaultStyleIsChestAndOnlyTwoStylesExist() {
        assertEquals(ShopDocument.Style.CHEST, ShopSettings.DEFAULT.defaultStyle());
        ShopSettings custom = new ShopSettings(true, "auto", "native", "#,##0.##", 2000, Map.of());
        assertEquals(ShopDocument.Style.NATIVE, custom.defaultStyle());
        assertThrows(IllegalArgumentException.class, () -> new ShopSettings(true, "auto", "villager", "#,##0.##", 2000, Map.of()));
    }

    @Test void bareVillagerTypeAndProfessionAreMinecraftIds() throws Exception {
        JsonObject definition = JsonDocuments.object("""
                {"schemaVersion": 2, "revision": 1, "uuid": "1fde8840-5f7a-5a37-bc24-357183d2e276", "id": "farmer", "group": "town", "name": "Farmer",
                 "type": "villager", "spawned": true, "position": {"world": "world", "x": 0, "y": 64, "z": 0, "yaw": 0, "pitch": 0},
                 "modules": {"animus:shop": {"shop": "town:market", "villager": {"profession": "farmer", "level": 2}}}}
                """);
        ShopBinding binding = ShopBinding.of(NpcDefinition.parse(definition.toString())).orElseThrow();
        assertEquals(Optional.of(new ShopBinding.Villager("minecraft:farmer", 2)), binding.villager());
    }

    @Test void bindingsOverrideTheStyleAndCarryVillagerLooks() throws Exception {
        JsonObject definition = JsonDocuments.object("""
                {"schemaVersion": 2, "revision": 1, "uuid": "1fde8840-5f7a-5a37-bc24-357183d2e276", "id": "farmer", "group": "town", "name": "Farmer",
                 "type": "minecraft:villager", "spawned": true, "position": {"world": "world", "x": 0, "y": 64, "z": 0, "yaw": 0, "pitch": 0},
                 "modules": {"animus:shop": {"shop": "town:market", "style": "native", "villager": {"profession": "minecraft:farmer", "level": 2}}}}
                """);
        ShopBinding binding = ShopBinding.of(NpcDefinition.parse(definition.toString())).orElseThrow();
        assertEquals(Optional.of(ShopDocument.Style.NATIVE), binding.style());
        assertEquals(Optional.of(new ShopBinding.Villager("minecraft:farmer", 2)), binding.villager());
        definition.getAsJsonObject("modules").getAsJsonObject("animus:shop").getAsJsonObject("villager").addProperty("level", 6);
        assertEquals(List.of("$.modules[\"animus:shop\"].villager.level: expected integer 1..5"), NpcDefinition.validate(definition));
        definition.getAsJsonObject("modules").getAsJsonObject("animus:shop").addProperty("style", "fancy");
        definition.getAsJsonObject("modules").getAsJsonObject("animus:shop").remove("villager");
        assertEquals(List.of("$.modules[\"animus:shop\"].style: expected native or chest"), NpcDefinition.validate(definition));
        definition.getAsJsonObject("modules").getAsJsonObject("animus:shop").remove("style");
        definition.addProperty("type", "minecraft:player");
        definition.getAsJsonObject("modules").getAsJsonObject("animus:shop").add("villager", JsonDocuments.object("{\"level\": 3}"));
        assertEquals(Optional.empty(), ShopBinding.of(NpcDefinition.parse(definition.toString())).orElseThrow().villager(), "villager looks apply to villager bodies only");
    }

    @Test void aVillagerBodyWithoutSettingsIsANovice() throws Exception {
        JsonObject definition = JsonDocuments.object("""
                {"schemaVersion": 2, "revision": 1, "uuid": "1fde8840-5f7a-5a37-bc24-357183d2e276", "id": "farmer", "group": "town", "name": "Farmer",
                 "type": "minecraft:villager", "spawned": true, "position": {"world": "world", "x": 0, "y": 64, "z": 0, "yaw": 0, "pitch": 0},
                 "modules": {"animus:shop": {"shop": "town:market"}}}
                """);
        assertEquals(Optional.of(new ShopBinding.Villager("minecraft:none", 1)), ShopBinding.of(NpcDefinition.parse(definition.toString())).orElseThrow().villager());
    }

    @Test void theNativeStyleListsEveryOfferAsAMerchantTrade() {
        assertTrue(open(parse(MARKET), nativeView()));
        ScreenFrame.Merchant merchant = alex.frame.merchant().orElseThrow();
        assertEquals("Market", RichText.plain(alex.frame.title()));
        assertEquals(5, merchant.offers().size(), "static entries are not offers");
        assertEquals(5, alex.frame.size());
        ScreenFrame.Offer swap = merchant.offers().get(0);
        assertEquals("minecraft:stick", swap.costA().item().id());
        assertTrue(swap.costB().isEmpty());
        assertEquals("minecraft:apple", swap.result().item().id());
        ScreenFrame.Offer bread = merchant.offers().get(1);
        assertEquals(3, bread.costA().item().count());
        assertEquals("minecraft:egg", bread.costB().orElseThrow().item().id());
        assertEquals("minecraft:bread", bread.result().item().id());
        assertEquals(2, bread.result().item().count());
        assertTrue(lore(bread.result()).contains("- 2x Sugar"), "the third need is listed in the tooltip");
        assertTrue(lore(bread.result()).contains("- 1x Cookie"), "the second give is listed in the tooltip");
        assertEquals(0, bread.uses());
        assertEquals(2, bread.maxUses());
        ScreenFrame.Offer cake = merchant.offers().get(2);
        assertEquals("minecraft:gold_nugget", cake.costA().item().id());
        assertEquals("$30", RichText.plain(cake.costA().name().orElseThrow()), "the economy writes the price");
        assertEquals("minecraft:cake", cake.result().item().id());
        assertEquals("Free", RichText.plain(merchant.offers().get(3).costA().name().orElseThrow()));
        ScreenFrame.Offer wheat = merchant.offers().get(4);
        assertEquals("minecraft:wheat", wheat.costA().item().id());
        assertEquals(8, wheat.costA().item().count());
        assertEquals("minecraft:gold_nugget", wheat.result().item().id());
        assertEquals("Pays $2", RichText.plain(wheat.result().name().orElseThrow()));
        assertEquals(Integer.MAX_VALUE, wheat.maxUses());
    }

    @Test void choosingANativeOfferTradesAndCountsItsUses() {
        alex.inventory.with(0, Stack.of("minecraft:wheat", 6)).with(1, Stack.of("minecraft:egg", 2)).with(2, Stack.of("minecraft:sugar", 4));
        open(parse(MARKET), nativeView());
        click(1, Click.LEFT);
        assertEquals(2, alex.inventory.total("minecraft:bread"));
        assertEquals(1, alex.inventory.total("minecraft:cookie"));
        assertEquals(3, alex.inventory.total("minecraft:wheat"));
        assertEquals(List.of("Traded for 2x minecraft:bread, 1x minecraft:cookie."), alex.messages);
        assertEquals(1, alex.frame.merchant().orElseThrow().offers().get(1).uses(), "the window shows the use");
        click(1, Click.LEFT);
        click(1, Click.LEFT);
        assertEquals(4, alex.inventory.total("minecraft:bread"));
        assertEquals("You have reached the limit for 2x minecraft:bread, 1x minecraft:cookie.", alex.messages.getLast());
        assertEquals(2, alex.frame.merchant().orElseThrow().offers().get(1).uses());
    }

    @Test void nativeMoneyOffersUseTheEconomy() {
        economy.give(alex.id, "100");
        alex.inventory.with(0, Stack.of("minecraft:wheat", 8));
        open(parse(MARKET), nativeView());
        click(2, Click.LEFT);
        assertEquals(1, alex.inventory.total("minecraft:cake"));
        click(4, Click.SHIFT_LEFT);
        assertEquals(0, alex.inventory.total("minecraft:wheat"));
        assertEquals(0, new BigDecimal("72").compareTo(economy.balance(alex.id)));
    }

    @Test void nativeConfirmationUsesAChestWindowThenReturns() {
        economy.give(alex.id, "100");
        open(parse(MARKET.replace("\"modes\"", "\"confirm\": true, \"modes\"")), nativeView());
        click(2, Click.LEFT);
        assertTrue(alex.frame.merchant().isEmpty());
        assertEquals("Confirm purchase", RichText.plain(alex.frame.title()));
        click(11, Click.LEFT);
        assertEquals(1, alex.inventory.total("minecraft:cake"));
        assertTrue(alex.frame.merchant().isPresent(), "the trading screen comes back");
    }

    @Test void villagerBodiesShowTheirLevelAndProfession() {
        ShopScreen.View<Player, Stack> farmer = new ShopScreen.View<>(ShopDocument.Style.NATIVE, Optional.of(new ShopBinding.Villager("minecraft:farmer", 2)), Optional.empty(), Optional.empty());
        open(parse(MARKET), farmer);
        assertEquals("Market - Apprentice", RichText.plain(alex.frame.title()));
        manager.close(alex);
        open(parse(MARKET.replace("\"title\": \"&6Market\", ", "")), farmer);
        assertEquals("Farmer - Apprentice", RichText.plain(alex.frame.title()), "an untitled shop takes the profession's name, as vanilla does");
    }

    @Test void aShopWithOnlyStaticEntriesHasNothingToTradeNatively() {
        String decor = """
                {"schemaVersion": 1, "revision": 1, "id": "town:market", "pages": [{"name": "Decor", "buy": {"0": {"id": "sign", "type": "static", "item": {"id": "minecraft:oak_sign"}}}}]}
                """;
        assertFalse(open(parse(decor), nativeView()));
        assertEquals(List.of("This shop has nothing you can use."), alex.messages);
    }

    private static final String FRAME = """
            {"schemaVersion": 1, "revision": 1, "title": "&8{{ args.title }} {{ page }}/{{ pages }}", "resolution": "9x2", "mask": ["#..#..#..", "<   X   >"],
             "keys": {"#": {"type": "decoration", "icon": {"type": "item", "item": "minecraft:black_stained_glass_pane", "name": " "}},
                      "<": {"type": "button", "icon": {"type": "item", "item": "minecraft:arrow"}, "actions": [{"type": "navigate", "mode": "page", "target": "prev"}]},
                      ">": {"type": "button", "icon": {"type": "item", "item": "minecraft:arrow"}, "actions": [{"type": "navigate", "mode": "page", "target": "next"}]},
                      "X": {"type": "button", "icon": {"type": "item", "item": "minecraft:barrier"}, "actions": [{"type": "close"}]}},
             "list": {"area": ".", "var": "offer", "source": "[]", "template": {"type": "decoration", "icon": {"type": "item", "item": "minecraft:stone"}}}}
            """;

    @Test void aLayoutGuiShowsTheOffersInItsListAreaInPages() {
        GuiDocument frame = GuiDocument.parse("town:frame", FRAME).document().orElseThrow();
        Screens<Player, Stack> screens = new Screens<>() {
            @Override public Optional<Screen> gui(ScreenManager<Player, Stack>.Viewer viewer, String id, Map<String, String> args) { return Optional.empty(); }
            @Override public Optional<Screen> shop(ScreenManager<Player, Stack>.Viewer viewer, String id) { return Optional.empty(); }
            @Override public Object npc(ScreenManager<Player, Stack>.Viewer viewer, String property) { return null; }
        };
        economy.give(alex.id, "100");
        String many = MARKET.replace("\"2\": {\"id\": \"gift\"", "\"3\": {\"id\": \"cake2\", \"type\": \"buy\", \"item\": {\"id\": \"minecraft:cookie\"}, \"price\": 1}, \"4\": {\"id\": \"cake3\", \"type\": \"buy\", \"item\": {\"id\": \"minecraft:melon\"}, \"price\": 1}, \"2\": {\"id\": \"gift\"");
        open(parse(many), new ShopScreen.View<>(ShopDocument.Style.CHEST, Optional.empty(), Optional.of(frame), Optional.of(screens)));
        assertEquals("Market 1/2", RichText.plain(alex.frame.title()));
        assertEquals("minecraft:black_stained_glass_pane", alex.frame.items().get(0).item().id());
        assertEquals("minecraft:apple", alex.frame.items().get(1).item().id(), "offers fill the list area in shop order");
        assertEquals("minecraft:bread", alex.frame.items().get(2).item().id());
        assertEquals("minecraft:cake", alex.frame.items().get(4).item().id());
        click(4, Click.LEFT);
        assertEquals(1, alex.inventory.total("minecraft:cake"));
        click(17, Click.LEFT);
        assertEquals("Market 2/2", RichText.plain(alex.frame.title()));
        assertEquals("minecraft:wheat", alex.frame.items().get(1).item().id());
        click(13, Click.LEFT);
        assertNull(alex.window);
    }

    @Test void notesNameOffersTheTradingScreenCannotShowExactly() {
        List<String> notes = ShopNotes.of(parse(MARKET), ShopDocument.Style.NATIVE, (String id) -> Optional.empty());
        assertTrue(notes.contains("$.pages[0].trade.4: the trading screen shows two needs and one result; the rest are listed in the result's tooltip"), notes.toString());
        assertTrue(notes.contains("$.pages[0].buy.0: the trading screen shows the price as a token item; the money is taken from the player's balance"), notes.toString());
        assertTrue(notes.contains("$.pages[0].sell.0: the trading screen shows the payment as a token item; the money is paid to the player's balance"), notes.toString());
        assertTrue(notes.contains("$.pages[0].buy.1: static entries do not show in the trading screen"), notes.toString());
        assertEquals(List.of(), ShopNotes.of(parse(MARKET), ShopDocument.Style.CHEST, (String id) -> Optional.empty()));
        String layout = MARKET.replace("\"title\"", "\"layout\": \"town:frame\", \"title\"");
        assertEquals(List.of("$.layout: chest GUI town:frame is not loaded; the shop shows its own pages"), ShopNotes.of(parse(layout), ShopDocument.Style.CHEST, (String id) -> Optional.empty()));
        assertTrue(ShopNotes.of(parse(layout), ShopDocument.Style.NATIVE, (String id) -> Optional.empty()).contains("$.layout: applies to the chest style only"));
    }
}
