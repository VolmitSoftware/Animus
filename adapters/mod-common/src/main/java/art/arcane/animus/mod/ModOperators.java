package art.arcane.animus.mod;

import art.arcane.animus.core.hotload.OperatorChannel;
import java.util.List;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Hotload messages on Fabric, NeoForge and Forge: on the server thread, every online player with permission level 2
 * (or the {@code animus:hotload.notify} permission atom) hears the vanilla note block ding and sees the lines.
 */
final class ModOperators implements OperatorChannel {
    private final MinecraftServer server;

    ModOperators(MinecraftServer server) { this.server = server; }

    @Override public void notifyOperators(List<String> lines, boolean sound) {
        List<String> sent = List.copyOf(lines);
        server.execute(() -> {
            for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
                if (!VanillaRuntimeHost.permitted(player.permissions(), NOTIFY)) continue;
                if (sound) player.connection.send(new ClientboundSoundPacket(SoundEvents.NOTE_BLOCK_PLING, SoundSource.RECORDS,
                        player.getX(), player.getY(), player.getZ(), VOLUME, PITCH, player.getRandom().nextLong()));
                for (String line : sent) player.sendSystemMessage(ModText.section(line));
            }
        });
    }
}
