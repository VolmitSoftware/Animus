package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.NpcRepository;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class CitizensShopsTest {
    @TempDir Path root;

    static final String SHOPS = """
            global:
              Smith Shop:
                title: Smithy
                type: TRADER
                viewPermission: town.smith
                pages:
                  '0':
                    title: Tools
                    items:
                      '0':
                        cost:
                          '0': {type: money, money: 5.0}
                        result:
                          '0': {type: items, items: {'0': {type: IRON_SWORD, amount: 1}}}
                        display: {type: IRON_SWORD, amount: 1}
                        timesPurchasable: 2
                      '1':
                        cost:
                          '0': {type: items, items: {'0': {type: EMERALD, amount: 2}}}
                        result:
                          '0': {type: items, items: {'0': {type: BREAD, amount: 4}}}
                        display: {type: BREAD, amount: 4}
                      '2':
                        cost:
                          '0': {type: items, items: {'0': {type: WHEAT, amount: 16}}}
                        result:
                          '0': {type: money, money: 3.0}
                        display: {type: WHEAT, amount: 16}
                        maxRepeatsOnShiftClick: true
                      '3':
                        cost:
                          '0': {type: money, money: 10.0}
                          '1': {type: permissions, permissions: ['vip.buy']}
                        result:
                          '0': {type: command, commands: ['give <p> diamond 1'], server: true}
                          '1': {type: experience, exp: 5}
                        display: {type: DIAMOND, amount: 1}
                        globalTimesPurchasable: 100
                      '4':
                        cost:
                          '0': {type: experience, exp: 3}
                        result:
                          '0': {type: items, items: {'0': {type: GOLDEN_APPLE, amount: 1}}}
                        display: {type: GOLDEN_APPLE, amount: 1}
                      '41':
                        display: {type: ARROW, amount: 1}
            npc:
              00000000-0000-0000-0000-000000000001:
                pages:
                  '0':
                    items:
                      '0':
                        cost:
                          '0': {type: money, money: 1.0}
                        result:
                          '0': {type: items, items: {'0': {type: APPLE, amount: 1}}}
                        display: {type: APPLE, amount: 1}
            """;

    static final String SAVES = """
            npc:
              '1':
                uuid: 00000000-0000-0000-0000-000000000001
                name: Smith
                metadata: {protected: false}
                traits:
                  type: PLAYER
                  spawned: true
                  location: {world: world, x: 1, y: 64, z: 1}
                  shop: {rightClickShop: Smith Shop}
                  dropstrait:
                    drops:
                      '0': {chance: 0.5, drop: {type: EMERALD, amount: 3}}
                      '1': {chance: 1.0, drop: {type: BONE, amount: 1}}
                  commandtrait:
                    commands:
                      '0': {command: 'say hi <p>', hand: RIGHT, n: 2, cost: 4.0, player: false}
              '2':
                uuid: 00000000-0000-0000-0000-000000000002
                name: Keeper
                traits:
                  type: PLAYER
                  location: {world: world, x: 2, y: 64, z: 2}
                  shop: {rightClickShop: own}
            """;

    @AfterEach void clearGuard() { CitizensGuard.clear(); }

    private static JsonObject json(String text) {
        try { return JsonDocuments.object(text); } catch (IOException invalid) { throw new AssertionError(invalid); }
    }

    @Test void shopItemsBecomeBuySellTradeAndCommandEntries() throws IOException {
        CitizensShops.Converted converted = CitizensShops.convert(CitizensImporter.parseYaml(SHOPS), json("{\"1\":\"00000000-0000-0000-0000-000000000001\"}"));
        assertEquals(Map.of("Smith Shop", "citizens:smith-shop"), converted.global());
        assertEquals(Map.of("00000000-0000-0000-0000-000000000001", "citizens:npc-1"), converted.npc());
        String content = converted.documents().get("shops/citizens/smith-shop.json");
        ShopDocument.Result parsed = ShopDocument.parse("citizens:smith-shop", content);
        assertEquals(List.of(), parsed.problems());
        assertTrue(ShopDocument.parse("citizens:npc-1", converted.documents().get("shops/citizens/npc-1.json")).problems().isEmpty());
        JsonObject shop = json(content);
        assertEquals("Smithy", shop.get("title").getAsString());
        assertEquals("town.smith", shop.get("permission").getAsString());
        assertEquals("native", shop.get("style").getAsString());
        JsonObject page = shop.getAsJsonArray("pages").get(0).getAsJsonObject();
        assertEquals(5, page.get("rows").getAsInt());
        assertEquals(json("{\"id\":\"p1-s0\",\"item\":{\"id\":\"minecraft:iron_sword\",\"count\":1},\"price\":5,\"type\":\"buy\",\"limit\":{\"perPlayer\":2}}"), page.getAsJsonObject("buy").getAsJsonObject("0"));
        JsonObject trade = page.getAsJsonObject("trade").getAsJsonObject("1");
        assertEquals(com.google.gson.JsonParser.parseString("[{\"item\":{\"id\":\"minecraft:emerald\"},\"amount\":2}]"), trade.get("needs"));
        assertEquals(com.google.gson.JsonParser.parseString("[{\"item\":{\"id\":\"minecraft:bread\"},\"amount\":4}]"), trade.get("gives"));
        JsonObject sell = page.getAsJsonObject("sell").getAsJsonObject("2");
        assertEquals(json("{\"id\":\"p1-s2\",\"item\":{\"id\":\"minecraft:wheat\",\"count\":16},\"price\":3,\"type\":\"sell\",\"allowStack\":true}"), sell);
        JsonObject command = page.getAsJsonObject("buy").getAsJsonObject("3");
        assertEquals("command", command.get("type").getAsString());
        assertEquals(com.google.gson.JsonParser.parseString("[{\"command\":\"give {player} diamond 1\",\"as\":\"console\"},{\"command\":\"xp add {player} 5 levels\",\"as\":\"console\"}]"), command.get("commands"));
        assertEquals("vip.buy", command.get("permission").getAsString());
        assertEquals(json("{\"global\":100}"), command.get("limit"));
        assertEquals(json("{\"id\":\"p1-s4\",\"item\":{\"id\":\"minecraft:golden_apple\",\"count\":1},\"price\":0,\"type\":\"buy\",\"levels\":3}"),
                page.getAsJsonObject("buy").getAsJsonObject("4"), "an experience cost becomes a levels price");
        assertFalse(page.getAsJsonObject("buy").has("41"), "the next page button icon is the window's own");
        assertTrue(converted.warnings().stream().noneMatch((String warning) -> warning.contains("experience")), converted.warnings().toString());
    }

    @Test void theDropInInstallsShopsDropsAndChargedCommandsAndRollsThemBack() throws Exception {
        Path imports = root.resolve("plugins/Animus/imports");
        Files.createDirectories(imports.resolve("citizenscmd"));
        Files.writeString(imports.resolve("saves.yml"), SAVES);
        Files.writeString(imports.resolve("shops.yml"), SHOPS);
        Files.writeString(imports.resolve("citizenscmd/saves.yml"), "npc-data:\n  npc-2:\n    cooldown: 10\n    price: 0\n    right-click-commands: ['[console] heal %p%']\n    left-click-commands: []\n");
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            CitizensDropIn flow = new CitizensDropIn(store, npcs, new CitizensDropIn.Host(Optional.of(root.resolve("plugins")), () -> false, () -> false, () -> false));
            String staged = String.join("\n", flow.preview("reject", Optional.empty(), Map.of(), false));
            assertTrue(staged.contains("Next: /animus import citizens apply"), staged);
            assertTrue(staged.contains("Also read beside the save: citizenscmd.yml, shops.yml; 2 Citizens shops install in shops/citizens/"), staged);
            flow.applyStaged();
            String smithy = store.read("shops/citizens/smith-shop.json");
            assertNotNull(smithy, "the converted shop is installed with the NPCs");
            JsonObject smith = json(store.read("npcs/world/citizens-1.json"));
            JsonObject modules = smith.getAsJsonObject("modules");
            assertEquals(json("{\"shop\":\"citizens:smith-shop\",\"openOn\":\"right\"}"), modules.get("animus:shop"));
            assertEquals(json("{\"killable\":true,\"death\":{\"drops\":[{\"item\":{\"id\":\"minecraft:emerald\",\"count\":3},\"chance\":0.5},{\"item\":{\"id\":\"minecraft:bone\",\"count\":1}}]}}"), modules.get("animus:health"));
            JsonObject keeper = json(store.read("npcs/world/citizens-2.json")).getAsJsonObject("modules");
            assertFalse(keeper.has("animus:shop"), "a right-click shop name that names no shop opens nothing");
            String commands = store.read("behaviors/citizens/commands-2.json");
            assertNotNull(commands, "the CitizensCMD commands make the NPC's command behavior");
            assertTrue(commands.contains("heal {player_name}") && commands.contains("citizenscmd-2-cooldown"), commands);
            assertTrue(store.read("behaviors/citizens/commands-1.json").contains("animus:charge"));

            assertTrue(CommandAllowList.allowed(CommandAllowList.read(store)).contains("citizens"), "apply lets the charged commands run");
            flow.rollbackApplied();
            assertNull(store.read("shops/citizens/smith-shop.json"), "rollback removes the converted shops");
        }
    }
}
