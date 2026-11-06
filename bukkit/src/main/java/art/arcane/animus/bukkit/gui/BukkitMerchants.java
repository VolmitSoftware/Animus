package art.arcane.animus.bukkit.gui;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.PacketKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.TradeSelectEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;

/**
 * Shops in the villager trading screen, as custom Bukkit merchants. The screen is only a picture: every click and drag
 * in it is cancelled, so no item ever enters the payment slots. Choosing an offer puts its result in the result slot;
 * clicking the result hands the chosen offer to the shop on the next tick, which runs the trade. Offers carry no
 * villager experience. New offers reopen the screen, since Bukkit cannot resend them in place.
 */
final class BukkitMerchants implements Listener {
    /** One open trading screen. */
    static final class Window {
        private final Merchant merchant;
        private final ScreenFrame frame;
        private volatile int selected = -1;
        private volatile boolean replaced;
        private Window(Merchant merchant, ScreenFrame frame) { this.merchant = merchant; this.frame = frame; }
        void replace() { replaced = true; }
    }

    private static final int RESULT = 2;
    private final Server server;
    private final BiPredicate<Player, Runnable> later;
    private final Supplier<ScreenManager<Player, ItemStack>> manager;
    private final Map<UUID, Window> open = new ConcurrentHashMap<>();

    BukkitMerchants(Server server, BiPredicate<Player, Runnable> later, Supplier<ScreenManager<Player, ItemStack>> manager) {
        this.server = server;
        this.later = later;
        this.manager = manager;
    }

    /** Shows the frame's offers, reusing the open screen when nothing changed. */
    Optional<Object> show(Player player, ScreenFrame frame, Object previous) {
        if (previous instanceof Window window && showing(player, window) && window.frame.equals(frame)) return Optional.of(window);
        if (previous instanceof Window window) window.replaced = true;
        if (previous instanceof BukkitScreens.Window chest) chest.replace();
        Merchant merchant = server.createMerchant(frame.title());
        List<MerchantRecipe> recipes = new ArrayList<>();
        for (ScreenFrame.Offer offer : frame.merchant().orElseThrow().offers()) {
            MerchantRecipe recipe = new MerchantRecipe(stack(offer.result()), offer.uses(), offer.maxUses(), false, 0, 0f);
            recipe.addIngredient(stack(offer.costA()));
            offer.costB().ifPresent((ScreenFrame.Item cost) -> recipe.addIngredient(stack(cost)));
            recipes.add(recipe);
        }
        merchant.setRecipes(recipes);
        Window next = new Window(merchant, frame);
        open.put(player.getUniqueId(), next);
        player.openMerchant(merchant, true);
        AnimusMetrics metrics = AnimusMetrics.current();
        metrics.sent(metrics.viewer(player.getUniqueId(), player.getName()), null, PacketKind.GUI_WINDOW, 1);
        if (!showing(player, next)) { open.remove(player.getUniqueId(), next); return Optional.empty(); }
        return Optional.of(next);
    }

    /** Closes the screen if it is still the one given. */
    boolean close(Player player, Object window) {
        if (!(window instanceof Window shown)) return false;
        if (showing(player, shown)) { shown.replaced = true; player.closeInventory(); }
        open.remove(player.getUniqueId(), shown);
        return true;
    }

    boolean showing(Player player) { return window(player.getOpenInventory()).isPresent(); }

    private boolean showing(Player player, Window window) {
        return player.getOpenInventory().getTopInventory() instanceof MerchantInventory inventory && inventory.getMerchant() == window.merchant;
    }

    private Optional<Window> window(InventoryView view) {
        if (!(view.getTopInventory() instanceof MerchantInventory inventory) || !(view.getPlayer() instanceof Player player)) return Optional.empty();
        Window window = open.get(player.getUniqueId());
        return window != null && inventory.getMerchant() == window.merchant ? Optional.of(window) : Optional.empty();
    }

    private ItemStack stack(ScreenFrame.Item item) {
        ItemStack stack = BukkitScreens.displayStack(server, item);
        stack.setAmount(Math.clamp(stack.getAmount(), 1, Math.max(1, stack.getMaxStackSize())));
        return stack;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void select(TradeSelectEvent event) {
        Optional<Window> window = window(event.getView());
        if (window.isEmpty()) return;
        event.setCancelled(true);
        List<ScreenFrame.Offer> offers = window.get().frame.merchant().orElseThrow().offers();
        int index = event.getIndex();
        if (index < 0 || index >= offers.size()) return;
        window.get().selected = index;
        event.getInventory().setItem(RESULT, stack(offers.get(index).result()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        Optional<Window> window = window(event.getView());
        if (window.isEmpty()) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        GuiModel.Click click = switch (event.getClick()) {
            case LEFT -> GuiModel.Click.LEFT;
            case RIGHT -> GuiModel.Click.RIGHT;
            case SHIFT_LEFT -> GuiModel.Click.SHIFT_LEFT;
            case SHIFT_RIGHT -> GuiModel.Click.SHIFT_RIGHT;
            default -> null;
        };
        Window target = window.get();
        int offer = target.selected, slot = event.getRawSlot();
        ScreenManager<Player, ItemStack> current = manager.get();
        later.test(player, () -> {
            if (!player.isOnline()) return;
            player.updateInventory();
            if (click != null && slot == RESULT && offer >= 0 && current != null) current.click(player, target, offer, click);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void drag(InventoryDragEvent event) {
        if (window(event.getView()).isPresent()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void closed(InventoryCloseEvent event) {
        Optional<Window> window = window(event.getView());
        if (window.isEmpty() || !(event.getPlayer() instanceof Player player)) return;
        open.remove(player.getUniqueId(), window.get());
        ScreenManager<Player, ItemStack> current = manager.get();
        if (!window.get().replaced && current != null) current.closed(player, window.get());
    }

    void forget(UUID player) { open.remove(player); }
}
