package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.inventory.MockInventory;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.owner.OwnerPermissions;
import art.arcane.animus.core.owner.OwnerWrites;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.shop.ShopSpot;
import art.arcane.animus.core.shop.ShopStock;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.virtual.Vec3;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A player makes a shop on their own chest and runs it from its menu, as in Shopkeepers, and reaches no one else's. */
class PlayerShopScreensTest extends ToolkitHarness {
    static final ShopSpot CHEST = new ShopSpot(Set.of("world", "minecraft:overworld"), 5, 64, 5, new Vec3(5.5, 64, 6.5), 180);
    static final String SHOP = "shops/players/alex-s-shop.json", ID = "players:alex-s-shop";
    static final UUID SAM = UUID.fromString("0000000b-0000-4000-8000-0000000000b2");

    @BeforeEach void setUp() throws IOException {
        start(List.of(), false);
        store.check(new OwnerWrites(store, npcs, () -> OwnerPermissions.NONE, Set::of));
        alex.permissions.clear();
        alex.permissions.add(PermissionNodes.SHOPS_CREATE);
    }

    private JsonObject stored(String path) throws IOException { return JsonDocuments.object(store.read(path)); }

    private void make(String kind, String body) {
        toolkit.newShop(alex.id, CHEST);
        assertEquals("New shop", title());
        click(kind, Click.LEFT);
        click(body, Click.LEFT);
        click("Make shop", Click.LEFT);
    }

    private void editor() {
        toolkit.manageShop(alex.id, ID);
        click("Edit offers", Click.LEFT);
        assertEquals("Offers", title());
    }

    /** Alex was told {@code text}, after the chat prefix. */
    private void said(String text) { assertTrue(alex.messages.stream().anyMatch((String line) -> line.endsWith(text)), alex.messages.toString()); }

    private void pick(String id, int count) { manager.picked(alex, alex.window, new NpcItem(id, count, Optional.empty(), Optional.empty())); }

    @Test void aPlayerMakesASellingShopWithItsNpcInOneChange() throws IOException {
        make("Selling", "Villager");
        JsonObject shop = stored(SHOP);
        assertEquals("selling", shop.get("kind").getAsString());
        assertEquals(alex.id.toString(), shop.getAsJsonObject("owner").get("uuid").getAsString());
        assertEquals("Alex", shop.getAsJsonObject("owner").get("name").getAsString());
        assertEquals("{\"world\":\"world\",\"x\":5,\"y\":64,\"z\":5}", shop.getAsJsonArray("containers").get(0).toString());
        assertEquals("native", shop.get("style").getAsString());
        NpcDefinition npc = npcs.find("alex-s-shop").orElseThrow();
        assertEquals(Optional.of(alex.id), npc.owner());
        assertEquals("minecraft:villager", npc.json().get("type").getAsString());
        assertEquals(ID, npc.json().getAsJsonObject("modules").getAsJsonObject("animus:shop").get("shop").getAsString());
        assertEquals(5.5, npc.json().getAsJsonObject("position").get("x").getAsDouble());
        assertEquals(1, changes().size(), "the shop and its NPC are one change");
        assertTrue(title().contains("Alex's shop"), "the shop's menu follows: " + title());
    }

    @Test void aPlayerWithoutTheNodeMakesNothing() {
        alex.permissions.clear();
        toolkit.newShop(alex.id, CHEST);
        said("No permission: animus.shops.create; ask an operator to grant it");
        assertNull(alex.frame);
    }

    @Test void aChestThePlayerCannotOpenIsRefused() {
        host.chestRefusal = Optional.of("protection does not let you open it");
        toolkit.newShop(alex.id, CHEST);
        said("You cannot use that chest: protection does not let you open it");
    }

    @Test void theShopLimitAndTheNpcLimitBothHold() {
        alex.permissions.add("animus.shops.limit.1");
        make("Selling", "Villager");
        toolkit.newShop(alex.id, new ShopSpot(Set.of("world"), 9, 64, 9, new Vec3(9.5, 64, 10.5), 180));
        said("You own 1 shop, your limit; remove one to make another");
        alex.permissions.remove("animus.shops.limit.1");
        alex.permissions.add("animus.npc.limit.1");
        toolkit.newShop(alex.id, new ShopSpot(Set.of("world"), 9, 64, 9, new Vec3(9.5, 64, 10.5), 180));
        said("You own 1 NPC, your limit; remove one to make another");
    }

    @Test void aPluginsTemporaryNpcForThePlayerCountsTowardTheNpcLimit() throws IOException {
        alex.permissions.add("animus.npc.limit.1");
        npcs.addTemporary("plugin:Pets", com.google.gson.JsonParser.parseString(npc("pet", UUID.randomUUID(), ",\"owner\":\"" + alex.id + "\"")).getAsJsonObject());
        toolkit.newShop(alex.id, CHEST);
        said("You own 1 NPC, your limit; remove one to make another");
    }

    @Test void anotherPlayersChestIsRefusedAndTheirShopIsNotOurs() throws IOException {
        store.commit(List.of(file("shops/players/sams-shop.json", "{\"schemaVersion\":1,\"revision\":1,\"id\":\"players:sams-shop\",\"kind\":\"selling\","
                + "\"owner\":{\"uuid\":\"" + SAM + "\",\"name\":\"Sam\"},\"containers\":[{\"world\":\"world\",\"x\":5,\"y\":64,\"z\":5}],\"pages\":[{\"name\":\"Offers\"}]}")));
        indexShops();
        toolkit.newShop(alex.id, CHEST);
        said("That chest belongs to Sam's shop");
        toolkit.manageShop(alex.id, "players:sams-shop");
        said("That shop is not yours");
    }

    @Test void theOwnerSetsADiamondForFiveEmeralds() throws IOException {
        make("Selling", "Villager");
        editor();
        pick("minecraft:diamond", 1);
        assertEquals("1x Diamond", name(0));
        assertEquals("1x Emerald", name(9), "a new offer asks for the server's currency");
        for (int click = 0; click < 4; click++) click(9, Click.LEFT);
        assertEquals("5x Emerald", name(9));
        click("Save", Click.LEFT);
        JsonObject entry = stored(SHOP).getAsJsonArray("pages").get(0).getAsJsonObject().getAsJsonObject("trade").getAsJsonObject("0");
        assertEquals("trade", entry.get("type").getAsString());
        assertEquals("[{\"item\":{\"id\":\"minecraft:emerald\",\"count\":1},\"amount\":5}]", entry.get("needs").toString());
        assertEquals("[{\"item\":{\"id\":\"minecraft:diamond\",\"count\":1},\"amount\":1}]", entry.get("gives").toString());
        assertEquals("offer-1", entry.get("id").getAsString());
    }

    @Test void aSecondPriceAndAmountsChangeByClickAndTheTopItemTakesItsOfferAway() throws IOException {
        make("Trading", "Villager");
        editor();
        pick("minecraft:iron_ingot", 3);
        assertEquals("Price", name(9), "a trading shop starts without a price");
        click(9, Click.LEFT);
        pick("minecraft:gold_ingot", 2);
        click(18, Click.LEFT);
        pick("minecraft:coal", 1);
        click(18, Click.SHIFT_LEFT);
        assertEquals("11x Coal", name(18), "sneaking adds ten");
        click("Save", Click.LEFT);
        JsonObject entry = stored(SHOP).getAsJsonArray("pages").get(0).getAsJsonObject().getAsJsonObject("trade").getAsJsonObject("0");
        assertEquals(2, entry.getAsJsonArray("needs").size());
        click(0, Click.RIGHT); click(0, Click.RIGHT); click(0, Click.RIGHT);
        click("Save", Click.LEFT);
        assertFalse(stored(SHOP).getAsJsonArray("pages").get(0).getAsJsonObject().has("trade"), "the offer went with its item");
    }

    @Test void anOfferWithoutAPriceIsNotSaved() throws IOException {
        make("Trading", "Villager");
        editor();
        pick("minecraft:diamond", 1);
        click("Save", Click.LEFT);
        said("Offer 1 needs a price");
        assertFalse(stored(SHOP).getAsJsonArray("pages").get(0).getAsJsonObject().has("trade"));
    }

    @Test void aBuyingShopSaysWhatItBuysAndPays() throws IOException {
        make("Buying", "Villager");
        editor();
        pick("minecraft:wheat", 16);
        click("Save", Click.LEFT);
        JsonObject entry = stored(SHOP).getAsJsonArray("pages").get(0).getAsJsonObject().getAsJsonObject("trade").getAsJsonObject("0");
        assertEquals("[{\"item\":{\"id\":\"minecraft:wheat\",\"count\":1},\"amount\":16}]", entry.get("needs").toString());
        assertEquals("[{\"item\":{\"id\":\"minecraft:emerald\",\"count\":1},\"amount\":1}]", entry.get("gives").toString());
    }

    @Test void aBookShopPricesTheBooksInItsChest() throws IOException {
        MockInventory chest = new MockInventory(27).with(0, new Stack("minecraft:written_book", 1, "Tales", "")).with(1, new Stack("minecraft:written_book", 1, "Worn", "generation=2"));
        host.stocks = (ShopDocument shop) -> ShopStock.of(chest);
        make("Book", "Villager");
        editor();
        assertEquals("Tales", name(0), "only a book that can still be copied");
        assertEquals("No more books", name(1));
        click(9, Click.LEFT);
        pick("minecraft:emerald", 3);
        click("Save", Click.LEFT);
        JsonObject entry = stored(SHOP).getAsJsonArray("pages").get(0).getAsJsonObject().getAsJsonObject("trade").getAsJsonObject("0");
        assertEquals("Tales", entry.get("book").getAsString());
        assertFalse(entry.has("gives"));
        assertEquals("[{\"item\":{\"id\":\"minecraft:emerald\",\"count\":1},\"amount\":3}]", entry.get("needs").toString());
    }

    @Test void theOwnerClosesOpensRenamesAndOpensTheStock() throws IOException {
        make("Selling", "Villager");
        click("Open", Click.LEFT);
        assertEquals("Closed by its owner", stored(SHOP).get("closed").getAsString());
        click("Closed", Click.LEFT);
        assertFalse(stored(SHOP).has("closed"));
        click("Stock", Click.LEFT);
        assertEquals(List.of("world 5 64 5"), host.chestsOpened);
        click("Rename", Click.LEFT);
        ScreenHost.Prompt prompt = alex.prompt;
        prompt.answer().accept(Optional.of("Gem stall"));
        assertEquals("Gem stall", stored(SHOP).get("title").getAsString());
        assertEquals("Gem stall", npcs.find("alex-s-shop").orElseThrow().name());
    }

    @Test void theKindChangesWhileTheOffersFitIt() throws IOException {
        make("Selling", "Villager");
        click("Kind: Selling", Click.LEFT);
        assertEquals("buying", stored(SHOP).get("kind").getAsString());
        click("Kind: Buying", Click.RIGHT);
        editor();
        pick("minecraft:diamond", 1);
        click("Save", Click.LEFT);
        click("Back", Click.LEFT);
        click("Kind: Selling", Click.RIGHT);
        said("$.pages[0].trade.0.book: a book shop's trades name the book they copy");
        assertEquals("selling", stored(SHOP).get("kind").getAsString());
    }

    @Test void theBodyChangesFromTheShopsMenu() {
        make("Selling", "Villager");
        click("Wandering trader", Click.LEFT);
        assertEquals("minecraft:wandering_trader", npcs.find("alex-s-shop").orElseThrow().json().get("type").getAsString());
    }

    @Test void chestsAreAddedByRightClickAndRemovedButNeverTheLast() throws IOException {
        make("Selling", "Villager");
        click("Chests", Click.LEFT);
        click(0, Click.RIGHT);
        said("A shop needs at least one chest; add another first");
        click("Add chest", Click.LEFT);
        assertTrue(toolkit.chestClicked(alex.id, new ShopSpot(Set.of("world"), 6, 64, 5, new Vec3(6.5, 64, 6.5), 0)));
        assertEquals(2, stored(SHOP).getAsJsonArray("containers").size());
        assertFalse(toolkit.chestClicked(alex.id, CHEST), "only one right-click answers Add chest");
        click(0, Click.RIGHT);
        assertEquals("[{\"world\":\"world\",\"x\":6,\"y\":64,\"z\":5}]", stored(SHOP).getAsJsonArray("containers").toString());
    }

    @Test void removingTheShopTakesItsNpcAndKeepsTheChest() throws IOException {
        make("Selling", "Villager");
        click(PlayerShopScreen.REMOVE, Click.LEFT);
        click(PlayerShopScreen.REMOVE, Click.LEFT);
        assertNull(store.read(SHOP));
        assertTrue(npcs.find("alex-s-shop").isEmpty());
    }

    @Test void theShopItemIsForPlayersWhoMakeShops() {
        toolkit.shopItem(alex.id);
        assertEquals(1, host.shopItems);
        alex.permissions.clear();
        toolkit.shopItem(alex.id);
        assertEquals(1, host.shopItems);
    }
}
