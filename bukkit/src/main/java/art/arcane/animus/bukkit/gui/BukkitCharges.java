package art.arcane.animus.bukkit.gui;

import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.Cost;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.CostItem;
import art.arcane.animus.core.behavior.builtin.Charges;
import art.arcane.animus.core.extension.CapabilityRefusal;
import art.arcane.animus.core.inventory.Economy;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.api.Outcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** {@link Charges} on a Bukkit player: the hotbar and three storage rows, whole experience levels and the shop economy. */
public final class BukkitCharges {
    private BukkitCharges() {}

    public static boolean affordable(Player player, Cost cost, Economy economy) { return Charges.affordable(payer(player, economy), cost); }

    public static boolean charge(Player player, Cost cost, Economy economy) { return Charges.charge(payer(player, economy), cost); }

    private static Charges.Payer<ItemStack> payer(Player player, Economy economy) {
        return new Charges.Payer<>(player.getUniqueId(), BukkitItems.storage(player), BukkitItems.INSTANCE, BukkitCharges::stack, player::getLevel,
                (int levels) -> player.setLevel(player.getLevel() - levels), economy);
    }

    private static ItemStack stack(CostItem wanted) {
        List<String> errors = new ArrayList<>();
        Optional<ItemStack> stack = BukkitItems.stack(new NpcItem(wanted.item(), 1, wanted.components(), Optional.empty()), Optional.empty(), List.of(), errors);
        if (stack.isEmpty()) throw new CapabilityRefusal(Outcome.Status.INVALID, "unknown-item", "Not an item on this server: " + wanted.item() + " " + String.join("; ", errors));
        return stack.get();
    }
}
