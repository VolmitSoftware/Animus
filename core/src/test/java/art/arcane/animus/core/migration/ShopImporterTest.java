package art.arcane.animus.core.migration;

import art.arcane.animus.core.shop.ShopCatalog;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ShopImporterTest {
    private static final String PLAYER = "33c1a218-e15e-4f9b-b905-b1f2cf26cd9c";
    @TempDir Path folder;

    static final String BAKERY = """
            Bakery:
              title: '&6The Bakery'
              command: bakery
              permission: none
              default-shop: buy
              buy-shop-enabled: true
              sell-shop-enabled: true
              trade-shop-enabled: true
              logging-enabled: false
              close-after-purchase: false
              confirmation-window-enabled: true
              pages:
                page-0:
                  size: 27
                  page-name: Bread
                  page-permission: ''
                  buy-items:
                    item-10:
                      id: 3
                      type: tradable
                      item:
                        ==: org.bukkit.inventory.ItemStack
                        v: 4189
                        type: BREAD
                        amount: 4
                        meta:
                          ==: ItemMeta
                          meta-type: UNSPECIFIC
                          display-name: '{"extra":[{"text":"&eFresh"}],"text":""}'
                          enchants:
                            DURABILITY: 1
                      trade-price: 12.0
                      trade-limit: 16
                      limit-reset-seconds: 86400
                      stack-sale-enabled: true
                      custom-input-enabled: true
                      broadcast-message: '%player% bought bread'
                      description:
                      - '&7Baked today'
                      permission: ''
                      Discount:
                        discount-enabled: true
                        discount-price: 8.0
                        discount-start: 01/11/2026 00:00:00
                        discount-end: 30/11/2026 00:00:00
                    item-11:
                      id: 7
                      type: tradable
                      item:
                        type: BONE
                        amount: 1
                      trade-price: 50
                      run-mode: BUY_AND_RUN
                      commands:
                        '0':
                          executor: CONSOLE
                          command: givepet %player% wolf
                        '1':
                          executor: OP
                          command: heal %player%
                    item-0:
                      id: 9
                      type: static
                      item:
                        type: OAK_SIGN
                  sell-items:
                    item-10:
                      id: 4
                      type: tradable
                      item:
                        type: WHEAT
                        amount: 8
                      trade-price: 2
                      sell-all-when-not-enough-items: true
                  trade-items:
                    item-13:
                      id: 5
                      type: trade
                      item:
                        type: DIAMOND
                      trade-price: 0.0
                      needed-items:
                        trade-item-0:
                          item:
                            type: EMERALD
                            amount: 10
                      obtainable-items:
                        obtainable-item-0:
                          item:
                            type: DIAMOND
                            amount: 1
                page-1:
                  size: 20
                  page-name: ''
            """;

    static final String SAVES = """
            npc:
              '0':
                name: Baker
                uuid: 1fde8840-5f7a-5a37-bc24-357183d2e276
                traits:
                  trader:
                    guiName: Bakery
              '1':
                name: Ghost
                traits:
                  trader:
                    guiName: Removed Shop
              '2':
                name: Old Bob
                traits:
                  trader:
                    type: server
                    sell:
                    - stone a:16 p:2 s:0
                    - diamond_sword p:100 .sp n:Blade
                    buy:
                    - wool:14 a:8 p:1
                  wallet:
                    type: owner
                    money: 12.5
            """;

    private Path dtl() throws Exception {
        Path root = folder.resolve("dtlTradersPlus");
        Files.createDirectories(root.resolve("shops/Bakery"));
        Files.writeString(root.resolve("shops/Bakery/Bakery.yml"), BAKERY);
        Files.write(root.resolve("data.dat"), data(5, 3, 6, 4, 2));
        return root;
    }

    private static byte[] data(int nextId, int... pairs) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("6.4.1");
            out.writeInt(nextId);
            out.writeInt(1);
            out.writeUTF(PLAYER);
            out.writeInt(pairs.length / 2);
            for (int index = 0; index < pairs.length; index += 2) { out.writeInt(pairs[index]); out.writeInt(pairs[index + 1]); }
            out.writeInt(550);
        }
        return bytes.toByteArray();
    }

    private ShopImporter.Preview preview(Optional<Path> saves) throws Exception {
        try (DocumentStore store = new DocumentStore(folder.resolve("animus"))) { return new ShopImporter().preview(dtl(), saves, store); }
    }

    private static JsonObject staged(ShopImporter.Preview preview, String path) throws Exception {
        return JsonDocuments.object(Files.readString(preview.directory().resolve(path)));
    }

    @Test void versionSixShopsConvertToValidShopDocuments() throws Exception {
        ShopImporter.Preview preview = preview(Optional.empty());
        assertEquals(1, preview.shops());
        JsonObject document = staged(preview, "shops/dtltraders/bakery.json");
        ShopDocument.Result parsed = ShopDocument.parse("dtltraders:bakery", document);
        assertEquals(List.of(), parsed.problems());
        ShopDocument shop = parsed.shop().orElseThrow();
        assertEquals("&6The Bakery", shop.title());
        assertTrue(shop.confirm());
        assertEquals(2, shop.pages().size());
        assertEquals(3, shop.pages().get(1).rows(), "a size that is not a multiple of 9 falls back to 27");
        assertEquals("Page 2", shop.pages().get(1).name());
        ShopDocument.Entry bread = shop.entry("dtl-3").orElseThrow();
        assertEquals(ShopDocument.Type.BUY, bread.type());
        assertEquals(0, new BigDecimal("12").compareTo(bread.price()));
        assertEquals(4, bread.item().count());
        assertTrue(bread.item().components().orElseThrow().contains("custom_name=\"§eFresh\""), bread.item().components().toString());
        assertTrue(bread.item().components().orElseThrow().contains("\"minecraft:unbreaking\":1"));
        assertEquals(16, bread.limit().perPlayer());
        assertEquals(86400, bread.limit().resetSeconds());
        assertTrue(bread.allowStack());
        assertEquals(Optional.of("{player} bought bread"), bread.broadcast());
        assertEquals(List.of("&7Baked today"), bread.display().lore());
        assertEquals(0, new BigDecimal("8").compareTo(bread.discount().orElseThrow().price()));
        assertEquals(ShopDocument.Type.SELL, shop.entry("dtl-4").orElseThrow().type());
        assertTrue(shop.entry("dtl-4").orElseThrow().sellPartial());
        ShopDocument.Entry trade = shop.entry("dtl-5").orElseThrow();
        assertEquals(10, trade.needs().getFirst().amount());
        assertEquals("minecraft:diamond", trade.gives().getFirst().item().id());
        ShopDocument.Entry pet = shop.entry("dtl-7").orElseThrow();
        assertEquals(ShopDocument.Type.COMMAND, pet.type());
        assertEquals(List.of("givepet {player} wolf", "heal {player}"), pet.commands().stream().map(ShopDocument.ShopCommand::command).toList());
        assertEquals(ShopDocument.Type.STATIC, shop.entry("dtl-9").orElseThrow().type());
    }

    @Test void versionFiveGuisFilesHoldSeveralShops() throws Exception {
        Path root = folder.resolve("dtlTraders");
        Files.createDirectories(root);
        Files.writeString(root.resolve("guis.yml"), BAKERY + "Second Shop:\n  title: Two\n  pages:\n    page-0:\n      size: 9\n");
        try (DocumentStore store = new DocumentStore(folder.resolve("animus"))) {
            ShopImporter.Preview preview = new ShopImporter().preview(root, Optional.empty(), store);
            assertEquals(2, preview.shops());
            assertTrue(Files.exists(preview.directory().resolve("shops/dtltraders/second-shop.json")));
            assertEquals("absent", preview.summary().getAsJsonObject("limitData").get("status").getAsString());
        }
    }

    @Test void aFolderWithoutShopsIsRefused() {
        assertThrows(java.io.IOException.class, () -> {
            try (DocumentStore store = new DocumentStore(folder.resolve("animus"))) { new ShopImporter().preview(folder, Optional.empty(), store); }
        });
    }

    @Test void everySourceFieldIsClassified() throws Exception {
        ShopImporter.Preview preview = preview(Optional.empty());
        JsonObject report = staged(preview, "reports/bakery.json");
        JsonObject fields = report.getAsJsonObject("fields");
        assertEquals("CONVERTED", fields.get("/pages/page-0/buy-items/item-10/trade-price").getAsString());
        assertEquals("UNSUPPORTED_PRESERVED", fields.get("/command").getAsString());
        assertEquals("UNSUPPORTED_PRESERVED", fields.get("/pages/page-0/buy-items/item-10/custom-input-enabled").getAsString());
        assertEquals("CONVERTED_WITH_CHANGES", fields.get("/pages/page-0/buy-items/item-10/limit-reset-seconds").getAsString());
        assertEquals("CONVERTED_WITH_CHANGES", fields.get("/pages/page-0/buy-items/item-11/commands/1/executor").getAsString());
        assertEquals("CONVERTED_WITH_CHANGES", fields.get("/pages/page-1/size").getAsString());
        assertTrue(Files.exists(preview.directory().resolve("originals/shops/Bakery/Bakery.yml")));
        assertTrue(Files.exists(preview.directory().resolve("originals/data.dat")));
    }

    @Test void readableLimitDataBecomesPlayerCounts() throws Exception {
        ShopImporter.Preview preview = preview(Optional.empty());
        JsonObject data = preview.summary().getAsJsonObject("limitData");
        assertEquals("converted", data.get("status").getAsString());
        assertEquals(1, data.get("orphaned").getAsInt(), "item 4 has no limit, so its count is left out");
        JsonObject limits = staged(preview, "limits.json");
        JsonObject count = limits.getAsJsonArray("counts").get(0).getAsJsonObject();
        assertEquals("dtltraders:bakery", count.get("shop").getAsString());
        assertEquals("dtl-3", count.get("entry").getAsString());
        assertEquals(PLAYER, count.get("player").getAsString());
        assertEquals(6, count.get("used").getAsInt());
    }

    @Test void unreadableLimitDataIsPreservedAndReported() throws Exception {
        Path root = dtl();
        Files.write(root.resolve("data.dat"), new byte[]{0, 5, '6', '.', '4'});
        try (DocumentStore store = new DocumentStore(folder.resolve("animus"))) {
            ShopImporter.Preview preview = new ShopImporter().preview(root, Optional.empty(), store);
            assertEquals("preserved-unread", preview.summary().getAsJsonObject("limitData").get("status").getAsString());
            assertFalse(Files.exists(preview.directory().resolve("limits.json")));
            assertTrue(Files.exists(preview.directory().resolve("originals/data.dat")));
        }
    }

    @Test void traderLinksAreCheckedAndVersionThreeStockConverts() throws Exception {
        Path saves = folder.resolve("saves.yml");
        Files.writeString(saves, SAVES);
        ShopImporter.Preview preview = preview(Optional.of(saves));
        assertEquals(2, preview.shops());
        List<String> statuses = new java.util.ArrayList<>();
        preview.summary().getAsJsonArray("links").forEach(link -> statuses.add(link.getAsJsonObject().get("status").getAsString()));
        assertEquals(List.of("linked", "missing-shop", "converted-from-version-3"), statuses);
        ShopDocument legacy = ShopDocument.parse("dtltraders:converted-oldbob", staged(preview, "shops/dtltraders/converted-oldbob.json")).shop().orElseThrow();
        ShopDocument.Entry stone = legacy.pages().getFirst().grid(ShopDocument.Mode.BUY).get(0);
        assertEquals(16, stone.item().count());
        assertEquals(0, new BigDecimal("32").compareTo(stone.price()), "a per-item price times the amount");
        ShopDocument.Entry sword = legacy.pages().getFirst().grid(ShopDocument.Mode.BUY).get(1);
        assertEquals(0, new BigDecimal("100").compareTo(sword.price()), ".sp means the price is for the bundle");
        assertEquals(ShopDocument.Type.SELL, legacy.pages().getFirst().grid(ShopDocument.Mode.SELL).get(0).type());
        JsonObject records = staged(preview, "originals/saves-trader-records.json");
        assertTrue(records.getAsJsonObject("2").has("wallet"));
        assertFalse(records.has("missing"));
    }

    @Test void applyInstallsShopsAndCountsOnceAndCanBeRolledBack() throws Exception {
        try (DocumentStore store = new DocumentStore(folder.resolve("animus"))) {
            ShopImporter importer = new ShopImporter();
            ShopImporter.Preview preview = importer.preview(dtl(), Optional.empty(), store);
            String transaction = importer.apply(store, preview.id());
            assertNotNull(store.read("shops/dtltraders/bakery.json"));
            assertNotNull(store.read("state/shops/imports/" + preview.id() + ".json"));
            assertEquals("already-applied", importer.apply(store, preview.id()));
            ShopCatalog catalog = ShopCatalog.load(folder.resolve("animus"), id -> id.startsWith("dtltraders:"));
            assertEquals(List.of(), catalog.problems());
            assertTrue(catalog.shop("dtltraders:bakery").isPresent());
            store.rollback(transaction);
            assertNull(store.read("shops/dtltraders/bakery.json"));
        }
    }

    @Test void importedCommandShopsNeedTheAllowList() throws Exception {
        try (DocumentStore store = new DocumentStore(folder.resolve("animus"))) {
            ShopImporter importer = new ShopImporter();
            importer.apply(store, importer.preview(dtl(), Optional.empty(), store).id());
            ShopCatalog catalog = ShopCatalog.load(folder.resolve("animus"), id -> false);
            assertTrue(catalog.shop("dtltraders:bakery").isEmpty());
            assertTrue(catalog.problems().getFirst().contains("runs commands"), catalog.problems().toString());
        }
    }

    @Test void citizensImportsLinkTraderNpcsToTheirShop() throws Exception {
        Path saves = folder.resolve("saves.yml");
        Files.writeString(saves, SAVES.replace("              '1':", "              '1':\n                uuid: 2fde8840-5f7a-5a37-bc24-357183d2e276").replace("              '2':", "              '2':\n                uuid: 3fde8840-5f7a-5a37-bc24-357183d2e276"));
        CitizensImporter.Preview preview = new CitizensImporter().preview(saves, folder.resolve("animus"), java.util.Map.of());
        JsonObject baker = JsonDocuments.object(Files.readString(preview.directory().resolve("npcs/citizens/citizens-0.json")));
        assertEquals("dtltraders:bakery", baker.getAsJsonObject("modules").getAsJsonObject("animus:shop").get("shop").getAsString());
        JsonObject bob = JsonDocuments.object(Files.readString(preview.directory().resolve("npcs/citizens/citizens-2.json")));
        assertEquals("dtltraders:converted-oldbob", bob.getAsJsonObject("modules").getAsJsonObject("animus:shop").get("shop").getAsString());
        JsonObject report = JsonDocuments.object(Files.readString(preview.directory().resolve("reports/0.json")));
        assertEquals("CONVERTED", report.getAsJsonObject("fields").get("/traits/trader/guiName").getAsString());
        assertFalse(preview.summary().get("issues").toString().contains("animus:shop"), preview.summary().get("issues").toString());
    }
}
