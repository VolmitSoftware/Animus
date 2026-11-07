package art.arcane.animus.bukkit;

import art.arcane.animus.core.command.AnimusDirector;
import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
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
    @Override public void tell(String message) { rich(ChatLine.parse(Palette.reply(message))); }
    @Override public void plain(String line) { sender.sendMessage(sender instanceof Player ? line : RichText.legacy(line)); }
    @Override public void rich(ChatLine line) {
        if (sender instanceof Player && line.interactive()) ComponentMessenger.send(sender, ComponentText.component(line.component()));
        else plain(line.console());
    }
    @Override public Optional<UUID> player() { return sender instanceof Player player ? Optional.of(player.getUniqueId()) : Optional.empty(); }
    @Override public boolean reply(Runnable action) { return replies.reply(sender, action); }
    @Override public Optional<WorldPosition> position() {
        if (!(sender instanceof Player player)) return Optional.empty();
        Location at = player.getLocation();
        return at.getWorld() == null ? Optional.empty() : Optional.of(new WorldPosition(at.getWorld().getName(), new Vec3(at.getX(), at.getY(), at.getZ())));
    }
    /**
     * Living entities within {@link #NEARBY} blocks, nearest first. Completion runs on the player's own thread; on Folia an
     * entity read the region refuses offers nothing.
     */
    @Override public List<UUID> nearby() {
        if (!(sender instanceof Player player)) return List.of();
        try {
            Location at = player.getLocation();
            return player.getNearbyEntities(NEARBY, NEARBY, NEARBY).stream().filter(LivingEntity.class::isInstance)
                    .sorted(Comparator.comparingDouble((Entity entity) -> entity.getLocation().distanceSquared(at))).limit(NEARBY_LIMIT).map(Entity::getUniqueId).toList();
        } catch (RuntimeException refused) { return List.of(); }
    }
    private static final double NEARBY = 16;
    private static final int NEARBY_LIMIT = 20;
    /** Lenient MiniMessage through VolmLib: hover rows and click paging for players. */
    @Override public void menu(DirectorMiniMenu.ContentMenu menu) { DirectorMiniMenu.deliverContent(sender, menu, AnimusDirector.THEME, AnimusDirector.TEXT); }
}
