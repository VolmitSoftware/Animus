package art.arcane.animus.mod;

import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.AnimusSplash;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;

public final class AnimusModRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(BuildIdentity.ID);

    private final Platform platform;
    private final Path dataDirectory;
    private AnimusRuntime runtime;

    public AnimusModRuntime(Platform platform, Path configDirectory) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.dataDirectory = Objects.requireNonNull(configDirectory, "configDirectory").resolve(BuildIdentity.ID);
    }

    public void start() {
        if (runtime != null) {
            throw new IllegalStateException(BuildIdentity.NAME + " is already running");
        }
        RuntimeEnvironment environment = new RuntimeEnvironment(
            platform, MinecraftVersion.current(), dataDirectory);
        AnimusRuntime started = new AnimusRuntime(environment);
        try {
            started.start();
            runtime = started;
            LOGGER.info(AnimusSplash.render(environment,
                platform + " / Minecraft " + environment.minecraftVersion(), true));
        } catch (IOException exception) {
            LOGGER.error("Could not start " + BuildIdentity.NAME, exception);
            throw new UncheckedIOException("Could not start " + BuildIdentity.NAME, exception);
        } catch (RuntimeException exception) {
            LOGGER.error("Could not start " + BuildIdentity.NAME, exception);
            throw exception;
        }
    }

    public void stop() {
        AnimusRuntime active = runtime;
        runtime = null;
        if (active == null) {
            return;
        }
        try {
            active.close();
            LOGGER.info("{} stopped", BuildIdentity.NAME);
        } catch (RuntimeException exception) {
            LOGGER.error("Could not stop " + BuildIdentity.NAME, exception);
            throw exception;
        }
    }

    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(BuildIdentity.ID)
            .executes(context -> status(context.getSource()))
            .then(Commands.literal("status").executes(context -> status(context.getSource()))));
    }

    private int status(CommandSourceStack source) {
        AnimusRuntime active = runtime;
        if (active == null || !active.isRunning()) {
            source.sendFailure(Component.literal(BuildIdentity.NAME + " is not running."));
            return 0;
        }
        String message = active.status();
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }
}
