package art.arcane.animus.mod;

import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.schedule.RuntimeScheduleWorld;
import art.arcane.animus.core.schedule.ScheduleWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** The dimension clock, items and console of a mod server, for NPC schedules. */
final class ModScheduleHost implements RuntimeScheduleWorld.Host<ItemStack> {
    private static final long DAY = 24_000;
    private final MinecraftServer server;
    private final NpcRuntime<?, ItemStack> living;

    ModScheduleHost(MinecraftServer server, NpcRuntime<?, ItemStack> living) { this.server = server; this.living = living; }

    @Override public Optional<ScheduleWorld.Clock> clock(String world) {
        ServerLevel level = ModWorldKeys.level(server, world);
        return level == null ? Optional.empty() : Optional.of(new ScheduleWorld.Clock(Math.floorMod(level.getDefaultClockTime(), DAY), level.isRaining()));
    }

    @Override public ItemStack item(Optional<NpcItem> item) {
        if (item.isEmpty()) return ItemStack.EMPTY;
        List<String> errors = new ArrayList<>();
        ItemStack stack = VanillaRuntimeHost.stack(server, item.get(), "item", errors);
        if (stack == null) throw new IllegalArgumentException(errors.getFirst());
        return stack;
    }

    @Override public void command(String command) {
        if (!living.players().console(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command)))
            throw new IllegalStateException("The console refused the command " + command);
    }
}
