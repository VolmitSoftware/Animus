package art.arcane.animus.core.gui;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.inventory.Economy;
import art.arcane.animus.core.inventory.ItemOps;
import art.arcane.animus.core.inventory.MockInventory;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.inventory.TradeInventory;
import art.arcane.animus.core.npc.NpcItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** A server with one kind of player, recording everything screens ask it to do. */
public final class MockScreenHost implements ScreenHost<MockScreenHost.Player, Stack> {
    public static final class Player {
        public final UUID id;
        public final String name;
        public final MockInventory inventory = new MockInventory();
        public final Set<String> permissions = new HashSet<>();
        public final Map<String, Object> facts = new HashMap<>();
        public final List<String> messages = new ArrayList<>();
        /** Every chat line with its chips, as sent; {@link #messages} holds its console text too. */
        public final List<art.arcane.animus.core.text.ChatLine> lines = new ArrayList<>();
        public final List<String> commands = new ArrayList<>();
        public final List<Stack> dropped = new ArrayList<>();
        public int levels;
        public ScreenFrame frame;
        public Object window;
        public ScreenHost.Pose pose;
        public final List<String> effects = new ArrayList<>();
        public int opened, closed, experience;
        /** The title of every window opened, in order; a screen that replaces its window in place adds nothing. */
        public final List<String> windows = new ArrayList<>();
        /** Whether the main hand holds a wand, and how many wands the host has handed out. */
        public boolean wand;
        public int wands;
        /** Sounds played to the player, as {@code sound@volume}. */
        public final List<String> sounds = new ArrayList<>();
        /** The text prompt waiting for an answer, or null. Tests answer it through {@code prompt.answer()}. */
        public ScreenHost.Prompt prompt;
        /** True makes the host refuse prompts, as a platform without text input does. */
        public boolean noPrompt;
        /** What the player holds and wears, read by {@code worn}. */
        public final Map<art.arcane.animus.core.npc.EquipmentSlot, NpcItem> worn = new java.util.EnumMap<>(art.arcane.animus.core.npc.EquipmentSlot.class);
        public Player(String name) { this.name = name; this.id = UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    }

    public Economy economy = Economy.none("no economy");
    /** What {@code stock} answers for a container shop; refused by default, as on a platform without container shops. */
    public java.util.function.Function<art.arcane.animus.core.shop.ShopDocument, art.arcane.animus.core.shop.ShopStock<Stack>> stocks =
            (art.arcane.animus.core.shop.ShopDocument shop) -> art.arcane.animus.core.shop.ShopStock.refused("this platform has no container shops");
    public final List<String> console = new ArrayList<>();
    public final List<String> broadcasts = new ArrayList<>();
    public Set<String> unsupported = Set.of();

    @Override public UUID id(Player player) { return player.id; }
    @Override public art.arcane.animus.core.shop.ShopStock<Stack> stock(art.arcane.animus.core.shop.ShopDocument shop) { return stocks.apply(shop); }
    /** Why a player may not keep shop stock in a block; empty lets them. */
    public Optional<String> chestRefusal = Optional.empty();
    /** The container blocks players opened, as {@code world x y z}, and how many shop items the host handed out. */
    public final List<String> chestsOpened = new ArrayList<>();
    public int shopItems;
    @Override public Optional<String> stockRefusal(Player player, art.arcane.animus.core.shop.ShopDocument.Container container) { return chestRefusal; }
    @Override public Optional<String> openContainer(Player player, art.arcane.animus.core.shop.ShopDocument.Container container) {
        if (chestRefusal.isPresent()) return chestRefusal;
        chestsOpened.add(container.world() + " " + container.x() + " " + container.y() + " " + container.z());
        return Optional.empty();
    }
    @Override public WandGift giveShopItem(Player player) { shopItems++; return WandGift.GIVEN; }
    /** Whether this host keeps shop stock in chests, as Bukkit does; tests of a mod server turn it off. */
    public boolean containerShops = true;
    @Override public boolean containerShops() { return containerShops; }
    /** The shop chests that are gone, as {@code world x y z}. */
    public final Set<String> gone = new HashSet<>();
    @Override public boolean containerGone(art.arcane.animus.core.shop.ShopDocument.Container container) {
        return gone.contains(container.world() + " " + container.x() + " " + container.y() + " " + container.z());
    }
    @Override public String name(Player player) { return player.name; }
    @Override public Object viewer(Player player, String property) { return player.facts.get(property); }
    @Override public boolean permitted(Player player, String node) { return player.permissions.contains("*") || PermissionNodes.granted(player.permissions::contains, node); }
    @Override public boolean takeLevels(Player player, int levels) {
        if (player.levels < levels) return false;
        player.levels -= levels;
        return true;
    }
    @Override public void giveLevels(Player player, int levels) { player.levels += levels; }

    /** What {@code unshowable} answers: by default every frame shows. */
    public java.util.function.Function<ScreenFrame, Optional<String>> unshowable = (ScreenFrame frame) -> Optional.empty();
    @Override public Optional<String> unshowable(ScreenFrame frame) { return unshowable.apply(frame); }
    @Override public Optional<Object> show(Player player, ScreenFrame frame, Object window) {
        if (unsupported.contains(frame.resolution())) return Optional.empty();
        if (window != null && window == player.window && frame.sameWindow(player.frame)) { player.frame = frame; return Optional.of(window); }
        player.opened++;
        player.windows.add(RichText.plain(frame.title()));
        player.frame = frame;
        player.window = new Object();
        return Optional.of(player.window);
    }

    @Override public void close(Player player, Object window) {
        if (window != null && window == player.window) { player.window = null; player.frame = null; player.closed++; }
    }

    @Override public void message(Player player, String text) { player.messages.add(RichText.plain(text)); }
    @Override public void chat(Player player, art.arcane.animus.core.text.ChatLine line) { player.lines.add(line); message(player, line.console()); }
    @Override public void actionBar(Player player, String text) { player.messages.add("bar:" + RichText.plain(text)); }
    @Override public void title(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut) { player.messages.add("title:" + RichText.plain(title)); }
    @Override public void sound(Player player, String sound, String source, float volume, float pitch) { player.sounds.add(sound + "@" + volume); }
    @Override public void command(Optional<Player> player, String command) {
        if (player.isPresent()) player.get().commands.add(command); else console.add(command);
    }
    @Override public void broadcast(String text) { broadcasts.add(RichText.plain(text)); }
    @Override public Optional<ScreenHost.Pose> pose(Player player) { return Optional.ofNullable(player.pose); }
    @Override public boolean teleport(Player player, String world, double x, double y, double z, float yaw, float pitch) {
        if (world.equals("missing:world")) return false;
        player.effects.add("teleport:" + world + ":" + (int) x + "," + (int) y + "," + (int) z);
        if (player.pose != null) player.pose = new ScreenHost.Pose(Set.of(world), x, y, z, y + 1.62, yaw, pitch, player.pose.sneaking());
        return true;
    }
    @Override public void bossBar(Player player, String lane, String title, float progress, String color, String style, int ticks) {
        player.effects.add("bossbar:" + lane + ":" + RichText.plain(title) + ":" + progress + ":" + color + ":" + style + ":" + ticks);
    }
    @Override public boolean effect(Player player, String effect, int ticks, int amplifier) {
        if (effect.endsWith("unknown")) return false;
        player.effects.add("effect:" + effect + ":" + ticks + ":" + amplifier);
        return true;
    }
    @Override public boolean particle(Player player, String particle, int count, double spread, double x, double y, double z) {
        player.effects.add("particle:" + particle + ":" + count + "@" + Math.round(x) + "," + Math.round(y) + "," + Math.round(z));
        return true;
    }
    @Override public void broadcast(Player source, String text, String scope, double radius, Optional<String> permission) {
        broadcasts.add(scope + (permission.map((String node) -> "[" + node + "]").orElse("")) + ":" + RichText.plain(text));
    }
    @Override public TradeInventory<Stack> inventory(Player player) { return player.inventory; }
    @Override public ItemOps<Stack> items() { return MockInventory.OPS; }
    @Override public Optional<Stack> stack(NpcItem item, Optional<String> name, List<String> lore, List<String> errors) {
        if (item.id().equals("minecraft:missing")) { errors.add("unknown item"); return Optional.empty(); }
        return Optional.of(new Stack(item.id(), item.count(), name.map(RichText::plain).orElse(""), item.components().orElse("")));
    }
    @Override public void drop(Player player, Stack stack) { player.dropped.add(stack); }
    @Override public Economy economy() { return economy; }
    @Override public Optional<NpcItem> worn(Player player, art.arcane.animus.core.npc.EquipmentSlot slot) { return Optional.ofNullable(player.worn.get(slot)); }
    @Override public void parts(Player player, List<art.arcane.animus.core.text.TextPart> parts) {
        StringBuilder line = new StringBuilder("parts:");
        for (art.arcane.animus.core.text.TextPart part : parts) line.append(RichText.plain(part.text())).append(part.command().map((String command) -> "<" + command + ">").orElse(""));
        player.messages.add(line.toString());
    }
    @Override public boolean experience(Player player, int points) { player.experience += points; return true; }
    @Override public boolean holdsWand(Player player) { return player.wand; }
    @Override public ScreenHost.WandGift giveWand(Player player) {
        if (player.wand) return ScreenHost.WandGift.MOVED;
        player.wand = true;
        player.wands++;
        return ScreenHost.WandGift.GIVEN;
    }
    @Override public boolean prompt(Player player, ScreenHost.Prompt prompt) {
        if (player.noPrompt) return false;
        player.prompt = prompt;
        player.messages.add("prompt:" + prompt.label());
        return true;
    }

    /** Types the answer into the waiting prompt, or cancels it with empty. */
    public static void answer(Player player, Optional<String> text) {
        ScreenHost.Prompt waiting = player.prompt;
        if (waiting == null) throw new AssertionError("no prompt is waiting for " + player.name);
        player.prompt = null;
        waiting.answer().accept(text);
    }

    /**
     * The lowest slot whose item's plain name contains the text. Frames keep no slot order, so a name that also appears
     * in a notice would otherwise pick either item from run to run.
     */
    public static int slot(Player player, String name) {
        for (Map.Entry<Integer, ScreenFrame.Item> item : new java.util.TreeMap<>(player.frame.items()).entrySet())
            if (item.getValue().name().map(RichText::plain).orElse("").contains(name)) return item.getKey();
        throw new AssertionError("no item named " + name + " in " + player.frame.items());
    }
}
