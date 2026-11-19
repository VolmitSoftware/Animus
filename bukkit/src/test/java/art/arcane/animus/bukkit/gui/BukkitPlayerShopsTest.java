package art.arcane.animus.bukkit.gui;

import static art.arcane.animus.bukkit.Stubs.stub;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.bukkit.EventChain;
import art.arcane.animus.bukkit.ForeignProbes;
import art.arcane.animus.bukkit.toolkit.BukkitWand;
import art.arcane.animus.core.config.OwnerSettings;
import art.arcane.animus.core.config.ShopSettings;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.shop.PlayerShops;
import art.arcane.animus.core.shop.ShopCatalog;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.skin.SkinSigner;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.toolkit.Toolkit;
import art.arcane.volmlib.util.event.ProtectionProbe;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Sam's stall keeps its stock in the chests at 1 64 2 and 2 64 2: what other plugins' protection probes get there, and when a broken chest leaves it. */
class BukkitPlayerShopsTest {
    private static final UUID SAM = UUID.fromString("5a000000-0000-0000-0000-000000000001"), ALEX = UUID.fromString("a1000000-0000-0000-0000-000000000002");
    private static final String STALL = "{\"schemaVersion\":1,\"revision\":1,\"id\":\"players:sams-stall\",\"title\":\"Sam's stall\",\"kind\":\"selling\","
            + "\"owner\":{\"uuid\":\"" + SAM + "\",\"name\":\"Sam\"},\"containers\":[{\"world\":\"world\",\"x\":1,\"y\":64,\"z\":2},{\"world\":\"world\",\"x\":2,\"y\":64,\"z\":2}],"
            + "\"pages\":[{\"name\":\"Offers\",\"trade\":{\"0\":{\"id\":\"t\",\"type\":\"trade\",\"item\":{\"id\":\"minecraft:diamond\"},"
            + "\"needs\":[{\"item\":{\"id\":\"minecraft:emerald\"},\"amount\":5}],\"gives\":[{\"item\":{\"id\":\"minecraft:diamond\"}}]}}}]}";
    @TempDir Path root;
    private DocumentStore store;
    private final List<Runnable> fileWork = new ArrayList<>();
    private final List<String> told = new ArrayList<>();
    private final World world = stub(World.class, (String method, Object[] arguments) -> switch (method) {
        case "getName" -> "world";
        case "getKey" -> NamespacedKey.minecraft("overworld");
        default -> null;
    });
    private Toolkit<Player, ItemStack> toolkit;
    private BukkitPlayerShops listener;

    /** Cancels every break, as a protection plugin listening at HIGHEST does. */
    public static final class Refusing implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST) public void refuse(BlockBreakEvent event) { event.setCancelled(true); }
    }

    @SuppressWarnings("unchecked")
    @BeforeEach void start() throws IOException {
        store = new DocumentStore(root);
        PlayerShops shops = new PlayerShops(() -> store, fileWork::add, () -> ShopSettings.DEFAULT, () -> {}, (UUID player, String line) -> {}, (String line) -> {});
        ShopDocument stall = ShopDocument.parse("players:sams-stall", STALL).shop().orElseThrow();
        shops.index(new ShopCatalog(Map.of(), Map.of(stall.id(), stall), List.of()));
        ScreenManager<Player, ItemStack> screens = new ScreenManager<>(stub(ScreenHost.class, (String method, Object[] arguments) -> null), (UUID npc) -> true, (String warning) -> {});
        NpcRuntime<Player, ?> living = stub(NpcRuntime.class, (String method, Object[] arguments) -> null);
        toolkit = Toolkit.install(new Toolkit.Parts<>(() -> null, () -> store, screens, living, Runnable::run, (Player player) -> Optional.empty(), Optional.empty(),
                System::nanoTime, () -> SkinSigner.off("Skin signing is off in this test"), () -> OwnerSettings.DEFAULT));
        listener = new BukkitPlayerShops(shops, toolkit);
    }

    @AfterEach void stop() throws IOException { toolkit.close(); store.close(); }

    @Test void aProbeAtAContainerNeitherStartsAShopNorAnswersForProtection() {
        PlayerInteractEvent probe = ProtectionProbe.blockInteract(player(SAM), block(5, 64, 5, Material.CHEST), EquipmentSlot.HAND);
        EventChain.fire(probe, listener);
        assertNotEquals(Event.Result.DENY, probe.useInteractedBlock(), "Animus is no protection plugin for a container it does not keep");
        assertEquals(List.of(), told);
    }

    @Test void aSneakingClickWithTheShopItemStillClaimsTheContainer() {
        Player sam = player(SAM);
        PlayerInteractEvent click = new PlayerInteractEvent(sam, Action.RIGHT_CLICK_BLOCK, sam.getInventory().getItemInMainHand(), block(5, 64, 5, Material.CHEST), BlockFace.UP, EquipmentSlot.HAND);
        EventChain.fire(click, listener);
        assertEquals(Event.Result.DENY, click.useInteractedBlock());
        assertEquals(List.of("[Animus]: Leave a free space beside the chest for the shop's NPC"), told, "the walled-in chest is refused, so the click reached New shop");
    }

    @Test void theOwnersBreakTakesTheChestOutOfItsShop() {
        BlockBreakEvent broken = EventChain.fire(new BlockBreakEvent(block(1, 64, 2, Material.CHEST), player(SAM)), listener);
        assertFalse(broken.isCancelled());
        assertEquals(1, fileWork.size(), "the shop file is updated on the file lane");
    }

    @Test void aBreakAnotherPluginCancelsKeepsTheChestInItsShop() {
        BlockBreakEvent broken = EventChain.fire(new BlockBreakEvent(block(1, 64, 2, Material.CHEST), player(SAM)), listener, new Refusing());
        assertTrue(broken.isCancelled());
        assertEquals(List.of(), fileWork);
    }

    @Test void theOwnersProbeBreakNeverTakesTheChest() {
        BlockBreakEvent probe = EventChain.fire(ForeignProbes.breaking(player(SAM), block(1, 64, 2, Material.CHEST)), listener);
        assertFalse(probe.isCancelled());
        assertEquals(List.of(), fileWork);
    }

    @Test void aStrangersProbeBreakIsRefusedWithoutAWord() {
        BlockBreakEvent probe = EventChain.fire(ForeignProbes.breaking(player(ALEX), block(2, 64, 2, Material.CHEST)), listener);
        assertTrue(probe.isCancelled(), "the chest stays Sam's");
        assertEquals(List.of(), told);
        assertEquals(List.of(), fileWork);
    }

    @Test void aStrangersProbePlaceBesideTheStallIsRefusedWithoutAWord() {
        Player alex = player(ALEX);
        BlockPlaceEvent probe = EventChain.fire(ForeignProbes.placing(alex, block(1, 64, 3, Material.CHEST), alex.getInventory().getItemInMainHand()), listener);
        assertTrue(probe.isCancelled());
        assertEquals(List.of(), told);
        BlockPlaceEvent placed = EventChain.fire(new BlockPlaceEvent(block(1, 64, 3, Material.CHEST), block(1, 64, 3, Material.CHEST).getState(), block(1, 64, 3, Material.CHEST),
                alex.getInventory().getItemInMainHand(), alex, true, EquipmentSlot.HAND), listener);
        assertTrue(placed.isCancelled());
        assertEquals(List.of("[Animus]: A chest here would join Sam's shop"), told);
    }

    /** Sneaking at 5.5 64 7.5 with the shop item in hand, and no shop permissions. */
    private Player player(UUID id) {
        ItemStack shopItem = shopItem();
        PlayerInventory inventory = stub(PlayerInventory.class, (String method, Object[] arguments) -> method.equals("getItemInMainHand") ? shopItem : null);
        Player.Spigot chat = new Player.Spigot() {
            @Override public void sendMessage(BaseComponent... components) { told.add(TextComponent.toPlainText(components)); }
        };
        return stub(Player.class, (String method, Object[] arguments) -> switch (method) {
            case "getUniqueId" -> id;
            case "getName" -> id.equals(SAM) ? "Sam" : "Alex";
            case "isSneaking" -> true;
            case "getInventory" -> inventory;
            case "getLocation" -> new Location(world, 5.5, 64, 7.5);
            case "spigot" -> chat;
            default -> null;
        });
    }

    /** A container of {@code type}, boxed in by solid blocks and with chests of the same type all around. */
    private Block block(int x, int y, int z, Material type) {
        Container state = stub(Container.class, (String method, Object[] arguments) -> method.equals("getType") ? type : null);
        return stub(Block.class, (String method, Object[] arguments) -> switch (method) {
            case "getWorld" -> world;
            case "getX" -> x;
            case "getY" -> y;
            case "getZ" -> z;
            case "getType" -> type;
            case "getState" -> state;
            case "getRelative" -> {
                BlockFace face = (BlockFace) arguments[0];
                yield block(x + face.getModX(), y + face.getModY(), z + face.getModZ(), type);
            }
            default -> null;
        });
    }

    private static ItemStack shopItem() {
        PersistentDataContainer data = stub(PersistentDataContainer.class, (String method, Object[] arguments) -> method.equals("get") && BukkitWand.KEY.equals(arguments[0]) ? Toolkit.SHOP_ITEM : null);
        ItemMeta meta = stub(ItemMeta.class, (String method, Object[] arguments) -> method.equals("getPersistentDataContainer") ? data : null);
        return new ItemStack(Material.PAPER) {
            @Override public boolean hasItemMeta() { return true; }
            @Override public ItemMeta getItemMeta() { return meta; }
        };
    }
}
