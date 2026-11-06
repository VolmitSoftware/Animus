package art.arcane.animus.bukkit.recording;

import art.arcane.animus.bukkit.folia.FoliaRegions;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.config.RecordingSettings;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.recording.GhostReplay;
import art.arcane.animus.core.recording.RecordingCapture;
import art.arcane.animus.core.recording.RecordingDocument;
import art.arcane.animus.core.recording.RecordingEvent;
import art.arcane.animus.core.recording.RecordingHost;
import art.arcane.animus.core.recording.RecordingSessions;
import art.arcane.animus.core.virtual.Vec3;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Recording sessions on Bukkit, Paper and Folia: the player's owner is the main thread, or its entity scheduler on
 * Folia, and ghosts are packets through the plugin's own adapter. Swings and door use arrive as Bukkit events, which
 * fire on the player's owner.
 */
public final class BukkitRecordings implements RecordingHost<Player, ItemStack>, Listener, AutoCloseable {
    private static final String ADMIN = "animus.admin";
    private final JavaPlugin plugin;
    private final RecorderOwners owners;
    private final GhostBodies ghosts;
    private final RecordingSessions<Player, ItemStack> sessions;

    /** @param regions the Folia schedulers, or null off Folia */
    public BukkitRecordings(JavaPlugin plugin, AnimusRuntime runtime, Executor files, FoliaRegions regions, String version) {
        this.plugin = plugin;
        owners = new RecorderOwners(plugin, regions);
        ghosts = new GhostBodies(plugin, version, owners);
        sessions = new RecordingSessions<>(this, runtime, files);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public RecordingSessions<Player, ItemStack> sessions() { return sessions; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void swung(PlayerAnimationEvent event) {
        if (event.getAnimationType() == PlayerAnimationType.ARM_SWING) sessions.swung(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void interacted(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (RecorderLook.openable(block)) sessions.used(event.getPlayer(), block.getX(), block.getY(), block.getZ(), block.getType().getKeyOrThrow().toString());
    }

    @Override public UUID id(Player player) { return player.getUniqueId(); }
    @Override public String name(Player player) { return player.getName(); }
    @Override public boolean online(Player player) { return player.isOnline(); }
    @Override public boolean dead(Player player) { return player.isDead(); }
    @Override public String world(Player player) { return player.getWorld().getName(); }
    @Override public Vec3 position(Player player) { Location at = player.getLocation(); return new Vec3(at.getX(), at.getY(), at.getZ()); }
    @Override public boolean admin(Player player) { return player.hasPermission(ADMIN); }
    @Override public Optional<BodySkin> skin(Player player) { return RecorderLook.skin(player); }
    @Override public Optional<Player> player(UUID id) { return Optional.ofNullable(plugin.getServer().getPlayer(id)); }
    @Override public Optional<String> knownName(UUID id) { return Optional.ofNullable(plugin.getServer().getOfflinePlayer(id).getName()); }

    /**
     * Reads the player directly; equipment converts only when a slot's stack changed since the last sample. On-ground is
     * the client's own claim, deprecated for anti-cheat use, which is exactly what playback should repeat.
     */
    @Override public Supplier<RecordingCapture.Sample> sampler(Player player) {
        Map<art.arcane.animus.core.npc.EquipmentSlot, ItemStack> stacks = new EnumMap<>(art.arcane.animus.core.npc.EquipmentSlot.class);
        Map<art.arcane.animus.core.npc.EquipmentSlot, Optional<NpcItem>> items = new EnumMap<>(art.arcane.animus.core.npc.EquipmentSlot.class);
        return () -> sample(player, stacks, items);
    }

    @SuppressWarnings("deprecation")
    private static RecordingCapture.Sample sample(Player player, Map<art.arcane.animus.core.npc.EquipmentSlot, ItemStack> stacks,
                                                  Map<art.arcane.animus.core.npc.EquipmentSlot, Optional<NpcItem>> items) {
        Location at = player.getLocation();
        PlayerInventory inventory = player.getInventory();
        for (art.arcane.animus.core.npc.EquipmentSlot slot : RecordingEvent.SLOTS) {
            ItemStack stack = RecorderLook.stack(inventory, slot);
            ItemStack before = stacks.get(slot);
            if (items.containsKey(slot) && (stack == null ? before == null : stack.equals(before))) continue;
            stacks.put(slot, stack == null ? null : stack.clone());
            items.put(slot, RecorderLook.item(stack));
        }
        return new RecordingCapture.Sample(player.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch(),
                player.isOnGround(), player.isSneaking(), player.isSprinting(), player.isSwimming(), player.isGliding(), items);
    }

    @Override public void later(Player player, Runnable work, Runnable gone) { owners.later(player, work, gone); }
    @Override public AutoCloseable everyTick(Player player, Runnable tick, Runnable retired) { return owners.everyTick(player, tick, retired); }

    @Override public Optional<String> ghostsUnavailable() { return ghosts.unavailable(); }
    @Override public GhostReplay<Player, ItemStack> ghost(Player viewer, RecordingDocument document, String name, Optional<BodySkin> skin, RecordingSettings settings) {
        return ghosts.play(viewer, document, name, skin, settings);
    }
    @Override public void release(Player viewer) { ghosts.release(viewer); }
    @Override public void closeGhosts() { ghosts.close(); }

    @Override public void info(String message) { plugin.getLogger().info(message); }
    @Override public void warn(String message) { plugin.getLogger().warning(message); }

    @Override public void close() { sessions.close(); }
}
