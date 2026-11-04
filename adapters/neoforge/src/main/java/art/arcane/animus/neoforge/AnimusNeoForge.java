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

@Mod(BuildIdentity.ID)
public final class AnimusNeoForge {
    private final AnimusModRuntime runtime = new AnimusModRuntime(Platform.NEOFORGE, FMLPaths.CONFIGDIR.get());

    public AnimusNeoForge() {
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::start);
        NeoForge.EVENT_BUS.addListener(this::stop);
    }

    private void registerCommands(RegisterCommandsEvent event) {
        runtime.registerCommands(event.getDispatcher());
    }

    private void start(ServerStartedEvent event) {
        runtime.start();
    }

    private void stop(ServerStoppingEvent event) {
        runtime.stop();
    }
}
