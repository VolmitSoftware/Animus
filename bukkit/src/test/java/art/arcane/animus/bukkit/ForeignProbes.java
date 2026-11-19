package art.arcane.animus.bukkit;

import art.arcane.volmlib.util.event.ProtectionProbe;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Break and place probes as another plugin's own VolmLib copy marks them: standard events carrying VolmLib's probe name. */
public final class ForeignProbes {
    private static final String MARK = ProtectionProbe.entityInteract(Stubs.stub(Player.class, (String method, Object[] arguments) -> null),
            Stubs.stub(Entity.class, (String method, Object[] arguments) -> null)).getEventName();

    private ForeignProbes() {}

    public static BlockBreakEvent breaking(Player player, Block block) {
        return new BlockBreakEvent(block, player) { @Override public String getEventName() { return MARK; } };
    }

    public static BlockPlaceEvent placing(Player player, Block block, ItemStack item) {
        return new BlockPlaceEvent(block, block.getState(), block, item, player, true, EquipmentSlot.HAND) { @Override public String getEventName() { return MARK; } };
    }
}
