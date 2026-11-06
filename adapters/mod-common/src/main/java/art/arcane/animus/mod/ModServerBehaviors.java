package art.arcane.animus.mod;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.core.behavior.SnapshotFacts;
import art.arcane.animus.core.behavior.trigger.ServerCommandSync;
import art.arcane.animus.core.behavior.trigger.ServerEvent;
import art.arcane.animus.core.behavior.trigger.ServerTriggerDispatcher;
import art.arcane.animus.core.behavior.trigger.TriggerKind;
import art.arcane.animus.core.platform.ModWorlds;
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
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import art.arcane.animus.mod.mixin.ContainerMenuAccess;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

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
    /** The loaded dimension keys, refreshed with the fact snapshots, so fact readers on any thread resolve worlds without the server. */
    private volatile Set<String> dimensions;

    ModServerBehaviors(MinecraftServer server, ServerTriggerDispatcher servers, SnapshotFacts facts, Consumer<String> log) {
        this.server = Objects.requireNonNull(server); this.servers = Objects.requireNonNull(servers);
        this.facts = Objects.requireNonNull(facts); this.log = Objects.requireNonNull(log);
        servers.worlds((String reference) -> ModWorldKeys.resolve(server, reference));
        dimensions = ModWorldKeys.loaded(server);
        String levelName = server.getWorldData().getLevelName();
        facts.worlds((String reference) -> ModWorlds.resolve(reference, levelName, dimensions));
    }

    void tick() {
        ticks++;
        if (ticks % SnapshotFacts.REFRESH_TICKS == 0) {
            dimensions = ModWorldKeys.loaded(server);
            List<ServerPlayer> online = List.copyOf(server.getPlayerList().getPlayers());
            for (ServerPlayer player : online) refresh(player);
            List<UUID> ids = new java.util.ArrayList<>(online.size());
            for (ServerPlayer player : online) ids.add(player.getUUID());
            facts.server(SnapshotFacts.server(online.size(), server.getPlayerList().getMaxPlayers(), server.getTickCount(), System.currentTimeMillis(), ids));
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
        servers.departed(player.getUUID());
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

    /*
     * World events. Each answers true when a trigger cancels the action; the mixin then stops it and resyncs the client.
     * Nothing is built unless a trigger listens, and players a loader fakes (not in the player list) never publish.
     */

    boolean opening(ServerPlayer player, AbstractContainerMenu menu) {
        return wants(TriggerKind.PLAYER_INVENTORY_OPEN, player)
                && servers.decide(ServerEvent.inventory(TriggerKind.PLAYER_INVENTORY_OPEN, player.getUUID(), inventoryType(menu)));
    }
    void closing(ServerPlayer player) {
        if (wants(TriggerKind.PLAYER_INVENTORY_CLOSE, player))
            servers.publish(ServerEvent.inventory(TriggerKind.PLAYER_INVENTORY_CLOSE, player.getUUID(), inventoryType(player.containerMenu)));
    }
    /** Only clicks vanilla would carry out: the open menu, a living non-spectator, a valid slot. A drag counts once, when it ends. */
    boolean clicking(ServerPlayer player, int containerId, int slot, int button, ContainerInput input) {
        if (!wants(TriggerKind.PLAYER_INVENTORY_CLICK, player)) return false;
        AbstractContainerMenu menu = player.containerMenu;
        if (menu.containerId != containerId || player.isSpectator() || player.isDeadOrDying() || !menu.stillValid(player) || !menu.isValidSlotIndex(slot)) return false;
        if (input == ContainerInput.QUICK_CRAFT && AbstractContainerMenu.getQuickcraftHeader(button) != 2) return false;
        ItemStack item = input == ContainerInput.QUICK_CRAFT ? menu.getCarried() : slot >= 0 ? menu.getSlot(slot).getItem() : ItemStack.EMPTY;
        return servers.decide(ServerEvent.inventoryClick(player.getUUID(), inventoryType(menu), click(input, button), slot < 0 ? -1 : slot, itemKey(item)));
    }
    boolean dropping(ServerPlayer player, boolean all) {
        if (!wants(TriggerKind.PLAYER_ITEM_DROP, player)) return false;
        ItemStack selected = player.getInventory().getSelectedItem();
        if (selected.isEmpty()) return false;
        return servers.decide(ServerEvent.item(TriggerKind.PLAYER_ITEM_DROP, player.getUUID(), itemKey(selected), all ? selected.getCount() : 1));
    }
    /** After the delay and owner checks; a full inventory takes nothing, so it publishes nothing. */
    boolean pickingUp(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty() || !wants(TriggerKind.PLAYER_ITEM_PICKUP, player) || player.isSpectator()) return false;
        if (player.getInventory().getFreeSlot() < 0 && player.getInventory().getSlotWithRemainingSpace(stack) < 0) return false;
        return servers.decide(ServerEvent.item(TriggerKind.PLAYER_ITEM_PICKUP, player.getUUID(), itemKey(stack), stack.getCount()));
    }
    boolean consuming(ServerPlayer player, ItemStack stack) {
        if (!stack.has(DataComponents.CONSUMABLE) || !wants(TriggerKind.PLAYER_ITEM_CONSUME, player)) return false;
        return servers.decide(ServerEvent.item(TriggerKind.PLAYER_ITEM_CONSUME, player.getUUID(), itemKey(stack), 1));
    }
    boolean clickingBlock(ServerPlayer player, boolean left, InteractionHand hand, BlockPos pos) {
        if (!wants(TriggerKind.PLAYER_BLOCK_CLICK, player) || player.isSpectator() || !player.isWithinBlockInteractionRange(pos, 1.0)) return false;
        BlockState state = player.level().getBlockState(pos);
        if (state.isAir()) return false;
        return servers.decide(ServerEvent.blockClick(player.getUUID(), left ? "left" : "right", hand == InteractionHand.OFF_HAND ? "off_hand" : "main_hand",
                blockKey(state.getBlock()), ModWorldKeys.key(player.level()), pos.getX(), pos.getY(), pos.getZ()));
    }
    boolean placing(ServerPlayer player, Block block, BlockPos pos) {
        return wants(TriggerKind.PLAYER_BLOCK_PLACE, player) && servers.decide(ServerEvent.block(TriggerKind.PLAYER_BLOCK_PLACE, player.getUUID(), blockKey(block),
                ModWorldKeys.key(player.level()), pos.getX(), pos.getY(), pos.getZ()));
    }
    boolean breaking(ServerPlayer player, BlockPos pos) {
        if (!wants(TriggerKind.PLAYER_BLOCK_BREAK, player)) return false;
        BlockState state = player.level().getBlockState(pos);
        return !state.isAir() && servers.decide(ServerEvent.block(TriggerKind.PLAYER_BLOCK_BREAK, player.getUUID(), blockKey(state.getBlock()),
                ModWorldKeys.key(player.level()), pos.getX(), pos.getY(), pos.getZ()));
    }
    /** A player taking damage, and a player's own hit on another living entity; either trigger can cancel. Damage vanilla would ignore publishes nothing. */
    boolean hurting(LivingEntity victim, ServerLevel level, DamageSource source, float amount) {
        boolean damaged = victim instanceof ServerPlayer && listens(TriggerKind.PLAYER_DAMAGED);
        boolean dealt = source.getEntity() instanceof ServerPlayer && listens(TriggerKind.PLAYER_DAMAGE_ENTITY);
        if (!damaged && !dealt || victim.isInvulnerableTo(level, source) || victim.isDeadOrDying()) return false;
        boolean cancel = false;
        if (damaged && victim instanceof ServerPlayer player && online(player)) {
            Entity damager = source.getEntity();
            cancel = servers.decide(ServerEvent.damaged(player.getUUID(), damageCause(source), amount, damager == null ? "" : entityKey(damager)));
        }
        if (source.getEntity() instanceof ServerPlayer attacker && source.getDirectEntity() == attacker && attacker != victim
                && dealt && online(attacker)) {
            cancel = servers.decide(ServerEvent.damageEntity(attacker.getUUID(), entityKey(victim), victim.getUUID(), damageCause(source), amount)) || cancel;
        }
        return cancel;
    }
    /** The killer is the player behind the fatal damage, or else the player credited with the kill. */
    void killed(LivingEntity victim, DamageSource source) {
        if (!listens(TriggerKind.PLAYER_KILL)) return;
        ServerPlayer killer = source.getEntity() instanceof ServerPlayer direct ? direct : victim.getKillCredit() instanceof ServerPlayer credited ? credited : null;
        if (killer != null && killer != victim && online(killer)) servers.publish(ServerEvent.kill(killer.getUUID(), entityKey(victim), victim.getUUID()));
    }

    /** A bow or crossbow shot in progress: its projectiles publish as the shot, then as launches. Server thread only. */
    private record Aim(LivingEntity shooter, String bow, InteractionHand hand, double force) {}
    private Aim aim;

    /** A player's bow reports its draw (shot speed is three times the draw); crossbows and mobs report 1. */
    void aiming(LivingEntity shooter, InteractionHand hand, ItemStack weapon, float velocity) {
        aim = listens(TriggerKind.ENTITY_SHOOT_BOW)
                ? new Aim(shooter, itemKey(weapon), hand, shooter instanceof ServerPlayer && weapon.is(Items.BOW) ? velocity / 3.0 : 1) : null;
    }
    void aimed() { aim = null; }

    /** A projectile entering the world; the shot it belongs to decides first, as on Bukkit. */
    boolean launching(Projectile projectile, ServerLevel level) {
        Entity owner = projectile.getOwner();
        Aim shot = aim;
        BlockPos at = projectile.blockPosition();
        if (shot != null && owner == shot.shooter() && listens(TriggerKind.ENTITY_SHOOT_BOW)) {
            BlockPos from = owner.blockPosition();
            if (servers.decide(ServerEvent.shootBow(playerOf(owner), entityKey(owner), owner.getUUID(), shot.bow(), entityKey(projectile), projectile.getUUID(), shot.force(),
                    shot.hand() == InteractionHand.OFF_HAND ? "off_hand" : "main_hand", ModWorldKeys.key(level), from.getX(), from.getY(), from.getZ()))) return true;
        }
        return listens(TriggerKind.PROJECTILE_LAUNCH) && servers.decide(ServerEvent.projectileLaunch(playerOf(owner), entityKey(projectile), projectile.getUUID(),
                owner == null ? "" : entityKey(owner), owner == null ? "" : owner.getUUID().toString(), ModWorldKeys.key(level), at.getX(), at.getY(), at.getZ()));
    }

    /** A projectile hitting an entity or a block; true lets it pass as if nothing was hit. */
    boolean projectileHit(Projectile projectile, HitResult hit) {
        if (hit.getType() == HitResult.Type.MISS || !listens(TriggerKind.PROJECTILE_HIT)) return false;
        Entity owner = projectile.getOwner(), struck = hit instanceof EntityHitResult entityHit ? entityHit.getEntity() : null;
        BlockPos block = hit instanceof BlockHitResult blockHit ? blockHit.getBlockPos() : null, at = block != null ? block : projectile.blockPosition();
        return servers.decide(ServerEvent.projectileHit(playerOf(owner), entityKey(projectile), projectile.getUUID(), owner == null ? "" : entityKey(owner),
                owner == null ? "" : owner.getUUID().toString(), struck == null ? "" : entityKey(struck), struck == null ? "" : struck.getUUID().toString(),
                block == null ? "" : blockKey(projectile.level().getBlockState(block).getBlock()), ModWorldKeys.key(projectile.level()), at.getX(), at.getY(), at.getZ()));
    }

    /** The shooter when it is a listed player; a loader's fake player shoots without one. */
    private Optional<UUID> playerOf(Entity shooter) {
        return shooter instanceof ServerPlayer player && online(player) ? Optional.of(player.getUUID()) : Optional.empty();
    }

    private boolean wants(TriggerKind kind, ServerPlayer player) { return servers.listens(kind) && online(player); }
    private boolean online(ServerPlayer player) { return server.getPlayerList().getPlayer(player.getUUID()) == player; }

    /** Bukkit InventoryType style: chest, dispenser, workbench, player and so on; other mods' menus keep their full key. */
    static String inventoryType(AbstractContainerMenu menu) {
        MenuType<?> type = menu instanceof InventoryMenu ? null : ((ContainerMenuAccess) menu).animus$menuType();
        if (type == null) return "player";
        Identifier key = BuiltInRegistries.MENU.getKey(type);
        if (key == null) return "unknown";
        if (!key.getNamespace().equals("minecraft")) return key.toString();
        return switch (key.getPath()) {
            case "generic_9x1", "generic_9x2", "generic_9x3", "generic_9x4", "generic_9x5", "generic_9x6" -> "chest";
            case "generic_3x3" -> "dispenser";
            case "crafter_3x3" -> "crafter";
            case "crafting" -> "workbench";
            case "enchantment" -> "enchanting";
            case "brewing_stand" -> "brewing";
            case "cartography_table" -> "cartography";
            default -> key.getPath();
        };
    }
    /** Bukkit ClickType style names. */
    static String click(ContainerInput input, int button) {
        return switch (input) {
            case PICKUP -> button == 1 ? "right" : "left";
            case QUICK_MOVE -> button == 1 ? "shift_right" : "shift_left";
            case SWAP -> button == 40 ? "swap_offhand" : "number_key";
            case CLONE -> "middle";
            case THROW -> button == 1 ? "control_drop" : "drop";
            case QUICK_CRAFT -> "drag";
            case PICKUP_ALL -> "double_click";
        };
    }
    private static String damageCause(DamageSource source) {
        return source.typeHolder().unwrapKey().map((ResourceKey<DamageType> key) -> key.identifier())
                .map((Identifier key) -> key.getNamespace().equals("minecraft") ? key.getPath() : key.toString()).orElse("generic");
    }
    private static String itemKey(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }
    private static String blockKey(Block block) { return BuiltInRegistries.BLOCK.getKey(block).toString(); }
    private static String entityKey(Entity entity) { return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(); }

    /** Undoes a client's predicted change to one block, block entity data included. */
    static void resendBlock(ServerPlayer player, BlockPos pos) {
        player.connection.send(new ClientboundBlockUpdatePacket(player.level(), pos));
        BlockEntity entity = player.level().getBlockEntity(pos);
        Packet<ClientGamePacketListener> data = entity == null ? null : entity.getUpdatePacket();
        if (data != null) player.connection.send(data);
    }

    private void refresh(ServerPlayer player) {
        net.minecraft.world.phys.Vec3 at = player.position();
        ItemStack held = player.getMainHandItem();
        facts.player(player.getUUID(), SnapshotFacts.player(player.getGameProfile().name(), player.getUUID(), player.getHealth(), player.getMaxHealth(),
                player.getFoodData().getFoodLevel(), player.experienceLevel, ModWorldKeys.key(player.level()), at.x(), at.y(), at.z(),
                player.gameMode().getName(), server.getPlayerList().isOp(player.nameAndId()), player.isShiftKeyDown(), player.getAbilities().flying,
                BuiltInRegistries.ITEM.getKey(held.getItem()).toString(), held.getCount()));
        servers.position(player.getUUID(), ModWorldKeys.key(player.level()), at.x(), at.y(), at.z());
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
