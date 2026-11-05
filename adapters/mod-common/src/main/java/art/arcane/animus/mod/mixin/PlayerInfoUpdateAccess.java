package art.arcane.animus.mod.mixin;

import java.util.List;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla builds player-info entries only from real players; a profile-only body supplies its own entry. */
@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public interface PlayerInfoUpdateAccess {
    @Mutable
    @Accessor("entries")
    void animus$entries(List<ClientboundPlayerInfoUpdatePacket.Entry> entries);
}
