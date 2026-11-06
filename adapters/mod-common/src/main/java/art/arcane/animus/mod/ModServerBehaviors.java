package art.arcane.animus.mod;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.core.behavior.SnapshotFacts;
import art.arcane.animus.core.behavior.trigger.ServerCommandSync;
import art.arcane.animus.core.behavior.trigger.ServerEvent;
import art.arcane.animus.core.behavior.trigger.ServerTriggerDispatcher;
import art.arcane.animus.core.behavior.trigger.TriggerKind;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.ItemStack;

/**
 * Server behaviors on the mod server thread: player events published to the server dispatcher, Animus-managed commands in
 * the Brigadier tree, and the player and server fact snapshots. Everything here runs on the server thread.
 */
final class ModServerBehaviors {
    private static final int COMMAND_TICKS = 20;
    /** Brigadier has no removal API; these maps are where a root keeps its children. Null when the loader denies access. */
    private static final Field CHILDREN = field("children"), LITERALS = field("literals");
    private final MinecraftServer server;
    private final ServerTriggerDispatcher servers;
    private final SnapshotFacts facts;
    private final Consumer<String> log;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private ServerCommandSync sync;
    private List<Diagnostic> logged = List.of();
    private long ticks;

    ModServerBehaviors(MinecraftServer server, ServerTriggerDispatcher servers, SnapshotFacts facts, Consumer<String> log) {
        this.server = Objects.requireNonNull(server); this.servers = Objects.requireNonNull(servers);
        this.facts = Objects.requireNonNull(facts); this.log = Objects.requireNonNull(log);
    }

    void tick() {
        ticks++;
        if (ticks % SnapshotFacts.REFRESH_TICKS == 0) {
            List<ServerPlayer> online = List.copyOf(server.getPlayerList().getPlayers());
            for (ServerPlayer player : online) refresh(player);
            facts.server(SnapshotFacts.server(online.size(), server.getPlayerList().getMaxPlayers(), server.getTickCount(), System.currentTimeMillis()));
        }
        if (ticks % COMMAND_TICKS == 0) syncCommands();
    }

    /**
     * A datapack reload rebuilds the dispatcher, dropping our nodes with the old one; a new sync registers them again.
     * The sync's own collision lines are dropped: every refusal is logged here once per change.
     */
    private void syncCommands() {
        CommandDispatcher<CommandSourceStack> current = server.getCommands().getDispatcher();
        if (current != dispatcher) { dispatcher = current; sync = new ServerCommandSync(new Host(current), (String ignored) -> {}); }
        sync.sync(servers.commands());
        List<Diagnostic> refusals = sync.refusals();
        if (refusals.equals(logged)) return;
        logged = refusals;
        for (Diagnostic refusal : refusals) log.accept("Server behavior command: " + refusal.message() + " (" + refusal.field() + ")");
    }

    void close() {
        if (sync != null) sync.clear();
        sync = null;
        dispatcher = null;
    }

    void joined(ServerPlayer player) {
        refresh(player);
        if (listens(TriggerKind.PLAYER_JOIN)) servers.publish(ServerEvent.join(player.getUUID(), player.getStats().getValue(Stats.CUSTOM.get(Stats.LEAVE_GAME)) == 0));
    }
    void quit(ServerPlayer player) {
        facts.forget(player.getUUID());
        if (listens(TriggerKind.PLAYER_QUIT)) servers.publish(ServerEvent.quit(player.getUUID()));
    }
    void chat(ServerPlayer player, String message) {
        if (listens(TriggerKind.PLAYER_CHAT)) servers.publish(ServerEvent.chat(player.getUUID(), message));
    }
    void died(ServerPlayer player) {
        if (!listens(TriggerKind.PLAYER_DEATH)) return;
        String message = player.getCombatTracker().getDeathMessage().getString();
        servers.publish(ServerEvent.death(player.getUUID(), message.isBlank() ? Optional.empty() : Optional.of(message)));
    }
    void respawned(ServerPlayer player) {
        if (listens(TriggerKind.PLAYER_RESPAWN)) servers.publish(ServerEvent.respawn(player.getUUID()));
    }
    void worldChanged(ServerPlayer player, String from, String to) {
        if (!from.equals(to) && listens(TriggerKind.PLAYER_WORLD_CHANGE)) servers.publish(ServerEvent.worldChange(player.getUUID(), from, to));
    }
    private boolean listens(TriggerKind kind) { return servers.listens(kind); }

    private void refresh(ServerPlayer player) {
        net.minecraft.world.phys.Vec3 at = player.position();
        ItemStack held = player.getMainHandItem();
        facts.player(player.getUUID(), SnapshotFacts.player(player.getGameProfile().name(), player.getUUID(), player.getHealth(), player.getMaxHealth(),
                player.getFoodData().getFoodLevel(), player.experienceLevel, ModWorldKeys.key(player.level()), at.x(), at.y(), at.z(),
                player.gameMode().getName(), server.getPlayerList().isOp(player.nameAndId()), player.isShiftKeyDown(), player.getAbilities().flying,
                BuiltInRegistries.ITEM.getKey(held.getItem()).toString(), held.getCount()));
    }

    /** The commands this sync added to one dispatcher. Every label also answers through {@link #labels}, so a node that could not be removed goes quiet. */
    private final class Host implements ServerCommandSync.Host {
        private final CommandDispatcher<CommandSourceStack> target;
        private final Map<String, CommandNode<CommandSourceStack>> nodes = new HashMap<>();
        private final Map<String, ServerTriggerDispatcher.Command> labels = new HashMap<>();

        Host(CommandDispatcher<CommandSourceStack> target) { this.target = target; }

        @Override public boolean taken(String label) {
            CommandNode<CommandSourceStack> child = target.getRoot().getChild(label);
            return child != null && child != nodes.get(label);
        }
        @Override public void register(ServerTriggerDispatcher.Command command, List<String> names) {
            for (String label : names) {
                labels.put(label, command);
                target.register(Commands.literal(label)
                        .executes((CommandContext<CommandSourceStack> context) -> run(context.getSource(), label, ""))
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .executes((CommandContext<CommandSourceStack> context) -> run(context.getSource(), label, StringArgumentType.getString(context, "args")))));
                nodes.put(label, target.getRoot().getChild(label));
            }
        }
        @Override public void unregister(ServerTriggerDispatcher.Command command, List<String> names) {
            for (String label : names) {
                labels.remove(label);
                if (remove(target.getRoot(), label, nodes.get(label))) nodes.remove(label);
            }
        }
        @Override public void changed() {
            if (server.getCommands().getDispatcher() != target) return;
            for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) server.getCommands().sendCommands(player);
        }

        private int run(CommandSourceStack source, String label, String raw) {
            ServerTriggerDispatcher.Command command = labels.get(label);
            if (command == null) { source.sendFailure(Component.literal("Unknown command: /" + label)); return 0; }
            ServerPlayer player = source.getEntity() instanceof ServerPlayer online ? online : null;
            boolean permitted = player == null || command.permission().map((String node) -> VanillaRuntimeHost.permitted(player.permissions(), node)).orElse(true);
            if (!ServerCommandSync.fire(servers, command, player == null ? Optional.empty() : Optional.of(player.getUUID()), permitted, label, raw)) {
                source.sendFailure(Component.literal("You do not have permission to use this command."));
                return 0;
            }
            return 1;
        }
    }

    /** True when the label no longer names our node. A loader that denies reflection keeps the node; its label then answers unknown. */
    static <S> boolean remove(RootCommandNode<S> root, String label, CommandNode<S> ours) {
        if (ours == null || root.getChild(label) != ours) return true;
        if (CHILDREN == null || LITERALS == null) return false;
        try {
            ((Map<?, ?>) CHILDREN.get(root)).remove(label);
            ((Map<?, ?>) LITERALS.get(root)).remove(label);
            return true;
        } catch (IllegalAccessException | RuntimeException failure) { return false; }
    }
    private static Field field(String name) {
        try {
            Field field = CommandNode.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException denied) { return null; }
    }
}
