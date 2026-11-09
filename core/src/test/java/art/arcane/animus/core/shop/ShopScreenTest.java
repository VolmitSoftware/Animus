package art.arcane.animus.core.shop;

import art.arcane.animus.core.config.ShopSettings;
import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.MockInventory;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShopScreenTest {
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    private final MockScreenHost host = new MockScreenHost();
    private final MockEconomy economy = new MockEconomy();
    private final ShopLedger ledger = new ShopLedger(change -> {});
    private final ScreenManager<Player, Stack> manager = new ScreenManager<>(host, npc -> true, warning -> { throw new AssertionError(warning); });
    private final ShopScreen.Context<Stack> context = new ShopScreen.Context<>(ShopSettings.DEFAULT,
            new ShopTransactions<>(MockInventory.OPS, economy, ledger, () -> NOW), Optional.empty(), () -> NOW);
    private final Player alex = new Player("Alex");

    static final String BAKERY = """
            {"schemaVersion": 1, "revision": 1, "id": "town:baker", "title": "&6Bakery", "modes": ["buy", "sell"],
             "pages": [
               {"name": "Bread", "rows": 3,
                "buy": {"10": {"id": "bread", "type": "buy", "item": {"id": "minecraft:bread", "count": 4}, "price": 12, "display": {"name": "&eFresh bread"},
                               "broadcast": "{player} bought {item}", "commands": [{"command": "say {player} paid {price}"}]},
                        "26": {"id": "hidden", "type": "buy", "item": {"id": "minecraft:cake"}, "price": 1}},
                "sell": {"10": {"id": "wheat", "type": "sell", "item": {"id": "minecraft:wheat", "count": 8}, "price": 2}}},
               {"name": "Cakes", "rows": 3, "buy": {"0": {"id": "cake", "type": "buy", "item": {"id": "minecraft:cake"}, "price": 30}}},
               {"name": "Staff", "rows": 3, "permission": "town.staff", "buy": {"0": {"id": "secret", "type": "buy", "item": {"id": "minecraft:diamond"}, "price": 1}}}
             ]}
            """;

    private static ShopDocument parse(String json) {
        ShopDocument.Result result = ShopDocument.parse("town:baker", json);
        assertEquals(List.of(), result.problems());
        return result.shop().orElseThrow();
    }

    private boolean open(ShopDocument shop) { return manager.open(alex, Optional.empty(), (ScreenManager<Player, Stack>.Viewer viewer) -> ShopScreen.open(shop, viewer, context)); }
    private void click(int slot, Click click) { manager.click(alex, alex.window, slot, click); }

    @Test void theFirstPageShowsItsEntriesAndControls() {
        assertTrue(open(parse(BAKERY)));
        assertEquals("Bakery (1/2)", RichText.plain(alex.frame.title()), "the staff page is hidden without its permission");
        assertEquals("Fresh bread", RichText.plain(alex.frame.items().get(10).name().orElseThrow()));
        assertTrue(alex.frame.items().get(10).lore().stream().map(RichText::plain).anyMatch((String line) -> line.equals("Price: 12")));
        assertEquals("minecraft:barrier", alex.frame.items().get(22).item().id());
        assertEquals("minecraft:arrow", alex.frame.items().get(24).item().id());
        assertNull(alex.frame.items().get(20), "no previous page on the first page");
        assertEquals("minecraft:emerald", alex.frame.items().get(26).item().id(), "the mode switch covers the entry in slot 26");
    }

    @Test void buyingPaysRunsCommandsAndBroadcasts() {
        economy.give(alex.id, "100");
        open(parse(BAKERY));
        click(10, Click.LEFT);
        assertEquals(4, alex.inventory.total("minecraft:bread"));
        assertEquals(0, new BigDecimal("88").compareTo(economy.balance(alex.id)));
        assertEquals(List.of("Bought 4x minecraft:bread for 12."), alex.messages);
        assertEquals(List.of("say Alex paid 12"), host.console);
        assertEquals(List.of("Alex bought 4x minecraft:bread"), host.broadcasts);
    }

    @Test void aNameThatCannotBeWrittenIntoACommandRefusesThePurchase() {
        Player selector = new Player("@a");
        economy.give(selector.id, "100");
        manager.open(selector, Optional.empty(), (ScreenManager<Player, Stack>.Viewer viewer) -> ShopScreen.open(parse(BAKERY), viewer, context));
        manager.click(selector, selector.window, 10, Click.LEFT);
        assertTrue(host.console.isEmpty(), host.console.toString());
        assertEquals(0, selector.inventory.total("minecraft:bread"));
        assertEquals(0, new BigDecimal("100").compareTo(economy.balance(selector.id)), "nothing is paid for a refused purchase");
        assertTrue(selector.messages.getFirst().contains("name cannot be written into this shop's commands"), selector.messages.toString());
    }

    @Test void aRefusedPurchaseSaysWhyAndRunsNothing() {
        open(parse(BAKERY));
        click(10, Click.LEFT);
        assertEquals(List.of("You need 12 for that."), alex.messages);
        assertTrue(host.console.isEmpty());
        assertTrue(host.broadcasts.isEmpty());
    }

    @Test void theModeSwitchAndPageButtonsMoveThroughTheShop() {
        open(parse(BAKERY));
        click(26, Click.LEFT);
        assertEquals("minecraft:wheat", alex.frame.items().get(10).item().id());
        click(26, Click.LEFT);
        click(24, Click.LEFT);
        assertEquals("Bakery (2/2)", RichText.plain(alex.frame.title()));
        assertEquals("minecraft:cake", alex.frame.items().get(0).item().id());
        click(20, Click.LEFT);
        assertEquals("Bakery (1/2)", RichText.plain(alex.frame.title()));
    }

    @Test void pagePermissionsRevealPages() {
        alex.permissions.add("town.staff");
        open(parse(BAKERY));
        assertEquals("Bakery (1/3)", RichText.plain(alex.frame.title()));
    }

    @Test void shopPermissionsAreCheckedBeforeOpening() {
        assertFalse(open(parse(BAKERY.replace("\"title\": \"&6Bakery\"", "\"title\": \"&6Bakery\", \"permission\": \"town.baker\""))));
        assertEquals(List.of("You cannot use this shop."), alex.messages);
        assertNull(alex.window);
    }

    @Test void theCloseButtonClosesTheWindow() {
        open(parse(BAKERY));
        click(22, Click.LEFT);
        assertNull(alex.window);
    }

    @Test void confirmationAsksBeforeBuying() {
        economy.give(alex.id, "100");
        open(parse(BAKERY.replace("\"modes\"", "\"confirm\": true, \"modes\"")));
        click(10, Click.LEFT);
        assertEquals(0, alex.inventory.total("minecraft:bread"));
        assertEquals("Confirm purchase", RichText.plain(alex.frame.title()));
        click(15, Click.LEFT);
        assertEquals("Bakery (1/2)", RichText.plain(alex.frame.title()));
        click(10, Click.LEFT);
        click(11, Click.LEFT);
        assertEquals(4, alex.inventory.total("minecraft:bread"));
    }

    @Test void closeAfterPurchaseClosesOnSuccessOnly() {
        open(parse(BAKERY.replace("\"modes\"", "\"closeAfterPurchase\": true, \"modes\"")));
        click(10, Click.LEFT);
        assertNotNull(alex.window, "a refused purchase keeps the shop open");
        economy.give(alex.id, "100");
        click(10, Click.LEFT);
        assertNull(alex.window);
    }

    @Test void entriesWhoseItemsDoNotExistRefuseToTrade() {
        open(parse(BAKERY.replace("minecraft:bread", "minecraft:missing")));
        economy.give(alex.id, "100");
        click(10, Click.LEFT);
        assertTrue(alex.messages.getFirst().contains("does not exist on this server"), alex.messages.toString());
        assertEquals(0, new BigDecimal("100").compareTo(economy.balance(alex.id)));
    }

    @Test void aShopWithNothingVisibleDoesNotOpen() {
        String staffOnly = """
                {"schemaVersion": 1, "revision": 1, "id": "town:baker", "pages": [{"name": "Staff", "permission": "town.staff", "buy": {"0": {"id": "x", "type": "buy", "item": {"id": "minecraft:stone"}, "price": 1}}}]}
                """;
        assertFalse(open(parse(staffOnly)));
        assertEquals(List.of("This shop has nothing you can use."), alex.messages);
    }

    @Test void aShopWithNoEntriesOpensAnEmptyWindowThatSaysSo() {
        assertTrue(open(parse("{\"schemaVersion\": 1, \"revision\": 1, \"id\": \"town:baker\", \"title\": \"&6Stall\", \"pages\": [{\"name\": \"Main\", \"rows\": 3}]}")));
        assertEquals(List.of(), alex.messages, "no refusal");
        assertEquals("Stall", RichText.plain(alex.frame.title()));
        assertEquals("Nothing here yet", RichText.plain(alex.frame.items().get(13).name().orElseThrow()));
        assertEquals("No offers have been added to this shop", RichText.plain(alex.frame.items().get(13).lore().getFirst()));
        click(22, Click.LEFT);
        assertNull(alex.window);
    }

    @Test void limitLinesShowWhatThePlayerHasUsed() {
        economy.give(alex.id, "100");
        String limited = BAKERY.replace("\"price\": 12,", "\"price\": 12, \"limit\": {\"perPlayer\": 8, \"resetSeconds\": 86400},");
        open(parse(limited));
        click(10, Click.LEFT);
        List<String> lore = alex.frame.items().get(10).lore().stream().map(RichText::plain).toList();
        assertTrue(lore.contains("Limit: 4/8"), lore.toString());
        assertTrue(lore.contains("Resets every 1d"), lore.toString());
    }
}
