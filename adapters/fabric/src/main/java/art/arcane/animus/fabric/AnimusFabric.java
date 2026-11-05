package art.arcane.animus.fabric;

import art.arcane.animus.core.Platform;
import art.arcane.animus.mod.AnimusModRuntime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class AnimusFabric implements ModInitializer {
    private final AnimusModRuntime runtime = new AnimusModRuntime(Platform.FABRIC, FabricLoader.getInstance().getConfigDir());

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> runtime.registerCommands(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(runtime::start);
        ServerTickEvents.END_SERVER_TICK.register(server -> runtime.tick());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> runtime.stop());
    }
}
