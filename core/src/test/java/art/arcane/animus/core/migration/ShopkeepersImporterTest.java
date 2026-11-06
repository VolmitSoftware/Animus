package art.arcane.animus.core.migration;

import art.arcane.animus.core.editor.SharedFixtures;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.shop.ShopBinding;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ShopkeepersImporterTest {
    @TempDir Path temporary;

    private static String fixture(String name) throws IOException { return Files.readString(SharedFixtures.fixture("shopkeepers/" + name)); }
    private static Path path(String name) { return SharedFixtures.fixture("shopkeepers/" + name); }

    private static ShopDocument shop(ShopkeepersImporter.Conversion conversion, int number) {
        ShopDocument.Result result = ShopDocument.parse("shopkeepers:shop-" + number, conversion.files().get("shops/shopkeepers/shop-" + number + ".json"));
        assertEquals(List.of(), result.problems());
        return result.shop().orElseThrow();
    }

    @Test void everyStagedShopAndNpcIsValid() throws IOException {
        for (String save : List.of("admin.yml", "player.yml", "minimal.yml")) {
            ShopkeepersImporter.Conversion conversion = ShopkeepersImporter.convert(fixture(save), Optional.empty(), Optional.empty());
            for (Map.Entry<String, String> file : conversion.files().entrySet()) {
                if (file.getKey().startsWith("shops/")) {
                    String id = "shopkeepers:" + file.getKey().substring(18, file.getKey().length() - 5);
                    assertEquals(List.of(), ShopDocument.parse(id, file.getValue()).problems(), save + " " + file.getKey());
                }
                if (file.getKey().startsWith("npcs/")) assertEquals(file.getKey(), NpcDefinition.parse(file.getValue()).path());
            }
        }
    }

    @Test void adminOffersBecomeUnlimitedTrades() throws IOException {
        ShopkeepersImporter.Conversion conversion = ShopkeepersImporter.convert(fixture("admin.yml"), Optional.empty(), Optional.empty());
        ShopDocument smith = shop(conversion, 1);
        ShopDocument.Entry sword = smith.entry("offer-1").orElseThrow();
        assertEquals(ShopDocument.Type.TRADE, sword.type());
        assertEquals(Optional.of("[minecraft:custom_name={text:\"Blade\",italic:0b},minecraft:enchantments={\"minecraft:sharpness\":5},minecraft:lore=[{text:\"Sharp\",color:\"gray\"}]]"), sword.item().components());
        assertEquals(2, sword.needs().size());
        assertEquals(20, sword.needs().getFirst().amount());
        assertTrue(sword.stock().isEmpty());
        assertEquals(Optional.of("[!minecraft:food]"), smith.entry("offer-2").orElseThrow().item().components());
        assertEquals(Optional.of("town.shop.vip"), shop(conversion, 2).permission());
        ShopDocument farmer = shop(conversion, 3);
        assertEquals(ShopDocument.Type.STATIC, farmer.entry("offer-1").orElseThrow().type(), "old Bukkit meta is kept, not traded");
        assertTrue(farmer.entry("offer-1").orElseThrow().item().imported().isPresent());
        assertTrue(farmer.entry("offer-2").isEmpty(), "an offer without a first item is skipped");
        assertEquals(ShopDocument.Type.TRADE, farmer.entry("offer-3").orElseThrow().type(), "an old item without meta is exact");
        assertEquals(ShopDocument.Type.STATIC, shop(conversion, 4).entry("offer-1").orElseThrow().type(), "a closed shop only shows");
        assertTrue(shop(conversion, 6).permission().isEmpty());
        assertFalse(conversion.files().containsKey("shops/shopkeepers/shop-7.json"), "unknown shop types stay in source.yml");
        JsonObject summary = conversion.summary();
        assertEquals(6, summary.get("shops").getAsInt());
        assertEquals(1, summary.get("npcs").getAsInt());
        assertEquals(2, summary.getAsJsonArray("issues").size());
    }

    @Test void mobShopkeepersBecomeDormantNpcsBoundToTheirShop() throws IOException {
        ShopkeepersImporter.Conversion conversion = ShopkeepersImporter.convert(fixture("admin.yml"), Optional.empty(), Optional.empty());
        NpcDefinition farmer = NpcDefinition.parse(conversion.files().get("npcs/shopkeepers/shopkeeper-3.json"));
        assertEquals("minecraft:villager", farmer.json().get("type").getAsString());
        assertFalse(farmer.spawned());
        assertEquals("6c1b7a43-9a0e-4d7f-8a39-1f9f0f2a0003", farmer.uuid().toString());
        assertEquals(-7.5, farmer.json().getAsJsonObject("position").get("x").getAsDouble());
        assertTrue(ShopBinding.of(farmer).isEmpty(), "an imported binding opens nothing until reviewed");
        assertEquals("shopkeepers:shop-3", farmer.json().getAsJsonObject("modules").getAsJsonObject("animus:shop").get("shop").getAsString());
        JsonObject module = farmer.json().getAsJsonObject("modules").getAsJsonObject("animus:shop");
        assertEquals("minecraft:farmer", module.getAsJsonObject("villager").get("profession").getAsString(), "the profession shows in the trading screen title");
        assertEquals(2, module.getAsJsonObject("villager").get("level").getAsInt());
        assertFalse(farmer.json().getAsJsonObject("modules").getAsJsonObject("shopkeepers:review").has("profession"));
        assertTrue(CitizensDropIn.waiting(farmer.json(), "spawn"));
        assertTrue(CitizensDropIn.waiting(farmer.json(), "shops"));
        NpcDefinition wanderer = NpcDefinition.parse(ShopkeepersImporter.convert(fixture("minimal.yml"), Optional.empty(), Optional.empty()).files().get("npcs/shopkeepers/shopkeeper-1.json"));
        assertEquals("minecraft:wandering_trader", wanderer.json().get("type").getAsString());
    }

    @Test void citizensLinksResolveByUuidAndByOldNumericId() throws IOException {
        JsonObject summary = ShopkeepersImporter.convert(fixture("admin.yml"), Optional.empty(), Optional.of(fixture("admin-citizens.yml"))).summary();
        assertEquals(2, summary.getAsJsonObject("links").get("inCitizensSave").getAsInt());
        assertEquals(1, summary.getAsJsonObject("links").get("notInCitizensSave").getAsInt());
        JsonObject first = summary.getAsJsonArray("bindings").get(0).getAsJsonObject();
        assertEquals("citizens-0", first.get("npc").getAsString());
        JsonObject old = summary.getAsJsonArray("bindings").get(2).getAsJsonObject();
        assertEquals("3f0c2b1e-0000-4000-8000-000000000007", old.get("citizensUuid").getAsString());
        assertEquals(3, ShopkeepersImporter.convert(fixture("admin.yml"), Optional.empty(), Optional.empty()).summary().getAsJsonObject("links").get("unchecked").getAsInt());
    }

    @Test void playerShopsTradeAgainstTheirContainersWithTheConfiguredCurrency() throws IOException {
        ShopkeepersImporter.Conversion conversion = ShopkeepersImporter.convert(fixture("player.yml"), Optional.of(fixture("player-config.yml")), Optional.empty());
        ShopDocument selling = shop(conversion, 10);
        assertTrue(selling.stocked());
        assertEquals("Alex's shop", selling.title());
        assertTrue(selling.log());
        ShopDocument.Entry beef = selling.entry("offer-1").orElseThrow();
        assertEquals(List.of("minecraft:gold_ingot", "minecraft:gold_nugget"), beef.needs().stream().map((ShopDocument.Goods goods) -> goods.item().id()).toList());
        assertEquals(List.of(3, 3), beef.needs().stream().map(ShopDocument.Goods::amount).toList(), "30 = 3 x 9 + 3");
        assertEquals(1, selling.entry("offer-2").orElseThrow().needs().size(), "5 is under the high currency minimum");
        assertTrue(selling.entry("offer-3").isEmpty(), "700 cannot be paid with one stack of each currency");
        ShopDocument buying = shop(conversion, 11);
        assertEquals(List.of(new ShopDocument.Container("world", 20, 63, 5)), buying.containers());
        ShopDocument.Entry wheat = buying.entry("offer-1").orElseThrow();
        assertEquals("minecraft:wheat", wheat.needs().getFirst().item().id());
        assertEquals(2, wheat.gives().getFirst().amount());
        assertTrue(buying.entry("offer-2").isEmpty());
        assertEquals(2, shop(conversion, 12).containers().size());
        assertEquals(2, shop(conversion, 12).entry("offer-1").orElseThrow().needs().size());
        assertTrue(shop(conversion, 13).entry("offer-1").isEmpty(), "book offers are preserved only");
        assertEquals(ShopDocument.Type.STATIC, shop(conversion, 14).entry("offer-1").orElseThrow().type(), "no owner, no trading");
        assertTrue(shop(conversion, 14).owner().isEmpty());
        JsonObject hired = JsonDocuments.object(conversion.files().get("shops/shopkeepers/shop-15.json")).getAsJsonObject("imported");
        assertTrue(hired.get("forHire").getAsBoolean());
        assertEquals("trade", hired.getAsJsonArray("members").get(0).getAsJsonObject().get("access").getAsString());
    }

    @Test void applyBindsInstalledCitizensNpcsAndKeepsTheRestPending() throws IOException {
        Path data = temporary.resolve("data");
        try (DocumentStore store = new DocumentStore(data)) {
            CitizensImporter.Preview citizens = new CitizensImporter().preview(path("admin-citizens.yml"), store, Map.of());
            new CitizensImporter().apply(store, citizens.id());
            NpcRepository npcs = new NpcRepository(store);
            npcs.reload();
            ShopkeepersImporter importer = new ShopkeepersImporter();
            ShopkeepersImporter.Preview preview = importer.preview(path("admin.yml"), Optional.empty(), Optional.of(path("admin-citizens.yml")), store);
            ShopkeepersImporter.Applied applied = importer.apply(store, preview.id(), npcs.installed());
            assertEquals(2, applied.bound());
            assertEquals(1, applied.pending().size());
            npcs.reload();
            NpcDefinition smith = npcs.all().stream().filter((NpcDefinition definition) -> definition.id().equals("citizens-0")).findFirst().orElseThrow();
            assertEquals("shopkeepers:shop-1", smith.json().getAsJsonObject("modules").getAsJsonObject("animus:shop").get("shop").getAsString());
            assertEquals(2, smith.revision());
            assertNotNull(store.read("shops/shopkeepers/shop-3.json"));
            assertNotNull(store.read("npcs/shopkeepers/shopkeeper-3.json"));
            assertThrows(IOException.class, () -> importer.apply(store, preview.id(), npcs.installed()));
            store.rollback(applied.transaction());
            assertNull(store.read("shops/shopkeepers/shop-1.json"));
        }
    }

    @Test void shopkeepersFirstThenCitizensBindsWhenTheNpcsArrive() throws IOException {
        Path data = temporary.resolve("data");
        try (DocumentStore store = new DocumentStore(data)) {
            ShopkeepersImporter importer = new ShopkeepersImporter();
            ShopkeepersImporter.Preview preview = importer.preview(path("admin.yml"), Optional.empty(), Optional.empty(), store);
            ShopkeepersImporter.Applied applied = importer.apply(store, preview.id(), NpcRepository.Installed.NONE);
            assertEquals(0, applied.bound());
            assertEquals(3, applied.pending().size());
            assertTrue(ShopkeepersImporter.bind(store, applied.pending(), NpcRepository.Installed.NONE).isEmpty());
            CitizensImporter.Preview citizens = new CitizensImporter().preview(path("admin-citizens.yml"), store, Map.of());
            new CitizensImporter().apply(store, citizens.id());
            NpcRepository npcs = new NpcRepository(store);
            npcs.reload();
            ShopkeepersImporter.Applied later = ShopkeepersImporter.bind(store, applied.pending(), npcs.installed()).orElseThrow();
            assertEquals(2, later.bound());
            assertEquals(1, later.pending().size());
        }
    }
}
