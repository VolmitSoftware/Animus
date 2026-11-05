package art.arcane.animus.bukkit.gui;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.Economy;
import art.arcane.animus.core.inventory.ItemOps;
import art.arcane.animus.core.inventory.TradeInventory;
import art.arcane.animus.core.npc.NpcItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Animus windows as Bukkit inventories. Every click and drag while one is the top inventory is cancelled, so no
 * item moves into or out of it; the four bound clicks on its own slots are handed to the screen on the next tick,
 * outside the inventory event. Number keys, the offhand key, drops, double clicks and shift clicks from the player's
 * inventory all stay cancelled.
 */
public final class BukkitScreens implements ScreenHost<Player, ItemStack>, Listener {
    /** Identifies one Animus window. A window replaced by a new one no longer reports its close. */
    static final class Window implements InventoryHolder {
        private Inventory inventory;
        private String resolution;
        private boolean replaced;
        @Override public Inventory getInventory() { return inventory; }
    }

    private final JavaPlugin plugin;
    private final Server server;
    private final Logger logger;
    private final BiPredicate<Player, Runnable> later;
    private final Supplier<Economy> economy;
    private volatile ScreenManager<Player, ItemStack> manager;

    /**
     * @param later runs work on the player's owner on a later tick; false when it cannot be scheduled
     * @param economy the money provider after the plugin settings
     */
    public BukkitScreens(JavaPlugin plugin, BiPredicate<Player, Runnable> later, Supplier<Economy> economy) {
        this.plugin = Objects.requireNonNull(plugin);
        this.server = plugin.getServer();
        this.logger = plugin.getLogger();
        this.later = Objects.requireNonNull(later);
        this.economy = Objects.requireNonNull(economy);
    }

    /** Vault when its plugin is installed; nothing from Vault is loaded otherwise. */
    public static Economy vault(Server server) {
        if (server.getPluginManager().getPlugin("Vault") == null) return Economy.none("Vault is not installed");
        return VaultEconomy.create(server);
    }

    public void bind(ScreenManager<Player, ItemStack> manager) {
        this.manager = Objects.requireNonNull(manager);
        server.getPluginManager().registerEvents(this, plugin);
    }

    @Override public UUID id(Player player) { return player.getUniqueId(); }
    @Override public String name(Player player) { return player.getName(); }
    @Override public Object viewer(Player player, String property) {
        return switch (property) {
            case "world" -> player.getWorld().getName();
            case "health" -> player.getHealth();
            case "level" -> player.getLevel();
            case "food" -> player.getFoodLevel();
            case "op" -> player.isOp();
            case "sneaking" -> player.isSneaking();
            case "flying" -> player.isFlying();
            case "gamemode" -> player.getGameMode().name().toLowerCase(Locale.ROOT);
            case "x" -> player.getLocation().getX();
            case "y" -> player.getLocation().getY();
            case "z" -> player.getLocation().getZ();
            case "server.online" -> server.getOnlinePlayers().size();
            case "server.max" -> server.getMaxPlayers();
            default -> null;
        };
    }
    @Override public boolean permitted(Player player, String node) { return player.hasPermission(node); }

    @Override public Optional<Object> show(Player player, ScreenFrame frame, Object window) {
        InventoryView view = player.getOpenInventory();
        if (window instanceof Window open && view.getTopInventory().getHolder() == open && open.resolution.equals(frame.resolution())) {
            fill(open.inventory, frame);
            if (!view.getTitle().equals(frame.title())) {
                try { view.setTitle(frame.title()); }
                catch (RuntimeException unsupported) { return reopen(player, frame, open); }
            }
            return Optional.of(open);
        }
        return reopen(player, frame, window instanceof Window open ? open : null);
    }

    private Optional<Object> reopen(Player player, ScreenFrame frame, Window previous) {
        Window next = new Window();
        next.resolution = frame.resolution();
        next.inventory = switch (frame.resolution()) {
            case "5x1" -> Bukkit.createInventory(next, InventoryType.HOPPER, frame.title());
            case "3x3" -> Bukkit.createInventory(next, InventoryType.DROPPER, frame.title());
            default -> Bukkit.createInventory(next, frame.size(), frame.title());
        };
        fill(next.inventory, frame);
        if (previous != null) previous.replaced = true;
        player.openInventory(next.inventory);
        if (player.getOpenInventory().getTopInventory().getHolder() != next) return Optional.empty();
        return Optional.of(next);
    }

    private void fill(Inventory inventory, ScreenFrame frame) {
        ItemStack[] contents = new ItemStack[inventory.getSize()];
        for (Map.Entry<Integer, ScreenFrame.Item> slot : frame.items().entrySet()) {
            if (slot.getKey() < 0 || slot.getKey() >= contents.length) continue;
            contents[slot.getKey()] = display(slot.getValue());
        }
        inventory.setContents(contents);
    }

    private ItemStack display(ScreenFrame.Item item) {
        List<String> errors = new ArrayList<>();
        ItemStack stack = BukkitItems.stack(item.item(), item.name(), item.lore(), errors)
                .orElseGet(() -> BukkitItems.stack(new NpcItem("minecraft:barrier", 1, Optional.empty(), Optional.empty()), item.name(), item.lore(), new ArrayList<>()).orElseThrow());
        if (item.head().isPresent() && stack.getItemMeta() instanceof SkullMeta skull) {
            Player owner = server.getPlayerExact(item.head().get());
            if (owner != null) { skull.setOwningPlayer(owner); stack.setItemMeta(skull); }
        }
        return stack;
    }

    @Override public void close(Player player, Object window) {
        if (window instanceof Window open && player.getOpenInventory().getTopInventory().getHolder() == open) {
            open.replaced = true;
            player.closeInventory();
        }
    }

    @Override public void message(Player player, String text) { player.sendMessage(text); }
    @Override public void actionBar(Player player, String text) {
        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, net.md_5.bungee.api.chat.TextComponent.fromLegacy(text));
    }
    @Override public void title(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut) { player.sendTitle(title, subtitle, fadeIn, stay, fadeOut); }
    @Override public void sound(Player player, String sound, String source, float volume, float pitch) {
        NamespacedKey key = NamespacedKey.fromString(sound.contains(":") ? sound : "minecraft:" + sound);
        Sound found = key == null ? null : Registry.SOUNDS.get(key);
        SoundCategory category;
        try { category = SoundCategory.valueOf(source.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException unknown) { category = SoundCategory.MASTER; }
        if (found != null) player.playSound(player.getLocation(), found, category, volume, pitch);
        else player.playSound(player.getLocation(), sound, category, volume, pitch);
    }
    @Override public void command(Optional<Player> player, String command) {
        if (player.isPresent()) { player.get().performCommand(command); return; }
        later.test(null, () -> server.dispatchCommand(server.getConsoleSender(), command));
    }
    @Override public void broadcast(String text) { for (Player online : server.getOnlinePlayers()) later.test(online, () -> online.sendMessage(text)); }
    @Override public TradeInventory<ItemStack> inventory(Player player) { return BukkitItems.storage(player); }
    @Override public ItemOps<ItemStack> items() { return BukkitItems.INSTANCE; }
    @Override public Optional<ItemStack> stack(NpcItem item, Optional<String> name, List<String> lore, List<String> errors) { return BukkitItems.stack(item, name, lore, errors); }
    @Override public void drop(Player player, ItemStack stack) { player.getWorld().dropItemNaturally(player.getLocation(), stack); }
    @Override public Economy economy() { return economy.get(); }

    private static Optional<Window> window(InventoryView view) {
        return view.getTopInventory().getHolder() instanceof Window window ? Optional.of(window) : Optional.empty();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        Optional<Window> window = window(event.getView());
        if (window.isEmpty()) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        GuiModel.Click click = switch (event.getClick()) {
            case LEFT -> GuiModel.Click.LEFT;
            case RIGHT -> GuiModel.Click.RIGHT;
            case SHIFT_LEFT -> GuiModel.Click.SHIFT_LEFT;
            case SHIFT_RIGHT -> GuiModel.Click.SHIFT_RIGHT;
            default -> null;
        };
        if (click == null) return;
        int slot = event.getRawSlot();
        Window target = window.get();
        ScreenManager<Player, ItemStack> current = manager;
        if (current != null) later.test(player, () -> { if (player.isOnline()) current.click(player, target, slot, click); });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void drag(InventoryDragEvent event) {
        if (window(event.getView()).isPresent()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void closed(InventoryCloseEvent event) {
        Optional<Window> window = window(event.getView());
        ScreenManager<Player, ItemStack> current = manager;
        if (window.isEmpty() || window.get().replaced || current == null || !(event.getPlayer() instanceof Player player)) return;
        current.closed(player, window.get());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { ScreenManager<Player, ItemStack> current = manager; if (current != null) current.quit(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void died(PlayerDeathEvent event) { ScreenManager<Player, ItemStack> current = manager; if (current != null) current.died(event.getEntity()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void worldChanged(PlayerChangedWorldEvent event) { ScreenManager<Player, ItemStack> current = manager; if (current != null) current.worldChanged(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleported(PlayerTeleportEvent event) { ScreenManager<Player, ItemStack> current = manager; if (current != null) current.teleported(event.getPlayer()); }

    /** Plugin shutdown: every Animus window closes now, so no display item stays clickable once events stop. */
    public void closeAll() {
        ScreenManager<Player, ItemStack> current = manager;
        if (current == null) return;
        for (UUID id : current.clear()) {
            Player player = server.getPlayer(id);
            if (player == null || window(player.getOpenInventory()).isEmpty()) continue;
            try { player.closeInventory(); }
            catch (RuntimeException failure) { logger.warning("Could not close an Animus window for " + player.getName() + ": " + failure.getMessage()); }
        }
    }
}
