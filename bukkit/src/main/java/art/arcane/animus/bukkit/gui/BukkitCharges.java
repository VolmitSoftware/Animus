package art.arcane.animus.bukkit.gui;

import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.Cost;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.CostItem;
import art.arcane.animus.core.extension.CapabilityRefusal;
import art.arcane.animus.core.inventory.Economy;
import art.arcane.animus.core.inventory.InventoryTransaction;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.api.Outcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The {@code animus:charge} cost on a Bukkit player, on the player's owner thread. Items come from the hotbar and the
 * three storage rows, levels are whole experience levels and money goes through the shop economy. Money is taken
 * last, so a refused withdrawal puts the items back and leaves the levels alone.
 */
public final class BukkitCharges {
    private BukkitCharges() {}

    public static boolean affordable(Player player, Cost cost, Economy economy) {
        if (player.getLevel() < cost.levels()) return false;
        InventoryTransaction<ItemStack> items = InventoryTransaction.begin(BukkitItems.storage(player), BukkitItems.INSTANCE);
        try { if (!take(items, cost)) return false; }
        finally { items.rollback(); }
        return cost.money().signum() == 0 || economy.unavailable().isEmpty() && economy.has(player.getUniqueId(), cost.money());
    }

    public static boolean charge(Player player, Cost cost, Economy economy) {
        if (player.getLevel() < cost.levels()) return false;
        if (cost.money().signum() > 0 && economy.unavailable().isPresent()) return false;
        InventoryTransaction<ItemStack> items = InventoryTransaction.begin(BukkitItems.storage(player), BukkitItems.INSTANCE);
        if (!take(items, cost)) { items.rollback(); return false; }
        if (cost.money().signum() > 0 && !(economy.has(player.getUniqueId(), cost.money()) && economy.withdraw(player.getUniqueId(), cost.money()))) {
            items.rollback();
            return false;
        }
        items.commit();
        if (cost.levels() > 0) player.setLevel(player.getLevel() - cost.levels());
        return true;
    }

    private static boolean take(InventoryTransaction<ItemStack> items, Cost cost) {
        for (CostItem wanted : cost.items())
            if (!items.remove(stack(wanted), wanted.amount(), wanted.match())) return false;
        return true;
    }

    private static ItemStack stack(CostItem wanted) {
        List<String> errors = new ArrayList<>();
        Optional<ItemStack> stack = BukkitItems.stack(new NpcItem(wanted.item(), 1, wanted.components(), Optional.empty()), Optional.empty(), List.of(), errors);
        if (stack.isEmpty()) throw new CapabilityRefusal(Outcome.Status.INVALID, "unknown-item", "Not an item on this server: " + wanted.item() + " " + String.join("; ", errors));
        return stack.get();
    }
}
