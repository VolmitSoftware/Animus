package art.arcane.animus.mod;

import art.arcane.animus.core.hotload.OperatorChannel;
import art.arcane.animus.core.text.ChatLine;
import java.util.List;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Hotload messages on Fabric, NeoForge and Forge: on the server thread, every online player with permission level 2
 * (or the {@code animus:hotload.notify} permission atom) hears the vanilla note block ding and sees the line.
 */
final class ModOperators implements OperatorChannel {
    private final MinecraftServer server;

    ModOperators(MinecraftServer server) { this.server = server; }

    @Override public void notifyOperators(ChatLine line, boolean sound) {
        server.execute(() -> {
            for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
                if (!VanillaRuntimeHost.permitted(player.permissions(), NOTIFY)) continue;
                if (sound) player.connection.send(new ClientboundSoundPacket(SoundEvents.NOTE_BLOCK_PLING, SoundSource.RECORDS,
                        player.getX(), player.getY(), player.getZ(), VOLUME, PITCH, player.getRandom().nextLong()));
                player.sendSystemMessage(line.interactive() ? ModText.adventure(line.component()) : ModText.section(line.console()));
            }
        });
    }
}
