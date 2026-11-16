package art.arcane.animus.core.owner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A player writes only shops they own that keep their stock in chests; every other shop write is an admin's. */
class PlayerShopWritesTest {
    static final UUID ALEX = OwnedNpcAttackTest.ALEX, SAM = UUID.fromString("0000000b-0000-4000-8000-0000000000b2");
    static final String PATH = "shops/players/alex-shop.json";
    @TempDir Path root;
    private AnimusRuntime runtime;

    @BeforeEach void start() throws IOException {
        runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", root));
        runtime.start();
    }

    @AfterEach void stop() { runtime.close(); }

    static String shop(UUID owner, String extra) {
        return "{\"schemaVersion\":1,\"revision\":1,\"id\":\"players:alex-shop\",\"kind\":\"selling\",\"owner\":{\"uuid\":\"" + owner + "\"}" + extra + ",\"pages\":[{\"name\":\"Offers\"}]}";
    }

    static final String CHEST = ",\"containers\":[{\"world\":\"world\",\"x\":1,\"y\":64,\"z\":2}]";

    private String write(UUID player, String path, String content) throws IOException {
        DocumentStore store = runtime.documents();
        String current = store.read(path);
        return ActingPlayer.call(Optional.of(new ActingPlayer.Actor(player, false, false)),
                () -> store.commit(List.of(new DocumentStore.Change(path, current == null ? "missing" : JsonDocuments.hash(current), content))));
    }

    private String refused(UUID player, String path, String content) {
        return assertThrows(IOException.class, () -> write(player, path, content)).getMessage();
    }

    @Test void aPlayerWritesTheirOwnChestShop() throws IOException {
        write(ALEX, PATH, shop(ALEX, CHEST));
        write(ALEX, PATH, shop(ALEX, CHEST).replace("\"revision\":1", "\"revision\":2").replace("selling", "buying"));
        write(ALEX, PATH, null);
        assertNull(runtime.documents().read(PATH));
    }

    @Test void aPlayerCannotMakeAShopForSomeoneElse() {
        assertEquals("A shop you make is yours; only an admin can make one for another player", refused(SAM, PATH, shop(ALEX, CHEST)));
    }

    @Test void aPlayerCannotMakeAShopWithoutChests() {
        assertEquals("A shop you make keeps its stock in chests", refused(ALEX, PATH, shop(ALEX, "").replace(",\"kind\":\"selling\"", "")));
    }

    @Test void aPlayerCannotTakeOrChangeAnotherPlayersShop() throws IOException {
        write(ALEX, PATH, shop(ALEX, CHEST));
        assertEquals(PATH + " is not your shop; only an admin can change it", refused(SAM, PATH, shop(SAM, CHEST).replace("\"revision\":1", "\"revision\":2")));
        assertEquals(PATH + " is not your shop; only an admin can change it", refused(SAM, PATH, null));
    }

    @Test void aPlayerCannotGiveTheirShopAway() throws IOException {
        write(ALEX, PATH, shop(ALEX, CHEST));
        assertEquals("Only an admin can change who owns a shop", refused(ALEX, PATH, shop(SAM, CHEST).replace("\"revision\":1", "\"revision\":2")));
    }

    @Test void aPlayerCannotOverwriteAnAdminShop() throws IOException {
        String admin = "{\"schemaVersion\":1,\"revision\":1,\"id\":\"town:baker\",\"pages\":[{\"name\":\"Bread\"}]}";
        runtime.documents().commit(List.of(new DocumentStore.Change("shops/town/baker.json", "missing", admin)));
        assertEquals("shops/town/baker.json is not your shop; only an admin can change it",
                refused(ALEX, "shops/town/baker.json", shop(ALEX, CHEST).replace("players:alex-shop", "town:baker").replace("\"revision\":1", "\"revision\":2")));
    }

    @Test void aPlayerShopCannotRunCommandsOrAskForMoney() {
        String commands = shop(ALEX, CHEST).replace("[{\"name\":\"Offers\"}]", "[{\"name\":\"Offers\",\"trade\":{\"0\":{\"id\":\"t\",\"type\":\"trade\",\"item\":{\"id\":\"minecraft:diamond\"},"
                + "\"needs\":[{\"item\":{\"id\":\"minecraft:emerald\"}}],\"gives\":[{\"item\":{\"id\":\"minecraft:diamond\"}}],\"commands\":[{\"command\":\"op {player}\"}]}}}]");
        assertEquals("$.pages[0].trade.0.commands: a container shop trades items for items; remove the commands", refused(ALEX, PATH, commands));
        String money = shop(ALEX, CHEST).replace("[{\"name\":\"Offers\"}]", "[{\"name\":\"Offers\",\"trade\":{\"0\":{\"id\":\"t\",\"type\":\"trade\",\"item\":{\"id\":\"minecraft:diamond\"},"
                + "\"price\":5,\"needs\":[{\"item\":{\"id\":\"minecraft:emerald\"}}],\"gives\":[{\"item\":{\"id\":\"minecraft:diamond\"}}]}}}]");
        assertEquals("$.pages[0].trade.0.price: a container shop trades items for items; remove the price", refused(ALEX, PATH, money));
        String buying = shop(ALEX, CHEST).replace("[{\"name\":\"Offers\"}]", "[{\"name\":\"Offers\",\"buy\":{\"0\":{\"id\":\"b\",\"type\":\"buy\",\"item\":{\"id\":\"minecraft:diamond\"},\"price\":0}}}]");
        assertEquals("$.pages[0].buy.0.type: a container shop has only trade and static entries", refused(ALEX, PATH, buying));
    }

    @Test void otherDocumentsStayAnAdminsAsBefore() {
        assertEquals("Only an admin can change guis/town/menu.json", refused(ALEX, "guis/town/menu.json", "{}"));
    }
}
