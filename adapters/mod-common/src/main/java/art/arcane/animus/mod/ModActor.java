package art.arcane.animus.mod;

import art.arcane.animus.core.command.AnimusDirector;
import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.text.Palette;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * A command source as the shared command set sees it. Every source is answered on the server thread. Help reaches a
 * player in the same DirectorMiniMenu look as on Bukkit; VolmLib delivers it only to Bukkit senders, so the lines are
 * parsed here and sent as vanilla components.
 */
record ModActor(CommandSourceStack source, ModTicks ticks) implements CommandActor {
    ModActor {
        Objects.requireNonNull(source); Objects.requireNonNull(ticks);
    }
    @Override public boolean permitted(String node) { return VanillaRuntimeHost.permitted(source.permissions(), node); }
    /** Section codes, such as the debug command's colours and the brand lead {@link Palette#reply} adds, render as styled text. */
    @Override public void tell(String message) { plain(Palette.reply(message)); }
    @Override public void plain(String line) { source.sendSystemMessage(ModText.component(line)); }
    @Override public Optional<UUID> player() {
        ServerPlayer player = source.getPlayer();
        return player == null ? Optional.empty() : Optional.of(player.getUUID());
    }
    @Override public boolean reply(Runnable action) { return ticks.later(action); }

    @Override public void menu(DirectorMiniMenu.DirectorHelpPage page) {
        if (source.getPlayer() == null) CommandActor.super.menu(page);
        else deliver(DirectorMiniMenu.render(page, AnimusDirector.THEME, AnimusDirector.TEXT));
    }

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
