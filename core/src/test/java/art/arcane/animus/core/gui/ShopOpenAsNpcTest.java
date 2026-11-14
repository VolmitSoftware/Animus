package art.arcane.animus.core.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.shop.ShopCatalog;
import art.arcane.animus.core.shop.ShopService;
import art.arcane.animus.core.text.RichText;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code /animus shop open <shop> npc=<id>} shows the window that NPC's click gives, wherever the NPC is. */
class ShopOpenAsNpcTest {
    static final String BAKERY = "{\"schemaVersion\":1,\"revision\":1,\"id\":\"town:bakery\",\"title\":\"Bakery\",\"style\":\"chest\",\"pages\":[{\"name\":\"Bread\",\"buy\":{"
            + "\"0\":{\"id\":\"bread\",\"type\":\"buy\",\"item\":{\"id\":\"minecraft:bread\"},\"price\":2}}}]}";
    @TempDir Path directory;
    private AnimusRuntime runtime;
    private final MockScreenHost host = new MockScreenHost();
    private final Player alex = new Player("Alex");
    private final List<String> lines = new ArrayList<>();
    private ShopService<Player, Stack> shops;

    @BeforeEach void start() throws IOException {
        runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.2", directory));
        runtime.start();
        shops = new ShopService<>(runtime, host, new FakeNpcRuntime<Player>((UUID id) -> id.equals(alex.id) ? Optional.of(alex) : Optional.empty()), Runnable::run,
                (String name) -> Optional.empty(), (String line) -> {});
        write("shops/town/bakery.json", BAKERY);
        write("shops/town/smithy.json", BAKERY.replace("town:bakery", "town:smithy").replace("Bakery", "Smithy"));
        write("npcs/town/baker.json", npc("baker", "8f0c2c52-6f0e-4f43-a3a3-9d1f8f8a0c21", "minecraft:villager",
                "{\"shop\":\"town:bakery\",\"style\":\"native\",\"villager\":{\"profession\":\"minecraft:farmer\",\"level\":3}}"));
        write("npcs/town/guard.json", npc("guard", "8f0c2c52-6f0e-4f43-a3a3-9d1f8f8a0c22", "minecraft:player", "{\"shop\":\"town:bakery\"}"));
        runtime.npcs().reload();
        shops.reload((ShopCatalog ignored) -> {});
    }

    @AfterEach void stop() { shops.close(); runtime.closeAsync(); }

    static String npc(String id, String uuid, String type, String shop) {
        return "{\"schemaVersion\":2,\"revision\":1,\"uuid\":\"" + uuid + "\",\"id\":\"" + id + "\",\"group\":\"town\",\"name\":\"" + id + "\",\"type\":\"" + type + "\","
                + "\"spawned\":true,\"position\":{\"world\":\"world\",\"x\":500,\"y\":64,\"z\":500,\"yaw\":0,\"pitch\":0},\"modules\":{\"animus:shop\":" + shop + "}}";
    }

    private void write(String path, String json) throws IOException {
        Path file = directory.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    private final CommandActor actor = new CommandActor() {
        @Override public boolean permitted(String node) { return true; }
        @Override public void tell(String message) { lines.add(RichText.plain(message)); }
        @Override public Optional<UUID> player() { return Optional.of(alex.id); }
        @Override public boolean reply(Runnable action) { action.run(); return true; }
    };

    @Test void theNpcsStyleAndVillagerLookWinOverTheShopFile() {
        shops.open(actor, "town:bakery", Optional.empty(), Optional.empty(), Optional.of("baker"));
        assertTrue(alex.frame.merchant().isPresent(), "the baker opens villager trades although the file says chest: " + alex.frame);
        assertEquals("Bakery - Journeyman", RichText.plain(alex.frame.title()), "the villager level shows as the baker's click shows it");
    }

    @Test void anNpcWithoutItsOwnStyleGetsTheShopsStyle() {
        shops.open(actor, "town:bakery", Optional.empty(), Optional.empty(), Optional.of("guard"));
        assertTrue(alex.frame.merchant().isEmpty(), "the guard sets no style, so the file's chest window opens");
        assertEquals("Bakery", RichText.plain(alex.frame.title()));
    }

    @Test void styleStillWinsAndWithoutAnNpcTheFileDecides() {
        shops.open(actor, "town:bakery", Optional.empty(), Optional.of("chest"), Optional.of("baker"));
        assertTrue(alex.frame.merchant().isEmpty(), "style= replaces the NPC's style");
        shops.open(actor, "town:bakery", Optional.empty(), Optional.empty(), Optional.empty());
        assertTrue(alex.frame.merchant().isEmpty(), "no NPC: the shop file's chest window");
    }

    @Test void anUnknownNpcOrOneThatOpensAnotherShopOpensNothing() {
        shops.open(actor, "town:smithy", Optional.empty(), Optional.empty(), Optional.of("baker"));
        assertEquals("[Animus]: baker does not open town:smithy", lines.getLast());
        shops.open(actor, "town:bakery", Optional.empty(), Optional.empty(), Optional.of("ghost"));
        assertEquals("[Animus]: Unknown NPC ghost", lines.getLast());
        assertNull(alex.frame, "nothing opened");
    }
}
