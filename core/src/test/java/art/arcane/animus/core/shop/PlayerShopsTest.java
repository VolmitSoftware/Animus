package art.arcane.animus.core.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.config.ShopSettings;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.gui.FakeNpcRuntime;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.RichText;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which blocks keep whose stock, the shop limit, and a shop that closes when its chest is lost or broken. */
class PlayerShopsTest {
    @TempDir Path directory;
    private AnimusRuntime runtime;
    private final MockScreenHost host = new MockScreenHost();
    private final Player sam = new Player("Sam"), alex = new Player("Alex");
    private ShopService<Player, Stack> shops;
    static final String PATH = "shops/players/sams-stall.json";

    static String stall(UUID owner, String extra) {
        return "{\"schemaVersion\":1,\"revision\":1,\"id\":\"players:sams-stall\",\"title\":\"Sam's stall\",\"kind\":\"selling\"" + extra + ","
                + "\"owner\":{\"uuid\":\"" + owner + "\",\"name\":\"Sam\"},\"containers\":[{\"world\":\"world\",\"x\":1,\"y\":64,\"z\":2},{\"world\":\"world\",\"x\":2,\"y\":64,\"z\":2}],"
                + "\"pages\":[{\"name\":\"Offers\",\"trade\":{\"0\":{\"id\":\"t\",\"type\":\"trade\",\"item\":{\"id\":\"minecraft:diamond\"},"
                + "\"needs\":[{\"item\":{\"id\":\"minecraft:emerald\"},\"amount\":5}],\"gives\":[{\"item\":{\"id\":\"minecraft:diamond\"}}]}}}]}";
    }

    @BeforeEach void start() throws IOException {
        runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        shops = new ShopService<>(runtime, host, new FakeNpcRuntime<Player>((UUID id) -> id.equals(sam.id) ? Optional.of(sam) : id.equals(alex.id) ? Optional.of(alex) : Optional.empty()),
                Runnable::run, (String name) -> Optional.empty(), (String line) -> {});
        Path file = directory.resolve(PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, stall(sam.id, ""));
        shops.reload((ShopCatalog ignored) -> {});
    }

    @AfterEach void stop() { shops.close(); runtime.closeAsync(); }

    private JsonObject stored() throws IOException { return JsonDocuments.object(runtime.documents().read(PATH)); }

    @Test void aBlockNamesTheShopThatKeepsItsStock() {
        PlayerShops players = shops.players();
        assertTrue(players.any());
        assertEquals(Optional.of("players:sams-stall"), players.at(Set.of("minecraft:overworld", "world"), 2, 64, 2).map(ShopDocument::id));
        assertEquals(Optional.empty(), players.at(Set.of("world"), 3, 64, 2));
        assertEquals(Optional.empty(), players.at(Set.of("world_nether"), 1, 64, 2));
        assertEquals(1, players.owned(sam.id).size());
        assertEquals(0, players.owned(alex.id).size());
    }

    @Test void theShopLimitIsTheHighestNodeElseTheConfigAndNoneForAnAdmin() {
        Set<String> nodes = new java.util.HashSet<>(Set.of("animus.shops.limit.2", "animus.shops.limit.7"));
        assertEquals(OptionalInt.of(7), PlayerShops.limit(Optional.of(sam.id), nodes::contains, ShopSettings.DEFAULT));
        assertEquals(OptionalInt.of(3), PlayerShops.limit(Optional.of(sam.id), (String node) -> false, ShopSettings.DEFAULT));
        assertEquals(OptionalInt.empty(), PlayerShops.limit(Optional.of(sam.id), PermissionNodes.ADMIN::equals, ShopSettings.DEFAULT));
        assertEquals(Optional.of("You own 3 shops, your limit; remove one to make another"), PlayerShops.full(3, OptionalInt.of(3)));
        assertEquals(Optional.of("You cannot make shops; ask an admin for an animus.shops.limit.<n> node"), PlayerShops.full(0, OptionalInt.of(0)));
        assertEquals(Optional.empty(), PlayerShops.full(2, OptionalInt.of(3)));
    }

    @Test void aLostChestClosesTheShopOnceAndTellsItsOwner() throws IOException {
        ShopDocument stall = shops.players().shop("players:sams-stall").orElseThrow();
        shops.players().lost(stall, "there is no chest at world 1 64 2");
        assertEquals("There is no chest at world 1 64 2", stored().get("closed").getAsString());
        assertEquals(List.of("Your shop Sam's stall closed: There is no chest at world 1 64 2. Fix it, then open the shop again from its menu."),
                sam.messages.stream().map(RichText::plain).toList());
        shops.players().lost(stall, "protection does not let the owner open the chest at 1 64 2");
        assertEquals("There is no chest at world 1 64 2", stored().get("closed").getAsString(), "a closed shop stays as it is");
        assertEquals(1, sam.messages.size());
    }

    @Test void theOwnerBreakingAChestTakesItOutAndTheLastOneClosesTheShop() throws IOException {
        ShopDocument stall = shops.players().shop("players:sams-stall").orElseThrow();
        shops.players().broken(stall, new PlayerShops.Block("world", 1, 64, 2));
        assertEquals("[{\"world\":\"world\",\"x\":2,\"y\":64,\"z\":2}]", stored().getAsJsonArray("containers").toString());
        assertFalse(stored().has("closed"));
        ShopDocument one = shops.players().shop("players:sams-stall").orElseThrow();
        assertEquals(1, one.containers().size(), "the catalog reloaded after the write");
        shops.players().broken(one, new PlayerShops.Block("world", 2, 64, 2));
        assertEquals("Its chest at world 2 64 2 was broken", stored().get("closed").getAsString());
        assertEquals(1, stored().getAsJsonArray("containers").size(), "a shop keeps its last chest listed");
    }

    @Test void aClosedShopTellsABuyerWhoClicksIt() throws IOException {
        Files.writeString(directory.resolve(PATH), stall(sam.id, ",\"closed\":\"Its owner closed it\""));
        shops.reload((ShopCatalog ignored) -> {});
        shops.openShop(alex, "players:sams-stall", Optional.empty());
        assertEquals(List.of("Sam's stall is closed: its owner closed it."), alex.messages.stream().map(RichText::plain).toList());
    }

    private void npc(String id, String shop) throws IOException {
        Path file = directory.resolve("npcs/" + id + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"schemaVersion\":2,\"revision\":1,\"uuid\":\"" + UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "\",\"id\":\"" + id
                + "\",\"group\":\"\",\"name\":\"" + id + "\",\"type\":\"minecraft:villager\",\"spawned\":true,\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0},"
                + "\"modules\":{\"animus:shop\":{\"shop\":\"" + shop + "\"}}}");
        assertTrue(runtime.npcs().reload().isEmpty(), runtime.npcs().diagnostics().toString());
    }

    @Test void aChestShopOnlyItsNpcOpensIsRunByThatNpc() throws IOException {
        npc("stall", "players:sams-stall");
        npc("bakery", "town:bakery");
        assertEquals(List.of("players:sams-stall"), shops.players().runBy(runtime.npcs().stored(), Set.of("stall")).stream().map(ShopDocument::id).toList());
        assertEquals(List.of(), shops.players().runBy(runtime.npcs().stored(), Set.of("bakery")), "a shop without chests is no chest shop");
        npc("stall-two", "players:sams-stall");
        assertEquals(List.of(), shops.players().runBy(runtime.npcs().stored(), Set.of("stall")), "another NPC still opens it");
        assertEquals(1, shops.players().runBy(runtime.npcs().stored(), Set.of("stall", "stall-two")).size());
    }

    @Test void deletingAShopsNpcWithItsShopIsOneChangeThatUndoesBoth() throws IOException {
        npc("stall", "players:sams-stall");
        String line = new art.arcane.animus.core.admin.Administration(runtime).delete("stall", List.of("players:sams-stall"), "its shop Sam's stall");
        assertTrue(line.startsWith("Deleted stall and its shop Sam's stall; tx "), line);
        assertTrue(runtime.npcs().find("stall").isEmpty());
        assertEquals(null, runtime.documents().read(PATH));
        new art.arcane.animus.core.admin.Administration(runtime).rollback(line.substring(line.indexOf("tx ") + 3));
        assertTrue(runtime.documents().read(PATH) != null, "undo brings the shop back with its NPC");
        runtime.npcs().reload();
        assertTrue(runtime.npcs().find("stall").isPresent());
    }

    @Test void theNpcLimitRefusesAShopInShopWords() {
        assertEquals(Optional.of("A shop needs an NPC and you own 2, your limit; remove one to open a shop"), PlayerShops.npcFull(2, OptionalInt.of(2)));
        assertEquals(Optional.empty(), PlayerShops.npcFull(1, OptionalInt.of(2)));
        assertEquals(Optional.empty(), PlayerShops.npcFull(9, OptionalInt.empty()));
    }

    @Test void aSaleLineSpeaksFromTheShopsSide() throws IOException {
        ShopDocument stall = shops.players().shop("players:sams-stall").orElseThrow();
        assertEquals("&aSam's stall&a sold 1x Diamond to Alex for 5x Emerald", PlayerShops.line(stall, new PlayerShops.Sale("Alex", "1x Diamond", "5x Emerald")));
        Files.writeString(directory.resolve(PATH), stall(sam.id, "").replace("\"kind\":\"selling\"", "\"kind\":\"buying\""));
        shops.reload((ShopCatalog ignored) -> {});
        ShopDocument buyer = shops.players().shop("players:sams-stall").orElseThrow();
        assertEquals("&aSam's stall&a bought 16x Cod from Alex for 1x Emerald", PlayerShops.line(buyer, new PlayerShops.Sale("Alex", "1x Emerald", "16x Cod")));
    }
}
