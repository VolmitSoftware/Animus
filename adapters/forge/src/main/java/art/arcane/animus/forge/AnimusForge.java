package art.arcane.animus.forge;

import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.Platform;
import art.arcane.animus.mod.AnimusModRuntime;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod(BuildIdentity.ID)
public final class AnimusForge {
    private final AnimusModRuntime runtime = new AnimusModRuntime(Platform.FORGE, FMLPaths.CONFIGDIR.get());

    public AnimusForge(FMLJavaModLoadingContext context) {
        RegisterCommandsEvent.BUS.addListener(event -> runtime.registerCommands(event.getDispatcher()));
        ServerStartedEvent.BUS.addListener(event -> runtime.start());
        ServerStoppingEvent.BUS.addListener(event -> runtime.stop());
    }
}
