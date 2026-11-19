package art.arcane.animus.bukkit;

import static art.arcane.animus.bukkit.Stubs.stub;
import static org.junit.jupiter.api.Assertions.*;

import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.SnapshotFacts;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.catalog.ServerBehaviorList;
import art.arcane.animus.core.behavior.trigger.ServerTriggerDispatcher;
import art.arcane.animus.core.behavior.trigger.ServerTriggerSettings;
import art.arcane.volmlib.util.event.ProtectionProbe;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BukkitServerBehaviorsTest {
    @TempDir Path root;

    @Test void eventTextLosesColourCodesAndBlankTextIsAbsent() {
        assertEquals(Optional.of("Alex fell from a high place"), BukkitServerBehaviors.plain("§eAlex §rfell from a high place"));
        assertEquals(Optional.of("hello"), BukkitServerBehaviors.plain("hello"));
        assertEquals(Optional.empty(), BukkitServerBehaviors.plain(null));
        assertEquals(Optional.empty(), BukkitServerBehaviors.plain("§c "));
    }

    @Test void protectionProbesStartNoBlockTrigger() {
        ServerTriggerDispatcher dispatcher = blockTriggers();
        Server server = stub(Server.class, (String method, Object[] arguments) -> switch (method) {
            case "getLogger" -> Logger.getLogger("BukkitServerBehaviorsTest");
            case "getWorlds" -> List.of();
            default -> null;
        });
        @SuppressWarnings("deprecation")
        JavaPlugin plugin = new JavaPlugin(new JavaPluginLoader(server), new PluginDescriptionFile("ProbeTest", "1", "probe.Main"), root.toFile(), root.resolve("probe.jar").toFile()) {};
        BukkitServerBehaviors behaviors = new BukkitServerBehaviors(plugin, new BukkitOwners(plugin, null), dispatcher, new SnapshotFacts((UUID npc) -> Optional.empty()), (String line) -> {});
        World world = stub(World.class, (String method, Object[] arguments) -> method.equals("getName") ? "world" : null);
        BlockState air = stub(BlockState.class, (String method, Object[] arguments) -> null);
        Block stone = stub(Block.class, (String method, Object[] arguments) -> switch (method) {
            case "getWorld" -> world;
            case "getType" -> Material.STONE;
            case "getState" -> air;
            default -> null;
        });
        ItemStack held = new ItemStack(Material.STONE);
        PlayerInventory inventory = stub(PlayerInventory.class, (String method, Object[] arguments) -> method.equals("getItemInMainHand") ? held : null);
        UUID id = UUID.randomUUID();
        Player alex = stub(Player.class, (String method, Object[] arguments) -> switch (method) {
            case "getUniqueId" -> id;
            case "getInventory" -> inventory;
            default -> null;
        });

        EventChain.fire(ProtectionProbe.blockInteract(alex, stone, EquipmentSlot.HAND), behaviors);
        EventChain.fire(ForeignProbes.breaking(alex, stone), behaviors);
        EventChain.fire(ForeignProbes.placing(alex, stone, held), behaviors);
        assertEquals(0, dispatcher.snapshot().events(), "no probe reaches a trigger");

        EventChain.fire(new PlayerInteractEvent(alex, Action.RIGHT_CLICK_BLOCK, held, stone, BlockFace.UP, EquipmentSlot.HAND), behaviors);
        EventChain.fire(new BlockBreakEvent(stone, alex), behaviors);
        EventChain.fire(new BlockPlaceEvent(stone, air, stone, held, alex, true, EquipmentSlot.HAND), behaviors);
        assertEquals(3, dispatcher.snapshot().events(), "the player's own click, break and place each do");
    }

    /** One enabled server behavior listening for block clicks, breaks and places. */
    private static ServerTriggerDispatcher blockTriggers() {
        String source = "{\"schemaVersion\":1,\"id\":\"denizen:world/blocks\",\"server\":true,\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[]}},"
                + "\"triggers\":[{\"id\":\"click\",\"event\":\"player_block_click\"},{\"id\":\"break\",\"event\":\"player_block_break\"},{\"id\":\"place\",\"event\":\"player_block_place\"}]}";
        BehaviorCompiler.Result compiled = BehaviorCompiler.compile(source);
        BehaviorProgram program = compiled.program().orElseThrow(() -> new AssertionError(compiled.diagnostics().toString()));
        BehaviorCatalog.Resolution resolution = new BehaviorCatalog.Resolution("denizen:world/blocks", true,
                Optional.of(new BehaviorCatalog.Accepted(1, String.format("%064x", 1), 100, program, Set.of())), List.of());
        BehaviorCatalog.Snapshot catalog = new BehaviorCatalog.Snapshot(1, Map.of(), Map.of("denizen:world/blocks", resolution), List.of());
        ServerBehaviorList enabled = new ServerBehaviorList(1, List.of("denizen:world/blocks"), List.of());
        ServerTriggerDispatcher dispatcher = new ServerTriggerDispatcher(ServerTriggerSettings.DEFAULT, () -> catalog, () -> enabled);
        dispatcher.connect();
        dispatcher.pump((ServerTriggerDispatcher.Request request) -> Outcome.ok(UUID.randomUUID()));
        return dispatcher;
    }
}
