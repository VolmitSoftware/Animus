package art.arcane.animus.mod;

import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.TextPrompts;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Text prompts on mod servers: an anvil whose left slot holds a paper named with the starting text. The player edits
 * the name and clicks the result. Closing the anvil, typing {@value TextPrompts#CANCEL}, leaving or dying cancels;
 * when the anvil cannot open, the next chat line answers instead and is not broadcast. No item ever moves in a
 * prompt anvil and it is emptied before it closes. Everything here runs on the server thread.
 */
final class ModPrompts {
    private final ModTicks ticks;
    private final TextPrompts prompts = new TextPrompts();

    ModPrompts(ModTicks ticks) { this.ticks = Objects.requireNonNull(ticks); }

    boolean open(ServerPlayer player, ScreenHost.Prompt prompt) {
        PromptMenu[] made = new PromptMenu[1];
        OptionalInt id = player.openMenu(new SimpleMenuProvider((int container, Inventory inventory, Player owner) -> {
            made[0] = new PromptMenu(container, inventory, prompt, player.getUUID());
            return made[0];
        }, ModText.component(prompt.title())));
        PromptMenu menu = made[0];
        if (id.isEmpty() || menu == null || player.containerMenu != menu) {
            prompts.start(player.getUUID(), new TextPrompts.Waiting(prompt, TextPrompts.Input.CHAT, null, owner(player, null)));
            for (String line : TextPrompts.chat(prompt)) player.sendSystemMessage(ModText.component(line));
            return true;
        }
        prompts.start(player.getUUID(), new TextPrompts.Waiting(prompt, TextPrompts.Input.ANVIL, menu, owner(player, menu)));
        menu.getSlot(AnvilMenu.INPUT_SLOT).set(paper(prompt.initial()));
        menu.showResult();
        menu.broadcastChanges();
        return true;
    }

    /** Answers run on the next tick, after the anvil, if still open, is closed. */
    private Consumer<Runnable> owner(ServerPlayer player, PromptMenu menu) {
        return (Runnable answer) -> ticks.later(() -> {
            if (menu != null && player.containerMenu == menu) player.closeContainer();
            answer.run();
        });
    }

    /** A chat line on the server thread; true when it answered a prompt and must not be broadcast. */
    boolean chatted(ServerPlayer player, String line) {
        Optional<TextPrompts.Waiting> waiting = prompts.waiting(player.getUUID());
        return waiting.isPresent() && waiting.get().input() == TextPrompts.Input.CHAT && prompts.typed(player.getUUID(), line);
    }

    /** Left or died. */
    void forget(ServerPlayer player) { prompts.cancel(player.getUUID()); }

    /** Server shutdown: closes every prompt anvil without answering. */
    void closeAll(Function<UUID, ServerPlayer> online) {
        for (UUID id : prompts.clear()) {
            ServerPlayer player = online.apply(id);
            if (player != null && player.containerMenu instanceof PromptMenu) player.closeContainer();
        }
    }

    /**
     * A paper named {@code name}, upright and read as typed; a blank name becomes one space so the anvil's text field
     * starts empty. Without its enchantments component the client, which prices each rename itself as the player
     * types, never shows a cost.
     */
    static ItemStack paper(String name) {
        ItemStack paper = new ItemStack(Items.PAPER);
        paper.remove(DataComponents.ENCHANTMENTS);
        paper.set(DataComponents.CUSTOM_NAME, Component.literal(name.isBlank() ? " " : name).withStyle((Style style) -> style.withItalic(false)));
        return paper;
    }

    /** The result paper: the typed name, then "Click to confirm" and the prompt's hint as upright lore. */
    static ItemStack result(String name, ScreenHost.Prompt prompt) {
        ItemStack paper = paper(name);
        ModScreens.dress(paper, Optional.empty(), TextPrompts.lore(prompt));
        return paper;
    }

    /**
     * An anvil that only edits a name. The result is always a paper with the typed name, a click on it answers, and
     * every other click is refused and the window resent, as {@link AnimusMenu} does.
     */
    private final class PromptMenu extends AnvilMenu {
        private final UUID player;
        private final ScreenHost.Prompt prompt;
        private String typed;

        PromptMenu(int id, Inventory inventory, ScreenHost.Prompt prompt, UUID player) {
            super(id, inventory, ContainerLevelAccess.NULL);
            this.prompt = prompt;
            this.typed = prompt.initial();
            this.player = player;
        }

        /** Vanilla works out its own result first; the result is then always a paper with the typed name. */
        @Override public boolean setItemName(String name) {
            typed = name;
            boolean accepted = super.setItemName(name);
            showResult();
            return accepted;
        }

        /** {@code createResult} is final on some loaders, so the result is set after vanilla's. */
        void showResult() {
            if (!getSlot(INPUT_SLOT).getItem().isEmpty()) resultSlots.setItem(0, result(typed, prompt));
        }

        @Override public void clicked(int slot, int button, ContainerInput input, Player who) {
            sendAllDataToRemote();
            if (slot == RESULT_SLOT && (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE) && prompts.owns(player, this)) prompts.typed(player, typed);
        }

        @Override public ItemStack quickMoveStack(Player who, int slot) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player who) { return true; }
        @Override public boolean canDragTo(Slot slot) { return false; }
        @Override public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) { return false; }

        @Override public void removed(Player who) {
            inputSlots.clearContent();
            resultSlots.clearContent();
            super.removed(who);
            prompts.closed(player, this);
        }
    }
}
