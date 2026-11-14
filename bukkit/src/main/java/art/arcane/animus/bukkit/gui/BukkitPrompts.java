package art.arcane.animus.bukkit.gui;

import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.TextPrompts;
import art.arcane.animus.core.text.RichText;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.plugin.Plugin;

/**
 * Text prompts on Bukkit. The player gets an anvil whose left slot holds a paper named with the starting text; they
 * edit the name and click the result. Closing the anvil, typing {@value TextPrompts#CANCEL}, leaving or dying
 * cancels. When the anvil cannot be opened the prompt falls back to the next chat line, which is never broadcast.
 * Every click and drag in a prompt anvil is cancelled, and the anvil is emptied before it closes, so the paper never
 * reaches the player. Answers run on the player's owner through {@code later}.
 */
final class BukkitPrompts implements Listener {
    static final int INPUT = 0, RESULT = 2;
    private static final String PAPER_CHAT = "io.papermc.paper.event.player.AsyncChatEvent";
    private static final String UNPRICED = "minecraft:paper[!minecraft:enchantments]";
    private final BiPredicate<Player, Runnable> later;
    private final TextPrompts prompts = new TextPrompts();
    /** Anvils whose prompt is answered but which have not closed yet, by player. */
    private final Map<UUID, Inventory> closing = new ConcurrentHashMap<>();

    BukkitPrompts(BiPredicate<Player, Runnable> later) { this.later = Objects.requireNonNull(later); }

    /** Listens for anvil results and for chat answers, through Paper's chat event when the server has it. */
    void register(Plugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        Class<? extends Event> paper = paperChat();
        if (paper != null) {
            Method signed, text;
            try {
                signed = paper.getMethod("signedMessage");
                text = Class.forName("net.kyori.adventure.chat.SignedMessage").getMethod("message");
            } catch (ReflectiveOperationException missing) { signed = null; text = null; }
            Method message = signed, plain = text;
            if (message != null) {
                plugin.getServer().getPluginManager().registerEvent(paper, this, EventPriority.LOWEST, (Listener listener, Event event) -> {
                    if (!paper.isInstance(event) || !(event instanceof PlayerEvent chat) || !(event instanceof Cancellable cancellable)) return;
                    try { if (chatted(chat.getPlayer(), String.valueOf(plain.invoke(message.invoke(event))))) cancellable.setCancelled(true); }
                    catch (ReflectiveOperationException unreadable) { /* Leave a line we cannot read to the server. */ }
                }, plugin, false);
                return;
            }
        }
        plugin.getServer().getPluginManager().registerEvent(AsyncPlayerChatEvent.class, this, EventPriority.LOWEST, (Listener listener, Event event) -> {
            if (event instanceof AsyncPlayerChatEvent chat && chatted(chat.getPlayer(), chat.getMessage())) chat.setCancelled(true);
        }, plugin, false);
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Event> paperChat() {
        try {
            Class<?> found = Class.forName(PAPER_CHAT);
            return Event.class.isAssignableFrom(found) ? (Class<? extends Event>) found : null;
        } catch (ClassNotFoundException spigot) { return null; }
    }

    /** Opens the anvil, or asks in chat when the anvil cannot be opened. Runs on the player's owner. */
    boolean open(Player player, ScreenHost.Prompt prompt) {
        if (anvil(player, prompt)) return true;
        ask(player, prompt);
        return true;
    }

    private boolean anvil(Player player, ScreenHost.Prompt prompt) {
        AnvilView view;
        try {
            view = MenuType.ANVIL.create(player, prompt.title());
            player.openInventory(view);
        } catch (RuntimeException | LinkageError unsupported) { return false; }
        Inventory top = view.getTopInventory();
        if (!top.equals(player.getOpenInventory().getTopInventory())) return false;
        watch(player, prompt, top);
        name(view, prompt.initial());
        top.setItem(INPUT, paper(prompt.initial()));
        view.setRepairCost(0);
        return true;
    }

    /** The open anvil answers this prompt: from now until it closes, every click and drag in it is refused. */
    void watch(Player player, ScreenHost.Prompt prompt, Inventory anvil) {
        prompts.start(player.getUniqueId(), new TextPrompts.Waiting(prompt, TextPrompts.Input.ANVIL, anvil, owner(player, anvil)));
    }

    /** The anvil of the player's waiting prompt, or of one answered whose anvil has not closed yet. */
    private boolean guarded(UUID player, Inventory top) { return prompts.owns(player, top) || top.equals(closing.get(player)); }

    /**
     * Gives a fresh anvil its rename text before the paper goes in. Until the client types, the server holds no name,
     * so getRenameText() is null in the PrepareAnvilEvent that placing the paper fires, and listeners in other plugins
     * that assume a name (CMI's anvil colours) throw. Servers without the menu's setItemName keep the old behaviour.
     */
    private static void name(AnvilView view, String text) {
        try {
            Object menu = view.getClass().getMethod("getHandle").invoke(view);
            menu.getClass().getMethod("setItemName", String.class).invoke(menu, text);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
            // The prompt still works; only a listener that assumes a name sees null.
        }
    }

    /** The chat fallback: the next line the player sends answers. */
    void ask(Player player, ScreenHost.Prompt prompt) {
        prompts.start(player.getUniqueId(), new TextPrompts.Waiting(prompt, TextPrompts.Input.CHAT, null, owner(player, null)));
        for (String line : TextPrompts.chat(prompt)) player.sendMessage(RichText.colorize(line));
    }

    /**
     * Answers run on the owner after the anvil, if still showing, is emptied and closed. Until then the answered anvil
     * stays guarded, so nothing is taken from it or put in it and then cleared.
     */
    private Consumer<Runnable> owner(Player player, Inventory anvil) {
        UUID id = player.getUniqueId();
        return (Runnable answer) -> {
            if (anvil != null) closing.put(id, anvil);
            later.test(player, () -> {
                if (anvil != null) {
                    anvil.clear();
                    if (anvil.equals(player.getOpenInventory().getTopInventory())) player.closeInventory();
                    closing.remove(id, anvil);
                }
                answer.run();
            });
        };
    }

    /** A chat line; true when it answered a prompt and must not be broadcast. Safe on the async chat thread. */
    boolean chatted(Player player, String line) {
        Optional<TextPrompts.Waiting> waiting = prompts.waiting(player.getUniqueId());
        return waiting.isPresent() && waiting.get().input() == TextPrompts.Input.CHAT && prompts.typed(player.getUniqueId(), line);
    }

    /**
     * A click in a prompt anvil, the player's own rows included: always cancelled, so the paper never leaves and nothing
     * goes in. A left, right or shift click on the result of a waiting prompt answers with the edited name, as on mod
     * servers; number keys, drops and middle clicks do not.
     */
    boolean click(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !guarded(player.getUniqueId(), event.getView().getTopInventory())) return false;
        event.setCancelled(true);
        if (event.getRawSlot() == RESULT && BukkitScreens.click(event.getClick()).isPresent() && prompts.owns(player.getUniqueId(), event.getView().getTopInventory()))
            prompts.typed(player.getUniqueId(), typed(event.getView(), event.getView().getTopInventory()));
        return true;
    }

    boolean drag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !guarded(player.getUniqueId(), event.getView().getTopInventory())) return false;
        event.setCancelled(true);
        return true;
    }

    /** The prompt anvil closed: it is emptied first so the paper stays out of the player's inventory, then cancelled. */
    boolean closed(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return false;
        Inventory top = event.getView().getTopInventory();
        if (!guarded(player.getUniqueId(), top)) return false;
        top.clear();
        prompts.closed(player.getUniqueId(), top);
        closing.remove(player.getUniqueId(), top);
        return true;
    }

    /** Left or died: the prompt is cancelled and its anvil emptied. */
    void forget(Player player) {
        prompts.waiting(player.getUniqueId()).ifPresent((TextPrompts.Waiting waiting) -> { if (waiting.window() instanceof Inventory anvil) anvil.clear(); });
        prompts.cancel(player.getUniqueId());
        Inventory answered = closing.remove(player.getUniqueId());
        if (answered != null) answered.clear();
    }

    /** Shutdown: empties every prompt anvil so nothing is handed back when the server closes the windows. */
    void closeAll(Function<UUID, Player> online) {
        Set<UUID> players = new HashSet<>(prompts.clear());
        players.addAll(closing.keySet());
        closing.clear();
        for (UUID id : players) {
            Player player = online.apply(id);
            if (player != null && player.getOpenInventory().getTopInventory().getType() == InventoryType.ANVIL) {
                player.getOpenInventory().getTopInventory().clear();
                player.closeInventory();
            }
        }
    }

    /** Keeps the result a paper with the typed name and the confirm lore, whatever vanilla thinks a rename costs. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void prepare(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player) || !prompts.owns(player.getUniqueId(), event.getInventory())) return;
        Optional<TextPrompts.Waiting> waiting = prompts.waiting(player.getUniqueId());
        if (waiting.isEmpty()) return;
        String name = event.getView().getRenameText();
        event.setResult(result(name == null ? waiting.get().prompt().initial() : name, waiting.get().prompt()));
        event.getView().setRepairCost(0);
    }

    /** The edited name: the anvil's rename text, else the result paper's name without its codes. */
    private static String typed(InventoryView view, Inventory top) {
        if (view instanceof AnvilView anvil && anvil.getRenameText() != null) return anvil.getRenameText();
        ItemStack result = top.getItem(RESULT);
        return result != null && result.getItemMeta() != null && result.getItemMeta().hasDisplayName() ? ChatColor.stripColor(result.getItemMeta().getDisplayName()) : "";
    }

    /** The result paper: the typed name, then "Click to confirm" and the prompt's hint as upright lore. */
    private static ItemStack result(String name, ScreenHost.Prompt prompt) {
        ItemStack paper = paper(name);
        ItemMeta meta = paper.getItemMeta();
        if (meta == null) return paper;
        BukkitItems.dress(meta, Optional.empty(), TextPrompts.lore(prompt).stream().map(RichText::colorize).toList());
        paper.setItemMeta(meta);
        return paper;
    }

    /**
     * A paper named {@code name}, upright; a blank name becomes one space so the anvil's text field starts empty. It
     * carries no enchantments component, so the client, which prices each rename itself as the player types, finds
     * nothing an anvil can work on and never shows a cost.
     */
    static ItemStack paper(String name) {
        ItemStack paper;
        try { paper = Bukkit.getItemFactory().createItemStack(UNPRICED); }
        catch (RuntimeException | LinkageError unsupported) { paper = new ItemStack(Material.PAPER); }
        ItemMeta meta = paper.getItemMeta();
        if (meta != null) {
            BukkitItems.dress(meta, Optional.of(name.isBlank() ? " " : name), List.of());
            paper.setItemMeta(meta);
        }
        return paper;
    }
}
