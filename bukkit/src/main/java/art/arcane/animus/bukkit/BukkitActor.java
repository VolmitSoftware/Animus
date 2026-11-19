package art.arcane.animus.bukkit;

import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.command.CommandSound;
import art.arcane.animus.core.command.Coordinates;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/** A Bukkit command sender as the shared command set sees it. Replies go through the sender's own scheduler. */
record BukkitActor(CommandSender sender, ReplyScheduler replies) implements CommandActor {
    BukkitActor {
        Objects.requireNonNull(sender); Objects.requireNonNull(replies);
    }
    @Override public boolean permitted(String node) { return sender.hasPermission(node); }
    /**
     * Players see the brand pinks in hex, with transaction ids and links as click chips; the console gets the legacy
     * fallbacks, which every Bukkit console renders.
     */
    @Override public void tell(String message) { rich(line(message)); }
    @Override public void plain(String line) { BukkitChat.send(sender, line); }
    @Override public void rich(ChatLine line) { BukkitChat.send(sender, line); }
    @Override public Optional<UUID> player() { return sender instanceof Player player ? Optional.of(player.getUniqueId()) : Optional.empty(); }
    @Override public boolean reply(Runnable action) { return replies.reply(sender, action); }
    @Override public Optional<WorldPosition> position() {
        if (!(sender instanceof Player player)) return Optional.empty();
        Location at = player.getLocation();
        return at.getWorld() == null ? Optional.empty() : Optional.of(new WorldPosition(at.getWorld().getName(), new Vec3(at.getX(), at.getY(), at.getZ())));
    }
    /**
     * Mobs within {@link #NEARBY} blocks, nearest first. Completion runs on the player's own thread; on Folia an entity read
     * the region refuses offers nothing.
     */
    @Override public List<UUID> nearby() { return near((Entity entity) -> entity instanceof LivingEntity && !(entity instanceof Player)); }
    /** Other players within {@link #NEARBY} blocks, nearest first. */
    @Override public List<UUID> nearbyPlayers() { return near(Player.class::isInstance); }
    private List<UUID> near(java.util.function.Predicate<Entity> kind) {
        if (!(sender instanceof Player player)) return List.of();
        try {
            Location at = player.getLocation();
            return player.getNearbyEntities(NEARBY, NEARBY, NEARBY).stream().filter(kind)
                    .sorted(Comparator.comparingDouble((Entity entity) -> entity.getLocation().distanceSquared(at))).limit(NEARBY_LIMIT).map(Entity::getUniqueId).toList();
        } catch (RuntimeException refused) { return List.of(); }
    }
    private static final double NEARBY = 16;
    private static final int NEARBY_LIMIT = 20;
    /** One MiniMessage line, styled with its hover and click, as VolmLib's menus send each of theirs. */
    @Override public void miniMessage(String line) { ComponentMessenger.send(sender, MiniMessage.miniMessage().deserialize(line)); }
    @Override public Optional<Coordinates.Facing> facing() {
        if (!(sender instanceof Player player)) return Optional.empty();
        Location at = player.getLocation();
        return Optional.of(new Coordinates.Facing(at.getYaw(), at.getPitch()));
    }
    /** The block in the crosshair within {@link #REACH} blocks; on Folia a read the region refuses offers nothing. */
    @Override public Optional<Vec3> target() {
        if (!(sender instanceof Player player)) return Optional.empty();
        try {
            Block block = player.getTargetBlockExact(REACH);
            return block == null ? Optional.empty() : Optional.of(new Vec3(block.getX(), block.getY(), block.getZ()));
        } catch (RuntimeException refused) { return Optional.empty(); }
    }
    private static final int REACH = 5;
    /** At the player in the master category, as Iris plays its command sounds; scheduled on the player's own owner, which on Folia is its region. */
    @Override public void play(CommandSound sound) {
        if (!(sender instanceof Player player)) return;
        List<CommandSound.Tone> tones = sound.tones(ThreadLocalRandom.current());
        replies.reply(player, () -> {
            for (CommandSound.Tone tone : tones) player.playSound(player.getLocation(), tone.sound(), SoundCategory.MASTER, tone.volume(), tone.pitch());
        });
    }
}
