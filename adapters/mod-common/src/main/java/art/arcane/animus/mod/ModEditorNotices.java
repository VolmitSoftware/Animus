package art.arcane.animus.mod;

import art.arcane.animus.core.platform.MinecraftIds;
import art.arcane.animus.core.config.EditorNotifySettings;
import art.arcane.animus.core.editor.EditorNotice;
import art.arcane.animus.core.editor.EditorNoticeChannel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * Editor notices on Fabric, NeoForge and Forge: on the server thread, every online player with permission level 2 (or the
 * {@code animus:editor.notify} permission atom), and the player who paired the token, hears the sound and sees the line.
 */
final class ModEditorNotices implements EditorNoticeChannel {
    private final MinecraftServer server;

    ModEditorNotices(MinecraftServer server) { this.server = server; }

    @Override public Optional<String> name(UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        return online == null ? Optional.empty() : Optional.of(online.getGameProfile().name());
    }

    @Override public void deliver(EditorNotice notice) {
        Optional<Holder<SoundEvent>> sound = notice.sound().map((EditorNotifySettings.Sound chosen) -> event(chosen.id()));
        Optional<Component> chat = notice.chat().map((EditorNotice.Line text) -> {
            Style style = Style.EMPTY;
            if (text.hover().isPresent()) style = style.withHoverEvent(new HoverEvent.ShowText(ModText.section(text.hover().get())));
            if (text.suggest().isPresent()) style = style.withClickEvent(new ClickEvent.SuggestCommand(text.suggest().get()));
            MutableComponent whole = Component.empty().append(Component.empty().append(ModText.section(text.text())).setStyle(style));
            text.copy().ifPresent((EditorNotice.Copy copy) -> whole.append(Component.empty().append(ModText.section(copy.text()))
                    .setStyle(Style.EMPTY.withClickEvent(new ClickEvent.CopyToClipboard(copy.value())).withHoverEvent(new HoverEvent.ShowText(ModText.section(copy.hover()))))));
            return whole;
        });
        Optional<Component> bar = notice.actionBar().map(ModText::section);
        server.execute(() -> {
            for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
                if (!notice.reaches(player.getUUID(), VanillaRuntimeHost.permitted(player.permissions(), PERMISSION))) continue;
                if (sound.isPresent()) {
                    EditorNotifySettings.Sound chosen = notice.sound().get();
                    player.connection.send(new ClientboundSoundPacket(sound.get(), SoundSource.MASTER, player.getX(), player.getY(), player.getZ(),
                            (float) chosen.volume(), (float) chosen.pitch(), player.getRandom().nextLong()));
                }
                chat.ifPresent(player::sendSystemMessage);
                bar.ifPresent((Component text) -> player.sendSystemMessage(text, true));
            }
        });
    }

    private static Holder<SoundEvent> event(String sound) {
        Identifier id = Identifier.parse(MinecraftIds.normalize(sound));
        return BuiltInRegistries.SOUND_EVENT.get(id).<Holder<SoundEvent>>map((Holder.Reference<SoundEvent> reference) -> reference)
                .orElseGet(() -> Holder.direct(SoundEvent.createVariableRangeEvent(id)));
    }
}
