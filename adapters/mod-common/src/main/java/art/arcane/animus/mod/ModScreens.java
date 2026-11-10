package art.arcane.animus.mod;

import art.arcane.animus.core.platform.MinecraftIds;
import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.PacketKind;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.Economy;
import art.arcane.animus.core.inventory.ItemOps;
import art.arcane.animus.core.inventory.MatchRule;
import art.arcane.animus.core.inventory.TradeInventory;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.platform.ModWorlds;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.toolkit.Toolkit;
import art.arcane.animus.mod.toolkit.ModWand;
import com.mojang.brigadier.ParseResults;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Animus windows on mod servers, as vanilla chest menus that refuse every click (see {@link AnimusMenu}), and shops
 * in the villager trading screen (see {@link AnimusMerchantMenu}). Bound
 * clicks run on the next server tick, outside the click packet. Mod servers have no standard economy API, so money
 * is unavailable and money prices fail closed; item trades work.
 */
final class ModScreens implements ScreenHost<ServerPlayer, ItemStack> {
    static final String NO_ECONOMY = "mod servers have no standard economy API";
    private final MinecraftServer server;
    private final ModTicks ticks;
    private final Economy economy;
    private volatile ScreenManager<ServerPlayer, ItemStack> manager;
    /** Boss bar lanes by player; only the server thread touches them. */
    private final Map<UUID, Map<String, Lane>> bars = new HashMap<>();
    private long tick;
    private final ModPrompts prompts;

    ModScreens(MinecraftServer server, ModTicks ticks, Economy economy) {
        this.server = Objects.requireNonNull(server);
        this.ticks = Objects.requireNonNull(ticks);
        this.economy = Objects.requireNonNull(economy);
        ticks.every(this::expireBars);
        this.prompts = new ModPrompts(ticks);
    }

    void bind(ScreenManager<ServerPlayer, ItemStack> manager) { this.manager = manager; }

    @Override public UUID id(ServerPlayer player) { return player.getUUID(); }
    @Override public String name(ServerPlayer player) { return player.getGameProfile().name(); }
    @Override public Object viewer(ServerPlayer player, String property) {
        return switch (property) {
            case "world", "world_key" -> ModWorldKeys.key(player.level());
            case "health" -> (double) player.getHealth();
            case "level" -> player.experienceLevel;
            case "food" -> player.getFoodData().getFoodLevel();
            case "sneaking" -> player.isShiftKeyDown();
            case "x" -> player.getX();
            case "y" -> player.getY();
            case "z" -> player.getZ();
            case "server.online" -> server.getPlayerCount();
            case "server.max" -> server.getMaxPlayers();
            default -> null;
        };
    }
    @Override public boolean permitted(ServerPlayer player, String node) { return VanillaRuntimeHost.permitted(player.permissions(), node); }
    @Override public boolean takeLevels(ServerPlayer player, int levels) {
        if (player.experienceLevel < levels) return false;
        player.giveExperienceLevels(-levels);
        return true;
    }
    @Override public void giveLevels(ServerPlayer player, int levels) { player.giveExperienceLevels(levels); }

    @Override public Optional<Object> show(ServerPlayer player, ScreenFrame frame, Object window) {
        if (frame.merchant().isPresent()) return merchant(player, frame, window);
        if (window instanceof Trading trading && player.containerMenu == trading.menu) trading.menu.replaced = true;
        if (frame.width() != 9) return Optional.empty();
        if (window instanceof Window open && player.containerMenu == open.menu && open.title.equals(frame.title()) && open.menu.resolution().equals(frame.resolution())) {
            fill(open.menu, frame);
            open.menu.broadcastChanges();
            return Optional.of(open);
        }
        if (window instanceof Window open && player.containerMenu == open.menu) open.menu.replaced = true;
        Window next = new Window(frame.title());
        OptionalInt id = player.openMenu(new SimpleMenuProvider((int container, Inventory inventory, net.minecraft.world.entity.player.Player owner) -> {
            AnimusMenu menu = AnimusMenu.create(container, inventory, frame.rows(), frame.resolution(), (AnimusMenu clicked, int slot, GuiModel.Click click) -> clicked(player, next, slot, click),
                    (AnimusMenu closed, net.minecraft.world.entity.player.Player who) -> closed(player, next));
            next.menu = menu;
            fill(menu, frame);
            return menu;
        }, ModText.component(frame.title())));
        if (id.isEmpty() || next.menu == null) return Optional.empty();
        AnimusMetrics metrics = AnimusMetrics.current();
        metrics.sent(metrics.viewer(player.getUUID(), player.getGameProfile().name()), null, PacketKind.GUI_WINDOW, 1);
        return Optional.of(next);
    }

    private static final class Window {
        final String title;
        AnimusMenu menu;
        Window(String title) { this.title = title; }
    }

    /** One open trading screen. */
    private static final class Trading {
        final String title;
        AnimusMerchantMenu menu;
        Trading(String title) { this.title = title; }
    }

    /** Sends new offers in place when only the offers changed; otherwise opens a new trading screen. */
    private Optional<Object> merchant(ServerPlayer player, ScreenFrame frame, Object window) {
        List<AnimusMerchantMenu.Offer> offers = new ArrayList<>();
        for (ScreenFrame.Offer offer : frame.merchant().orElseThrow().offers())
            offers.add(new AnimusMerchantMenu.Offer(trade(offer.costA()), offer.costB().map(this::trade).orElse(ItemStack.EMPTY), trade(offer.result()), offer.uses(), offer.maxUses()));
        if (window instanceof Trading open && player.containerMenu == open.menu && open.title.equals(frame.title())) {
            open.menu.offers(offers);
            player.sendMerchantOffers(open.menu.containerId, open.menu.offers(), 0, 0, false, false);
            return Optional.of(open);
        }
        if (window instanceof Trading open && player.containerMenu == open.menu) open.menu.replaced = true;
        if (window instanceof Window open && player.containerMenu == open.menu) open.menu.replaced = true;
        Trading next = new Trading(frame.title());
        OptionalInt id = player.openMenu(new SimpleMenuProvider((int container, Inventory inventory, net.minecraft.world.entity.player.Player owner) -> {
            AnimusMerchantMenu menu = AnimusMerchantMenu.create(container, inventory, offers,
                    (AnimusMerchantMenu clicked, int offer, GuiModel.Click click) -> clicked(player, next, offer, click),
                    (AnimusMerchantMenu closed, net.minecraft.world.entity.player.Player who) -> closed(player, next));
            next.menu = menu;
            return menu;
        }, ModText.component(frame.title())));
        if (id.isEmpty() || next.menu == null) return Optional.empty();
        player.sendMerchantOffers(id.getAsInt(), next.menu.offers(), 0, 0, false, false);
        AnimusMetrics metrics = AnimusMetrics.current();
        metrics.sent(metrics.viewer(player.getUUID(), player.getGameProfile().name()), null, PacketKind.GUI_WINDOW, 1);
        return Optional.of(next);
    }

    /** A display stack for the trading screen, at most one stack. */
    private ItemStack trade(ScreenFrame.Item item) {
        ItemStack stack = display(item);
        return stack.isEmpty() ? stack : stack.copyWithCount(Math.clamp(stack.getCount(), 1, Math.max(1, stack.getMaxStackSize())));
    }

    private void clicked(ServerPlayer player, Object window, int slot, GuiModel.Click click) {
        ScreenManager<ServerPlayer, ItemStack> current = manager;
        if (current != null) ticks.later(() -> { if (!player.hasDisconnected()) current.click(player, window, slot, click); });
    }

    private void closed(ServerPlayer player, Object window) {
        ScreenManager<ServerPlayer, ItemStack> current = manager;
        if (current != null) current.closed(player, window);
    }

    private void fill(AnimusMenu menu, ScreenFrame frame) {
        for (int slot = 0; slot < menu.windowSize(); slot++) {
            ScreenFrame.Item item = frame.items().get(slot);
            menu.show(slot, item == null ? ItemStack.EMPTY : display(item));
        }
    }

    /** The stack a display item shows; an invalid item shows a barrier. */
    ItemStack display(ScreenFrame.Item item) {
        return stack(item.item(), item.name(), item.lore(), new ArrayList<>())
                .orElseGet(() -> stack(new NpcItem("minecraft:barrier", 1, Optional.empty(), Optional.empty()), item.name(), item.lore(), new ArrayList<>()).orElse(ItemStack.EMPTY));
    }

    @Override public void close(ServerPlayer player, Object window) {
        if (window instanceof Window open && player.containerMenu == open.menu) {
            open.menu.replaced = true;
            player.closeContainer();
        }
        if (window instanceof Trading open && player.containerMenu == open.menu) {
            open.menu.replaced = true;
            player.closeContainer();
        }
    }

    @Override public boolean prompt(ServerPlayer player, Prompt prompt) { return prompts.open(player, prompt); }

    /** A chat line; true when it answered a text prompt and must not be broadcast. */
    boolean typed(ServerPlayer player, String line) { return prompts.chatted(player, line); }

    /** The player left or died: a waiting text prompt is cancelled. */
    void forget(ServerPlayer player) { prompts.forget(player); }

    @Override public void message(ServerPlayer player, String text) { player.sendSystemMessage(ModText.component(text)); }
    @Override public void actionBar(ServerPlayer player, String text) { player.sendSystemMessage(ModText.component(text), true); }
    @Override public void title(ServerPlayer player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
        if (!subtitle.isEmpty()) player.connection.send(new ClientboundSetSubtitleTextPacket(ModText.component(subtitle)));
        player.connection.send(new ClientboundSetTitleTextPacket(ModText.component(title)));
    }
    @Override public void sound(ServerPlayer player, String sound, String source, float volume, float pitch) {
        Identifier id = Identifier.tryParse(MinecraftIds.normalize(sound));
        if (id == null) return;
        Holder<SoundEvent> event = BuiltInRegistries.SOUND_EVENT.get(id).<Holder<SoundEvent>>map((Holder.Reference<SoundEvent> reference) -> reference)
                .orElseGet(() -> Holder.direct(SoundEvent.createVariableRangeEvent(id)));
        SoundSource category = SoundSource.MASTER;
        for (SoundSource candidate : SoundSource.values()) if (candidate.getName().equals(source.toLowerCase(Locale.ROOT))) category = candidate;
        player.connection.send(new ClientboundSoundPacket(event, category, player.getX(), player.getY(), player.getZ(), volume, pitch, player.getRandom().nextLong()));
    }
    @Override public void command(Optional<ServerPlayer> player, String command) {
        CommandSourceStack source = player.map(ServerPlayer::createCommandSourceStack).orElseGet(server::createCommandSourceStack);
        Commands commands = server.getCommands();
        ParseResults<CommandSourceStack> parsed = commands.getDispatcher().parse(command, source);
        commands.performCommand(parsed, command);
    }
    @Override public void broadcast(String text) { server.getPlayerList().broadcastSystemMessage(ModText.component(text), false); }

    @Override public Optional<Pose> pose(ServerPlayer player) {
        Set<String> worlds = ModWorlds.aliases(ModWorldKeys.key(player.level()), server.getWorldData().getLevelName());
        return Optional.of(new Pose(worlds, player.getX(), player.getY(), player.getZ(), player.getEyeY(), player.getYRot(), player.getXRot(), player.isShiftKeyDown()));
    }

    @Override public boolean teleport(ServerPlayer player, String world, double x, double y, double z, float yaw, float pitch) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return false;
        Optional<String> key = ModWorldKeys.resolve(server, world);
        ServerLevel level = key.map((String resolved) -> ModWorldKeys.level(server, resolved)).orElse(null);
        return level != null && player.teleportTo(level, x, y, z, Set.of(), yaw, pitch, true);
    }

    @Override public void bossBar(ServerPlayer player, String lane, String title, float progress, String color, String style, int ticks) {
        Map<String, Lane> lanes = bars.computeIfAbsent(player.getUUID(), (UUID ignored) -> new HashMap<>());
        Lane previous = lanes.remove(lane);
        if (previous != null) previous.bar().removePlayer(player);
        if (ticks <= 0) return;
        BossEvent.BossBarColor barColor = BossEvent.BossBarColor.WHITE;
        for (BossEvent.BossBarColor candidate : BossEvent.BossBarColor.values()) if (candidate.name().equalsIgnoreCase(color)) barColor = candidate;
        BossEvent.BossBarOverlay overlay = switch (style.toLowerCase(Locale.ROOT)) {
            case "segmented_6" -> BossEvent.BossBarOverlay.NOTCHED_6;
            case "segmented_10" -> BossEvent.BossBarOverlay.NOTCHED_10;
            case "segmented_12" -> BossEvent.BossBarOverlay.NOTCHED_12;
            case "segmented_20" -> BossEvent.BossBarOverlay.NOTCHED_20;
            default -> BossEvent.BossBarOverlay.PROGRESS;
        };
        ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), VanillaDisplayTransport.component(title), barColor, overlay);
        bar.setProgress(Math.clamp(progress, 0f, 1f));
        bar.addPlayer(player);
        lanes.put(lane, new Lane(bar, player, tick + ticks));
    }
    private record Lane(ServerBossEvent bar, ServerPlayer player, long until) {}

    private void expireBars() {
        tick++;
        if (bars.isEmpty()) return;
        for (java.util.Iterator<Map.Entry<UUID, Map<String, Lane>>> players = bars.entrySet().iterator(); players.hasNext(); ) {
            Map<String, Lane> lanes = players.next().getValue();
            lanes.values().removeIf((Lane lane) -> {
                boolean gone = lane.player().hasDisconnected();
                if (gone || lane.until() <= tick) { lane.bar().removeAllPlayers(); return true; }
                return false;
            });
            if (lanes.isEmpty()) players.remove();
        }
    }

    @Override public boolean effect(ServerPlayer player, String effect, int ticks, int amplifier) {
        Identifier id = Identifier.tryParse(MinecraftIds.normalize(effect));
        Optional<Holder.Reference<MobEffect>> type = id == null ? Optional.empty() : BuiltInRegistries.MOB_EFFECT.get(id);
        return type.isPresent() && player.addEffect(new MobEffectInstance(type.get(), ticks, amplifier), null);
    }

    @Override public boolean particle(ServerPlayer player, String particle, int count, double spread, double x, double y, double z) {
        Identifier id = Identifier.tryParse(MinecraftIds.normalize(particle));
        if (id == null || !BuiltInRegistries.PARTICLE_TYPE.containsKey(id)) return false;
        if (!(BuiltInRegistries.PARTICLE_TYPE.getValue(id) instanceof SimpleParticleType simple)) return false;
        return player.level().sendParticles(player, simple, false, false, x, y, z, count, spread, spread, spread, 0);
    }

    @Override public void broadcast(ServerPlayer source, String text, String scope, double radius, Optional<String> permission) {
        Component message = Component.literal(text);
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (!scope.equals("server") && online.level() != source.level()) continue;
            if (scope.equals("radius") && online.distanceToSqr(source) > radius * radius) continue;
            if (permission.isPresent() && !permitted(online, permission.get())) continue;
            online.sendSystemMessage(message);
        }
    }

    @Override public TradeInventory<ItemStack> inventory(ServerPlayer player) { return storage(player); }

    /** The hotbar and three storage rows; worn armour and the off hand are never touched. */
    static TradeInventory<ItemStack> storage(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        return new TradeInventory<>() {
            @Override public int size() { return 36; }
            @Override public ItemStack get(int slot) { ItemStack stack = inventory.getItem(slot); return stack.isEmpty() ? null : stack.copy(); }
            @Override public void set(int slot, ItemStack stack) { inventory.setItem(slot, stack == null ? ItemStack.EMPTY : stack); }
        };
    }
    @Override public ItemOps<ItemStack> items() { return VanillaItems.INSTANCE; }

    @Override public Optional<ItemStack> stack(NpcItem item, Optional<String> name, List<String> lore, List<String> errors) {
        ItemStack stack = VanillaRuntimeHost.stack(server, item, "$.item", errors);
        if (stack == null) return Optional.empty();
        name.ifPresent((String text) -> stack.set(DataComponents.CUSTOM_NAME, plain(text)));
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (String line : lore) lines.add(plain(line));
            stack.set(DataComponents.LORE, new ItemLore(lines));
        }
        return Optional.of(stack);
    }

    private static Component plain(String text) { return ModText.component(text, Style.EMPTY.withItalic(false)); }

    @Override public void drop(ServerPlayer player, ItemStack stack) {
        ItemEntity entity = new ItemEntity(player.level(), player.getX(), player.getEyeY() - 0.3, player.getZ(), stack);
        player.level().addFreshEntity(entity);
    }

    @Override public Economy economy() { return economy; }

    @Override public Optional<NpcItem> worn(ServerPlayer player, EquipmentSlot slot) {
        if (slot == EquipmentSlot.BODY || slot == EquipmentSlot.SADDLE) return Optional.empty();
        return ModRecordings.item(server, player.getItemBySlot(ModRecordings.vanilla(slot)));
    }
    @Override public boolean holdsWand(ServerPlayer player) { return ModWand.is(player.getMainHandItem()); }
    @Override public boolean giveWand(ServerPlayer player) {
        Optional<ItemStack> wand = stack(Toolkit.WAND_ITEM, Optional.of(Toolkit.WAND_NAME), Toolkit.WAND_LORE, new ArrayList<>());
        if (wand.isEmpty()) return false;
        ItemStack marked = ModWand.mark(wand.get());
        if (!player.getInventory().add(marked)) drop(player, marked);
        return true;
    }
    @Override public void chat(ServerPlayer player, ChatLine line) {
        player.sendSystemMessage(line.interactive() ? ModText.adventure(line.component()) : ModText.component(line.console()));
    }
    @Override public void parts(ServerPlayer player, List<art.arcane.animus.core.text.TextPart> parts) { player.sendSystemMessage(ModText.parts(parts)); }
    @Override public boolean experience(ServerPlayer player, int points) { player.giveExperiencePoints(points); return true; }

    /** Server shutdown: closes every Animus window on the server thread. */
    void closeAll() {
        prompts.closeAll(server.getPlayerList()::getPlayer);
        ScreenManager<ServerPlayer, ItemStack> current = manager;
        if (current == null) return;
        for (UUID id : current.clear()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null && player.containerMenu instanceof AnimusMenu menu) { menu.replaced = true; player.closeContainer(); }
            if (player != null && player.containerMenu instanceof AnimusMerchantMenu menu) { menu.replaced = true; player.closeContainer(); }
        }
    }

    /** Vanilla stacks. */
    static final class VanillaItems implements ItemOps<ItemStack> {
        static final VanillaItems INSTANCE = new VanillaItems();
        @Override public boolean empty(ItemStack stack) { return stack == null || stack.isEmpty(); }
        @Override public int count(ItemStack stack) { return empty(stack) ? 0 : stack.getCount(); }
        @Override public ItemStack withCount(ItemStack stack, int count) { return stack.copyWithCount(count); }
        @Override public int maxStackSize(ItemStack stack) { return Math.max(1, stack.getMaxStackSize()); }
        @Override public boolean matches(ItemStack held, ItemStack wanted, MatchRule rule) {
            if (empty(held) || wanted == null || !ItemStack.isSameItem(held, wanted)) return false;
            return switch (rule) {
                case MATERIAL -> true;
                case STRICT -> ItemStack.isSameItemSameComponents(held, wanted);
                case LEGACY -> Objects.equals(held.get(DataComponents.DAMAGE), wanted.get(DataComponents.DAMAGE))
                        && Objects.equals(held.get(DataComponents.CUSTOM_NAME), wanted.get(DataComponents.CUSTOM_NAME))
                        && Objects.equals(held.get(DataComponents.LORE), wanted.get(DataComponents.LORE))
                        && Objects.equals(held.get(DataComponents.ENCHANTMENTS), wanted.get(DataComponents.ENCHANTMENTS))
                        && Objects.equals(held.get(DataComponents.POTION_CONTENTS), wanted.get(DataComponents.POTION_CONTENTS))
                        && Objects.equals(held.get(DataComponents.WRITABLE_BOOK_CONTENT), wanted.get(DataComponents.WRITABLE_BOOK_CONTENT))
                        && Objects.equals(held.get(DataComponents.WRITTEN_BOOK_CONTENT), wanted.get(DataComponents.WRITTEN_BOOK_CONTENT));
            };
        }
        @Override public String describe(ItemStack stack, int count) { return count + "x " + stack.getHoverName().getString(); }
    }

}
