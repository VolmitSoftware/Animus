package art.arcane.animus.bukkit.gui;

import art.arcane.animus.bukkit.folia.FoliaRegions;
import art.arcane.animus.bukkit.BukkitWorldLookup;

import art.arcane.animus.core.platform.MinecraftIds;
import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.PacketKind;
import art.arcane.animus.bukkit.protection.RegionContainerAccess;
import art.arcane.animus.bukkit.protection.RegionFlags;
import art.arcane.animus.bukkit.recording.RecorderLook;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.Economy;
import art.arcane.animus.core.inventory.ItemOps;
import art.arcane.animus.core.inventory.TradeInventory;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.bukkit.toolkit.BukkitWand;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.toolkit.Toolkit;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Method;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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
 * inventory all stay cancelled. Frames with offers open the villager trading screen through {@link BukkitMerchants}.
 */
public final class BukkitScreens implements ScreenHost<Player, ItemStack>, Listener {
    /** Identifies one Animus window. A window replaced by a new one no longer reports its close. */
    static final class Window implements InventoryHolder {
        private Inventory inventory;
        private String resolution;
        private boolean replaced;
        @Override public Inventory getInventory() { return inventory; }
        void replace() { replaced = true; }
    }

    private final JavaPlugin plugin;
    private final Server server;
    private final Logger logger;
    private final BiPredicate<Player, Runnable> later;
    private final Supplier<Economy> economy;
    private volatile ScreenManager<Player, ItemStack> manager;
    private final Map<UUID, Map<String, Lane>> bars = new ConcurrentHashMap<>();
    private final Method teleportAsync, entityScheduler, entityExecute;
    private final BukkitShopStock stocks;
    private final BukkitMerchants merchants;
    private final BukkitPrompts prompts;

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
        Method async = null, scheduler = null, execute = null;
        try { async = org.bukkit.entity.Entity.class.getMethod("teleportAsync", Location.class); }
        catch (NoSuchMethodException spigot) { /* Spigot teleports on the calling thread. */ }
        if (FoliaRegions.present()) {
            try {
                scheduler = org.bukkit.entity.Entity.class.getMethod("getScheduler");
                execute = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler").getMethod("execute", org.bukkit.plugin.Plugin.class, Runnable.class, Runnable.class, long.class);
            } catch (ReflectiveOperationException unbound) { scheduler = null; execute = null; }
        }
        teleportAsync = async; entityScheduler = scheduler; entityExecute = execute;
        RegionFlags.Binding regions = RegionFlags.bind(server);
        if (regions != null && regions.failure().isPresent()) logger.warning(regions.describe("container shops stay closed"));
        merchants = new BukkitMerchants(server, later, () -> manager);
        prompts = new BukkitPrompts(later);
        stocks = new BukkitShopStock(server, regions == null ? (UUID owner, Location at) -> true : new RegionContainerAccess(regions.flags()));
    }

    /** Vault when its plugin is installed; nothing from Vault is loaded otherwise. */
    public static Economy vault(Server server) {
        if (server.getPluginManager().getPlugin("Vault") == null) return Economy.none("Vault is not installed");
        return VaultEconomy.create(server);
    }

    public void bind(ScreenManager<Player, ItemStack> manager) {
        this.manager = Objects.requireNonNull(manager);
        server.getPluginManager().registerEvents(this, plugin);
        server.getPluginManager().registerEvents(merchants, plugin);
        prompts.register(plugin);
    }

    @Override public UUID id(Player player) { return player.getUniqueId(); }
    @Override public String name(Player player) { return player.getName(); }
    @Override public Object viewer(Player player, String property) {
        return switch (property) {
            case "world" -> player.getWorld().getName();
            case "world_key" -> player.getWorld().getKey().toString();
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
    @Override public boolean takeLevels(Player player, int levels) {
        if (player.getLevel() < levels) return false;
        player.giveExpLevels(-levels);
        return true;
    }
    @Override public void giveLevels(Player player, int levels) { player.giveExpLevels(levels); }

    @Override public Optional<Object> show(Player player, ScreenFrame frame, Object window) {
        if (frame.merchant().isPresent()) return merchants.show(player, frame, window);
        if (window instanceof BukkitMerchants.Window merchant) merchant.replace();
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
        AnimusMetrics metrics = AnimusMetrics.current();
        metrics.sent(metrics.viewer(player.getUniqueId(), player.getName()), null, PacketKind.GUI_WINDOW, 1);
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

    private ItemStack display(ScreenFrame.Item item) { return displayStack(server, item); }

    /**
     * The stack a display item shows, its name and lore upright; an invalid item shows a barrier. A head named for an
     * owner wears an online player's own profile, or for anyone else a name the client resolves (see
     * {@link BukkitItems#owned}), so drawing never waits on a lookup.
     */
    public static ItemStack displayStack(Server server, ScreenFrame.Item item) {
        Optional<Player> online = item.head().map(server::getPlayerExact);
        ItemStack stack = BukkitItems.display(item.item(), item.name(), item.lore(), new ArrayList<>())
                .orElseGet(() -> BukkitItems.display(new NpcItem("minecraft:barrier", 1, Optional.empty(), Optional.empty()), item.name(), item.lore(), new ArrayList<>()).orElseThrow());
        if (online.isPresent() && stack.getItemMeta() instanceof SkullMeta skull) { skull.setOwningPlayer(online.get()); stack.setItemMeta(skull); }
        if (online.isPresent() || stack.getType() != Material.PLAYER_HEAD) return stack;
        Optional<NpcItem> named = item.head().map((String owner) -> BukkitItems.owned(item.item(), owner)).filter((NpcItem owned) -> !owned.equals(item.item()));
        return named.map((NpcItem owned) -> named(stack, owned)).orElse(stack);
    }

    /**
     * Puts the name-only profile on last, after the name and lore: each time a skull's meta is applied with such a
     * profile, the server asks the session service to fill it in and throws the answer away. A profile the server
     * refuses leaves the default head.
     */
    private static ItemStack named(ItemStack stack, NpcItem owned) {
        try { return Bukkit.getUnsafe().modifyItemStack(stack, owned.components().orElseThrow()); }
        catch (RuntimeException refused) { return stack; }
    }

    @Override public void close(Player player, Object window) {
        if (merchants.close(player, window)) return;
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
        NamespacedKey key = NamespacedKey.fromString(MinecraftIds.normalize(sound));
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
    @Override public Optional<Pose> pose(Player player) {
        Location at = player.getLocation();
        World world = player.getWorld();
        return Optional.of(new Pose(Set.of(world.getName(), world.getKey().toString(), world.getUID().toString()), at.getX(), at.getY(), at.getZ(),
                player.getEyeLocation().getY(), at.getYaw(), at.getPitch(), player.isSneaking()));
    }

    /** Folia and Paper teleport asynchronously; true means the move was accepted, not that it finished. */
    @Override public boolean teleport(Player player, String world, double x, double y, double z, float yaw, float pitch) {
        World target = world(world);
        if (target == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return false;
        Location destination = new Location(target, x, y, z, yaw, pitch);
        if (teleportAsync != null) {
            try { teleportAsync.invoke(player, destination); return true; }
            catch (ReflectiveOperationException | RuntimeException failure) { logger.warning("Teleport failed for " + player.getName() + ": " + failure.getClass().getSimpleName()); return false; }
        }
        return player.teleport(destination);
    }

    private World world(String reference) { return BukkitWorldLookup.find(server, reference); }

    @Override public void bossBar(Player player, String lane, String title, float progress, String color, String style, int ticks) {
        Map<String, Lane> lanes = bars.computeIfAbsent(player.getUniqueId(), (UUID ignored) -> new ConcurrentHashMap<>());
        Lane previous = lanes.remove(lane);
        if (previous != null) previous.bar().removePlayer(player);
        if (ticks <= 0) return;
        BarColor barColor;
        BarStyle barStyle;
        try { barColor = BarColor.valueOf(color.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException unknown) { barColor = BarColor.WHITE; }
        try { barStyle = BarStyle.valueOf(style.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException unknown) { barStyle = BarStyle.SOLID; }
        BossBar bar = server.createBossBar(title, barColor, barStyle);
        bar.setProgress(Math.clamp(progress, 0f, 1f));
        bar.addPlayer(player);
        Lane shown = new Lane(bar);
        lanes.put(lane, shown);
        delayed(player, ticks, () -> { if (lanes.remove(lane, shown)) bar.removePlayer(player); });
    }
    private record Lane(BossBar bar) {}

    /** Runs on the player's owner after the delay; a Folia entity task is dropped with the player. */
    private void delayed(Player player, int ticks, Runnable work) {
        if (!plugin.isEnabled()) return;
        try {
            if (entityExecute != null) entityExecute.invoke(entityScheduler.invoke(player), plugin, work, (Runnable) () -> {}, (long) Math.max(1, ticks));
            else server.getScheduler().runTaskLater(plugin, work, Math.max(1, ticks));
        } catch (ReflectiveOperationException | RuntimeException failure) { logger.warning("Could not schedule a boss bar expiry: " + failure.getClass().getSimpleName()); }
    }

    @Override public boolean effect(Player player, String effect, int ticks, int amplifier) {
        NamespacedKey key = NamespacedKey.fromString(MinecraftIds.normalize(effect));
        PotionEffectType type = key == null ? null : Registry.EFFECT.get(key);
        if (type == null) return false;
        return player.addPotionEffect(new PotionEffect(type, ticks, amplifier));
    }

    @Override public boolean particle(Player player, String particle, int count, double spread, double x, double y, double z) {
        NamespacedKey key = NamespacedKey.fromString(MinecraftIds.normalize(particle));
        Particle type = key == null ? null : Registry.PARTICLE_TYPE.get(key);
        if (type == null || type.getDataType() != Void.class) return false;
        player.spawnParticle(type, x, y, z, count, spread, spread, spread, 0);
        return true;
    }

    @Override public void broadcast(Player source, String text, String scope, double radius, Optional<String> permission) {
        World world = source.getWorld();
        Location center = source.getLocation();
        for (Player online : server.getOnlinePlayers()) {
            later.test(online, () -> {
                if (!online.isOnline()) return;
                if (!scope.equals("server") && online.getWorld() != world) return;
                if (scope.equals("radius") && online.getLocation().distanceSquared(center) > radius * radius) return;
                if (permission.isPresent() && !online.hasPermission(permission.get())) return;
                online.sendMessage(text);
            });
        }
    }

    @Override public TradeInventory<ItemStack> inventory(Player player) { return BukkitItems.storage(player); }
    @Override public ItemOps<ItemStack> items() { return BukkitItems.INSTANCE; }
    @Override public Optional<ItemStack> stack(NpcItem item, Optional<String> name, List<String> lore, List<String> errors) { return BukkitItems.stack(item, name, lore, errors); }
    @Override public void drop(Player player, ItemStack stack) { player.getWorld().dropItemNaturally(player.getLocation(), stack); }
    @Override public Economy economy() { return economy.get(); }
    @Override public art.arcane.animus.core.shop.ShopStock<ItemStack> stock(art.arcane.animus.core.shop.ShopDocument shop) { return stocks.open(shop); }
    @Override public Optional<NpcItem> worn(Player player, EquipmentSlot slot) { return RecorderLook.item(RecorderLook.stack(player.getInventory(), slot)); }
    @Override public boolean holdsWand(Player player) { return BukkitWand.held(player); }
    @Override public WandGift giveWand(Player player) {
        Optional<ItemStack> wand = stack(Toolkit.WAND_ITEM, Optional.of(Toolkit.WAND_NAME), Toolkit.WAND_LORE, new ArrayList<>());
        return wand.map((ItemStack stack) -> BukkitWand.give(player, stack)).orElse(WandGift.NONE);
    }
    @Override public boolean prompt(Player player, Prompt prompt) { return prompts.open(player, prompt); }
    @Override public void chat(Player player, ChatLine line) {
        if (line.interactive()) ComponentMessenger.send(player, ComponentText.component(line.component()));
        else player.sendMessage(line.console());
    }
    @Override public void parts(Player player, List<art.arcane.animus.core.text.TextPart> parts) {
        player.spigot().sendMessage(art.arcane.animus.bukkit.BukkitChoiceMessages.components(parts));
    }
    @Override public boolean experience(Player player, int points) { player.giveExp(points); return true; }

    private static Optional<Window> window(InventoryView view) {
        return view.getTopInventory().getHolder() instanceof Window window ? Optional.of(window) : Optional.empty();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        if (prompts.click(event)) return;
        Optional<Window> window = window(event.getView());
        if (window.isEmpty()) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        Optional<GuiModel.Click> click = click(event.getClick());
        if (click.isEmpty()) return;
        int slot = event.getRawSlot();
        Window target = window.get();
        ScreenManager<Player, ItemStack> current = manager;
        if (current != null) later.test(player, () -> { if (player.isOnline()) current.click(player, target, slot, click.get()); });
    }

    /** The four bound clicks, as the mod host maps them; number keys, double clicks, drops, middle and border clicks are none. */
    static Optional<GuiModel.Click> click(ClickType type) {
        return switch (type) {
            case LEFT -> Optional.of(GuiModel.Click.LEFT);
            case RIGHT -> Optional.of(GuiModel.Click.RIGHT);
            case SHIFT_LEFT -> Optional.of(GuiModel.Click.SHIFT_LEFT);
            case SHIFT_RIGHT -> Optional.of(GuiModel.Click.SHIFT_RIGHT);
            default -> Optional.empty();
        };
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void drag(InventoryDragEvent event) {
        if (prompts.drag(event)) return;
        if (window(event.getView()).isPresent()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void closed(InventoryCloseEvent event) {
        if (prompts.closed(event)) return;
        Optional<Window> window = window(event.getView());
        ScreenManager<Player, ItemStack> current = manager;
        if (window.isEmpty() || window.get().replaced || current == null || !(event.getPlayer() instanceof Player player)) return;
        current.closed(player, window.get());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        Map<String, Lane> lanes = bars.remove(event.getPlayer().getUniqueId());
        if (lanes != null) for (Lane lane : lanes.values()) lane.bar().removeAll();
        merchants.forget(event.getPlayer().getUniqueId());
        prompts.forget(event.getPlayer());
        ScreenManager<Player, ItemStack> current = manager;
        if (current != null) current.quit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void died(PlayerDeathEvent event) {
        prompts.forget(event.getEntity());
        ScreenManager<Player, ItemStack> current = manager;
        if (current != null) current.died(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void worldChanged(PlayerChangedWorldEvent event) { ScreenManager<Player, ItemStack> current = manager; if (current != null) current.worldChanged(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleported(PlayerTeleportEvent event) { ScreenManager<Player, ItemStack> current = manager; if (current != null) current.teleported(event.getPlayer()); }

    /** Plugin shutdown: every Animus window closes now, so no display item stays clickable once events stop. */
    public void closeAll() {
        prompts.closeAll(server::getPlayer);
        ScreenManager<Player, ItemStack> current = manager;
        if (current == null) return;
        for (UUID id : current.clear()) {
            Player player = server.getPlayer(id);
            if (player == null || (window(player.getOpenInventory()).isEmpty() && !merchants.showing(player))) continue;
            try { player.closeInventory(); }
            catch (RuntimeException failure) { logger.warning("Could not close an Animus window for " + player.getName() + ": " + failure.getMessage()); }
        }
    }
}
