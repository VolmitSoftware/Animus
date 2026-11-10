package art.arcane.animus.mod;

import art.arcane.animus.core.gui.GuiModel;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponentExactPredicate;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * A shop's villager trading screen that never lets an item move. Choosing an offer puts its result in the result slot
 * instead of moving payment from the player; every click is refused and the window is sent again, and a left, right
 * or shift click on the result reports the chosen offer. Offers give no villager experience.
 */
final class AnimusMerchantMenu extends MerchantMenu {
    /** Receives a bound click on the result slot, with the chosen offer. */
    @FunctionalInterface interface Clicks { void clicked(AnimusMerchantMenu menu, int offer, GuiModel.Click click); }
    /** Receives the close of this window. */
    @FunctionalInterface interface Closes { void closed(AnimusMerchantMenu menu, Player player); }
    /** One offer: its costs, its result and its uses. */
    record Offer(ItemStack costA, ItemStack costB, ItemStack result, int uses, int maxUses) {}

    private static final int RESULT = 2;
    private final Trader trader;
    private final Clicks clicks;
    private final Closes closes;
    private List<Offer> offers;
    private int selected = -1;
    boolean replaced;

    private AnimusMerchantMenu(int id, Inventory inventory, Trader trader, List<Offer> offers, Clicks clicks, Closes closes) {
        super(id, inventory, trader);
        this.trader = trader;
        this.offers = List.copyOf(offers);
        this.clicks = clicks;
        this.closes = closes;
    }

    static AnimusMerchantMenu create(int id, Inventory inventory, List<Offer> offers, Clicks clicks, Closes closes) {
        Trader trader = new Trader();
        trader.offers = merchantOffers(offers);
        return new AnimusMerchantMenu(id, inventory, trader, offers, clicks, closes);
    }

    /** The offers the client shows, as vanilla merchant offers. */
    MerchantOffers offers() { return trader.offers; }

    /** New offers for the open window; the caller sends them. */
    void offers(List<Offer> next) {
        offers = List.copyOf(next);
        trader.offers = merchantOffers(next);
        if (selected >= offers.size()) selected = -1;
    }

    private static MerchantOffers merchantOffers(List<Offer> offers) {
        MerchantOffers shown = new MerchantOffers();
        for (Offer offer : offers) {
            Optional<ItemCost> costB = offer.costB().isEmpty() ? Optional.empty() : Optional.of(cost(offer.costB()));
            shown.add(new MerchantOffer(cost(offer.costA()), costB, offer.result().copy(), offer.uses(), offer.maxUses(), 0, 0f, 0));
        }
        return shown;
    }

    private static ItemCost cost(ItemStack stack) {
        DataComponentExactPredicate components = DataComponentExactPredicate.allOf(PatchedDataComponentMap.fromPatch(DataComponentMap.EMPTY, stack.getComponentsPatch()));
        return new ItemCost(stack.typeHolder(), stack.getCount(), components, stack.copy());
    }

    @Override public void setSelectionHint(int offer) { selected = offer >= 0 && offer < offers.size() ? offer : -1; }

    /**
     * Vanilla moves the payment in here; this screen shows the chosen result instead. It goes straight into the trade
     * container: a slot set would mark the container changed, and vanilla then recomputes the result from the empty
     * payment slots and clears it.
     */
    @Override public void tryMoveItems(int offer) {
        Slot result = getSlot(RESULT);
        result.container.setItem(result.getContainerSlot(), selected >= 0 ? offers.get(selected).result().copy() : ItemStack.EMPTY);
        sendAllDataToRemote();
    }

    @Override public void clicked(int slot, int button, ContainerInput input, Player player) {
        GuiModel.Click click = null;
        if (slot == RESULT) {
            if (input == ContainerInput.PICKUP) click = button == 0 ? GuiModel.Click.LEFT : button == 1 ? GuiModel.Click.RIGHT : null;
            else if (input == ContainerInput.QUICK_MOVE) click = button == 0 ? GuiModel.Click.SHIFT_LEFT : button == 1 ? GuiModel.Click.SHIFT_RIGHT : null;
        }
        sendAllDataToRemote();
        if (click != null && selected >= 0) clicks.clicked(this, selected, click);
    }

    @Override public void slotsChanged(net.minecraft.world.Container container) {}
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) { return true; }
    @Override public boolean canDragTo(Slot slot) { return false; }
    @Override public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) { return false; }

    @Override public void removed(Player player) {
        getSlot(RESULT).set(ItemStack.EMPTY);
        super.removed(player);
        if (!replaced) closes.closed(this, player);
    }

    /** The merchant behind the window: its offers and nothing else. */
    private static final class Trader implements Merchant {
        private MerchantOffers offers = new MerchantOffers();
        private Player trading;
        @Override public void setTradingPlayer(Player player) { trading = player; }
        @Override public Player getTradingPlayer() { return trading; }
        @Override public MerchantOffers getOffers() { return offers; }
        @Override public void overrideOffers(MerchantOffers next) { offers = next; }
        @Override public void notifyTrade(MerchantOffer offer) {}
        @Override public void notifyTradeUpdated(ItemStack stack) {}
        @Override public int getVillagerXp() { return 0; }
        @Override public void overrideXp(int experience) {}
        @Override public boolean showProgressBar() { return false; }
        @Override public SoundEvent getNotifyTradeSound() { return SoundEvents.VILLAGER_YES; }
        @Override public boolean isClientSide() { return false; }
        @Override public boolean stillValid(Player player) { return true; }
    }
}
