package art.arcane.animus.neoforge;

import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.Platform;
import art.arcane.animus.mod.AnimusModRuntime;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(BuildIdentity.ID)
public final class AnimusNeoForge {
    private final AnimusModRuntime runtime = new AnimusModRuntime(Platform.NEOFORGE, FMLPaths.CONFIGDIR.get());

    public AnimusNeoForge() {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> runtime.registerCommands(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> runtime.start(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> runtime.tick());
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> runtime.stop());
    }
}
