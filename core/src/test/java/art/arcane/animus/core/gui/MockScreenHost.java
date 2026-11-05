package art.arcane.animus.core.gui;

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
        public final List<String> commands = new ArrayList<>();
        public final List<Stack> dropped = new ArrayList<>();
        public ScreenFrame frame;
        public Object window;
        public int opened, closed;
        public Player(String name) { this.name = name; this.id = UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    }

    public Economy economy = Economy.none("no economy");
    public final List<String> console = new ArrayList<>();
    public final List<String> broadcasts = new ArrayList<>();
    public Set<String> unsupported = Set.of();

    @Override public UUID id(Player player) { return player.id; }
    @Override public String name(Player player) { return player.name; }
    @Override public Object viewer(Player player, String property) { return player.facts.get(property); }
    @Override public boolean permitted(Player player, String node) { return player.permissions.contains(node) || player.permissions.contains("*"); }

    @Override public Optional<Object> show(Player player, ScreenFrame frame, Object window) {
        if (unsupported.contains(frame.resolution())) return Optional.empty();
        if (window != null && window == player.window && frame.sameWindow(player.frame)) { player.frame = frame; return Optional.of(window); }
        player.opened++;
        player.frame = frame;
        player.window = new Object();
        return Optional.of(player.window);
    }

    @Override public void close(Player player, Object window) {
        if (window != null && window == player.window) { player.window = null; player.frame = null; player.closed++; }
    }

    @Override public void message(Player player, String text) { player.messages.add(RichText.plain(text)); }
    @Override public void actionBar(Player player, String text) { player.messages.add("bar:" + RichText.plain(text)); }
    @Override public void title(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut) { player.messages.add("title:" + RichText.plain(title)); }
    @Override public void sound(Player player, String sound, String source, float volume, float pitch) { player.messages.add("sound:" + sound); }
    @Override public void command(Optional<Player> player, String command) {
        if (player.isPresent()) player.get().commands.add(command); else console.add(command);
    }
    @Override public void broadcast(String text) { broadcasts.add(RichText.plain(text)); }
    @Override public TradeInventory<Stack> inventory(Player player) { return player.inventory; }
    @Override public ItemOps<Stack> items() { return MockInventory.OPS; }
    @Override public Optional<Stack> stack(NpcItem item, Optional<String> name, List<String> lore, List<String> errors) {
        if (item.id().equals("minecraft:missing")) { errors.add("unknown item"); return Optional.empty(); }
        return Optional.of(new Stack(item.id(), item.count(), name.map(RichText::plain).orElse(""), item.components().orElse("")));
    }
    @Override public void drop(Player player, Stack stack) { player.dropped.add(stack); }
    @Override public Economy economy() { return economy; }

    /** The slot of the first item whose plain name contains the text. */
    public static int slot(Player player, String name) {
        for (Map.Entry<Integer, ScreenFrame.Item> item : player.frame.items().entrySet())
            if (item.getValue().name().map(RichText::plain).orElse("").contains(name)) return item.getKey();
        throw new AssertionError("no item named " + name + " in " + player.frame.items());
    }
}
