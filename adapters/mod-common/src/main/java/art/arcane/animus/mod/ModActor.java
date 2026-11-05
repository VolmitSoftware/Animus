package art.arcane.animus.mod;

import art.arcane.animus.core.command.CommandActor;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/** A command source as the shared command set sees it. Every source is answered on the server thread. */
record ModActor(CommandSourceStack source, ModTicks ticks) implements CommandActor {
    ModActor {
        Objects.requireNonNull(source); Objects.requireNonNull(ticks);
    }
    @Override public boolean permitted(String node) { return VanillaRuntimeHost.permitted(source.permissions(), node); }
    /** Section codes, such as the debug command's colours, render as styled text. */
    @Override public void tell(String message) { source.sendSystemMessage(ModText.component(message)); }
    @Override public Optional<UUID> player() {
        ServerPlayer player = source.getPlayer();
        return player == null ? Optional.empty() : Optional.of(player.getUUID());
    }
    @Override public boolean reply(Runnable action) { return ticks.later(action); }
}
