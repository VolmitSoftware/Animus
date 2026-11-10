package art.arcane.animus.bukkit.integration.modelengine;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.presentation.BodyTransport;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Draws Model Engine models on NPC bodies when the ModelEngine plugin is enabled. Model Engine classes are touched
 * only after that check, so Animus starts the same without it, showing every NPC's plain body.
 */
public final class ModelEngineIntegration {
    public static final String PLUGIN = "ModelEngine";

    private ModelEngineIntegration() {}

    /** {@code packets} with models drawn over it, or {@code packets} itself when Model Engine is missing or fails to bind. */
    public static BodyTransport<Player, ItemStack> wrap(Plugin plugin, BodyTransport<Player, ItemStack> packets, Supplier<Map<String, NpcDefinition>> definitions) {
        Plugin modelEngine = plugin.getServer().getPluginManager().getPlugin(PLUGIN);
        if (modelEngine == null || !modelEngine.isEnabled()) return packets;
        try {
            BodyTransport<Player, ItemStack> wrapped = Bound.wrap(plugin, packets, definitions);
            plugin.getLogger().info("Model Engine found; NPCs with animus:modelengine show their models");
            return wrapped;
        } catch (LinkageError | RuntimeException failed) {
            plugin.getLogger().log(Level.WARNING, "Model Engine could not be bound; NPCs show their own bodies", failed);
            return packets;
        }
    }

    /** Holds every Model Engine reference, so none loads until the plugin is known to be there. */
    private static final class Bound {
        static BodyTransport<Player, ItemStack> wrap(Plugin plugin, BodyTransport<Player, ItemStack> packets, Supplier<Map<String, NpcDefinition>> definitions) {
            DefinitionModels models = new DefinitionModels(definitions);
            AtomicReference<ModeledTransport<Player, ItemStack>> bound = new AtomicReference<>();
            AtomicReference<BukkitTask> ticks = new AtomicReference<>();
            ModelEngineModels driver = new ModelEngineModels(plugin, () -> {
                BukkitTask task = ticks.getAndSet(null);
                if (task != null) task.cancel();
                ModeledTransport<Player, ItemStack> transport = bound.get();
                if (transport != null) NpcModels.unbind(transport);
            });
            ModeledTransport<Player, ItemStack> transport = new ModeledTransport<>(packets, driver, models, new ModeledTransport.Owner<>() {
                @Override public UUID id(Player viewer) { return viewer.getUniqueId(); }
                @Override public void run(Player viewer, Runnable work) {
                    if (!plugin.isEnabled()) return;
                    if (plugin.getServer().isPrimaryThread()) work.run();
                    else plugin.getServer().getScheduler().runTask(plugin, work);
                }
            });
            bound.set(transport);
            ticks.set(plugin.getServer().getScheduler().runTaskTimer(plugin, transport::tick, 1, 1));
            NpcModels.bind(models, transport);
            return transport;
        }
    }
}
