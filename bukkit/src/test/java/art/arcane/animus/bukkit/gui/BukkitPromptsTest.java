package art.arcane.animus.bukkit.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.gui.ScreenHost;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;
import org.junit.jupiter.api.Test;

/**
 * Bukkit prompts without a server: who is asked in chat, what is consumed, every cancel path, and every click and drag
 * in a prompt anvil, which never moves an item, including in the tick between the answer and the anvil closing.
 */
class BukkitPromptsTest {
    final UUID id = UUID.randomUUID();
    final List<String> told = new ArrayList<>();
    final List<Runnable> owner = new ArrayList<>();
    final List<Optional<String>> answers = new ArrayList<>();
    final List<String> cleared = new ArrayList<>();
    boolean showing = true;
    int serverCloses;

    final Inventory anvil = inventory("anvil"), own = inventory("own"), crafting = inventory("crafting");
    final Player player = proxy(Player.class, (Object proxy, Method method, Object[] arguments) -> switch (method.getName()) {
        case "getUniqueId" -> id;
        case "getName" -> "Alex";
        case "sendMessage" -> { told.add(String.valueOf(arguments[0])); yield null; }
        case "getOpenInventory" -> showing ? view() : craftingView();
        case "closeInventory" -> { showing = false; serverCloses++; yield null; }
        case "hashCode" -> 1;
        case "equals" -> proxy == arguments[0];
        default -> throw new UnsupportedOperationException(method.getName());
    });
    final AnvilView view = proxy(AnvilView.class, (Object proxy, Method method, Object[] arguments) -> switch (method.getName()) {
        case "getTopInventory" -> anvil;
        case "getBottomInventory" -> own;
        case "getPlayer" -> player;
        case "convertSlot" -> arguments[0];
        case "getRenameText" -> "jeb_";
        case "getType" -> InventoryType.ANVIL;
        case "hashCode" -> 2;
        case "equals" -> proxy == arguments[0];
        default -> throw new UnsupportedOperationException(method.getName());
    });
    final BukkitPrompts prompts = new BukkitPrompts((Player who, Runnable work) -> owner.add(work));

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(BukkitPromptsTest.class.getClassLoader(), new Class<?>[]{type}, handler);
    }

    Inventory inventory(String name) {
        return proxy(Inventory.class, (Object proxy, Method method, Object[] arguments) -> switch (method.getName()) {
            case "clear" -> { if (arguments == null) cleared.add(name); yield null; }
            case "getSize" -> 3;
            case "hashCode" -> name.hashCode();
            case "equals" -> proxy == arguments[0];
            case "toString" -> name;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    AnvilView view() { return view; }

    InventoryView craftingView() {
        return proxy(InventoryView.class, (Object proxy, Method method, Object[] arguments) -> switch (method.getName()) {
            case "getTopInventory" -> crafting;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    void run() { while (!owner.isEmpty()) owner.removeFirst().run(); }

    void ask() { prompts.ask(player, new ScreenHost.Prompt("Player name", "Notch", answers::add)); }

    void watch() { prompts.watch(player, new ScreenHost.Prompt("Name", "old", answers::add), anvil); }

    InventoryClickEvent click(int slot, ClickType type) {
        return new InventoryClickEvent(view, slot < 3 ? InventoryType.SlotType.CRAFTING : InventoryType.SlotType.CONTAINER, slot, type, InventoryAction.UNKNOWN);
    }

    /** Every click type on the input, the empty middle slot and the player's own rows is refused. */
    void refusesEveryClickAwayFromTheResult(String when) {
        for (ClickType type : ClickType.values()) for (int slot : new int[]{BukkitPrompts.INPUT, 1, 5, 38}) {
            InventoryClickEvent event = click(slot, type);
            assertTrue(prompts.click(event), when + ": " + type + " on " + slot + " is the prompt's");
            assertTrue(event.isCancelled(), when + ": " + type + " on " + slot + " moves nothing");
        }
        InventoryDragEvent drag = new InventoryDragEvent(view, null, new ItemStack(Material.PAPER), false, Map.of(BukkitPrompts.INPUT, new ItemStack(Material.PAPER)));
        assertTrue(prompts.drag(drag));
        assertTrue(drag.isCancelled(), when + ": a drag moves nothing");
    }

    @Test void theChatFallbackTellsThePlayerWhatToTypeAndConsumesOnlyTheAnswer() {
        ask();
        assertEquals("Type Player name (now Notch) in chat, or type cancel", told.getLast().replaceAll("§.", ""));
        assertTrue(prompts.chatted(player, " jeb_ "), "the answer is not broadcast");
        assertTrue(answers.isEmpty(), "the answer waits for the owner");
        run();
        assertEquals(List.of(Optional.of("jeb_")), answers);
        assertFalse(prompts.chatted(player, "hello"), "later lines are ordinary chat");
    }

    @Test void typingCancelLeavingOrDyingCancels() {
        ask();
        assertTrue(prompts.chatted(player, "Cancel"));
        ask();
        prompts.forget(player);
        run();
        assertEquals(List.of(Optional.<String>empty(), Optional.<String>empty()), answers);
        assertFalse(prompts.chatted(player, "late"));
    }

    @Test void onlyAClickOnTheResultAnswersAndTheAnvilStaysLockedUntilItCloses() {
        watch();
        refusesEveryClickAwayFromTheResult("waiting");
        assertTrue(answers.isEmpty(), "only the result answers");
        InventoryClickEvent confirm = click(BukkitPrompts.RESULT, ClickType.LEFT);
        assertTrue(prompts.click(confirm));
        assertTrue(confirm.isCancelled(), "the result paper is never taken");
        refusesEveryClickAwayFromTheResult("answered, not yet closed");
        run();
        assertEquals(List.of(Optional.of("jeb_")), answers);
        assertEquals(List.of("anvil"), cleared, "the paper is gone before the anvil closes");
        assertEquals(1, serverCloses);
        assertFalse(prompts.click(click(BukkitPrompts.INPUT, ClickType.LEFT)), "a later window is not the prompt's");
    }

    @Test void onTheResultOnlyTheClicksAMenuMapsAnswerAsOnModServers() {
        watch();
        for (ClickType type : List.of(ClickType.NUMBER_KEY, ClickType.MIDDLE, ClickType.DROP, ClickType.CONTROL_DROP, ClickType.DOUBLE_CLICK, ClickType.SWAP_OFFHAND)) {
            InventoryClickEvent event = click(BukkitPrompts.RESULT, type);
            assertTrue(prompts.click(event), type.name());
            assertTrue(event.isCancelled(), type + " moves nothing");
        }
        run();
        assertTrue(answers.isEmpty(), "a number key, Q or a middle click does not answer: " + answers);
        prompts.click(click(BukkitPrompts.RESULT, ClickType.SHIFT_RIGHT));
        run();
        assertEquals(List.of(Optional.of("jeb_")), answers);
    }

    @Test void escapeEmptiesTheAnvilFirstAndCancelsOnce() {
        watch();
        showing = false;
        assertTrue(prompts.closed(new InventoryCloseEvent(view)));
        assertEquals(List.of("anvil"), cleared, "emptied before the server hands its items back");
        run();
        assertEquals(List.of(Optional.<String>empty()), answers);
        assertEquals(0, serverCloses);
        assertFalse(prompts.closed(new InventoryCloseEvent(view)));
    }

    @Test void escapeInTheTickAfterAnAnswerStillEmptiesTheAnvil() {
        watch();
        prompts.click(click(BukkitPrompts.RESULT, ClickType.LEFT));
        showing = false;
        assertTrue(prompts.closed(new InventoryCloseEvent(view)));
        run();
        assertEquals(List.of(Optional.of("jeb_")), answers, "the answer stands");
        assertEquals(List.of("anvil", "anvil"), cleared);
        assertEquals(0, serverCloses);
    }
}
