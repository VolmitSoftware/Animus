package art.arcane.animus.mod;

import art.arcane.animus.core.command.AnimusDirector;
import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/**
 * A command source as the shared command set sees it. Every source is answered on the server thread. Help and click
 * chips arrive as Adventure components and are converted here; paged listings reach a player in the same DirectorMiniMenu
 * look as on Bukkit, which VolmLib delivers only to Bukkit senders, so those lines are parsed here too.
 */
record ModActor(CommandSourceStack source, ModTicks ticks) implements CommandActor {
    ModActor {
        Objects.requireNonNull(source); Objects.requireNonNull(ticks);
    }
    @Override public boolean permitted(String node) { return VanillaRuntimeHost.permitted(source.permissions(), node); }
    /** Section codes, such as the debug command's colours and the brand lead {@link Palette#reply} adds, render as styled text. */
    @Override public void tell(String message) { rich(ChatLine.parse(RichText.colorize(Palette.reply(message)))); }
    @Override public void plain(String line) { source.sendSystemMessage(ModText.component(line)); }
    /** Players get the click chips; the console gets the plain text with each link written out. */
    @Override public void rich(ChatLine line) {
        if (source.getPlayer() != null && line.interactive()) source.sendSystemMessage(ModText.adventure(line.component()));
        else plain(line.console());
    }
    @Override public Optional<UUID> player() {
        ServerPlayer player = source.getPlayer();
        return player == null ? Optional.empty() : Optional.of(player.getUUID());
    }
    @Override public boolean reply(Runnable action) { return ticks.later(action); }
    @Override public Optional<WorldPosition> position() {
        ServerPlayer player = source.getPlayer();
        return player == null ? Optional.empty() : Optional.of(new WorldPosition(ModWorldKeys.key(player.level()), new Vec3(player.getX(), player.getY(), player.getZ())));
    }
    /** Living entities within {@link #NEARBY} blocks, nearest first, without the player. Runs on the server thread. */
    @Override public List<UUID> nearby() {
        ServerPlayer player = source.getPlayer();
        if (player == null) return List.of();
        AABB area = player.getBoundingBox().inflate(NEARBY);
        return player.level().getEntitiesOfClass(LivingEntity.class, area, (LivingEntity candidate) -> candidate != player).stream()
                .sorted(Comparator.comparingDouble((LivingEntity entity) -> entity.distanceToSqr(player))).limit(NEARBY_LIMIT).map(LivingEntity::getUUID).toList();
    }
    private static final double NEARBY = 16;
    private static final int NEARBY_LIMIT = 20;

    @Override public void menu(DirectorMiniMenu.ContentMenu menu) {
        if (source.getPlayer() == null) CommandActor.super.menu(menu);
        else deliver(DirectorMiniMenu.renderContent(menu, AnimusDirector.THEME, AnimusDirector.TEXT));
    }

    /** As VolmLib does for a Bukkit player: blank lines first so a page re-renders in place, then each line. */
    private void deliver(List<String> lines) {
        source.sendSystemMessage(Component.literal("\n".repeat(DirectorMiniMenu.CHAT_LINE_COUNT - 1)));
        for (String line : lines) if (line != null && !line.isBlank()) source.sendSystemMessage(ModText.miniMessage(line));
    }
}
