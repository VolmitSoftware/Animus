package example.animus.npcdemo;

import art.arcane.animus.api.Audience;
import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.DefinitionSnapshot;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.ItemSpec;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.NpcLocation;
import art.arcane.animus.api.NpcModules;
import art.arcane.animus.api.NpcService;
import art.arcane.animus.api.NpcSpec;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.bukkit.api.AnimusNpcs;
import art.arcane.animus.bukkit.api.BukkitNpcs;
import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.concurrent.CompletionStage;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/**
 * Drives Animus NPCs through the public API only:
 * <ul>
 *   <li>{@code /npcdemo walk}: a temporary NPC appears beside you, walks six blocks ahead and says so when it arrives</li>
 *   <li>a left click on it is cancelled; a right click is answered and counted in its {@code npcdemo:clicks} module</li>
 *   <li>{@code /npcdemo bench}: a persistent NPC is saved beside you; {@code /npcdemo sit} seats you on it</li>
 *   <li>{@code /npcdemo tp <walker|bench> <x> <y> <z>} teleports one of them, anywhere in its world</li>
 *   <li>{@code /npcdemo shout} has the walker call out to every player within 128 blocks</li>
 *   <li>{@code /npcdemo count} reads the click count back; {@code /npcdemo remove} removes both NPCs</li>
 * </ul>
 * Messages go out with {@code Player.sendMessage}, which Paper and Folia accept from any thread, so result callbacks
 * on Animus's worker threads never touch the world.
 */
public final class NpcDemo extends JavaPlugin implements Listener {
    private static final String CLICKS = "npcdemo:clicks", BENCH = "npcdemo-bench";
    private NpcService npcs;
    private volatile NpcHandle walker, bench;

    @Override public void onEnable() {
        AnimusNpcs animus = getServer().getServicesManager().load(AnimusNpcs.class);
        if (animus == null) { getLogger().severe("Animus is not running"); getServer().getPluginManager().disablePlugin(this); return; }
        npcs = animus.service(this);
        Outcome<Capabilities.Key> registered = npcs.modules().register(new NpcModules.Descriptor(new Capabilities.Key("npcdemo", "clicks"), "Counts right clicks", 0), new ClickCounter());
        getLogger().info("Module " + CLICKS + ": " + registered.status());
        getServer().getPluginManager().registerEvents(this, this);
    }

    /** Counts right clicks in the NPC's own definition data. */
    private final class ClickCounter implements NpcModules.Module {
        @Override public List<Diagnostic> validate(String data) {
            JsonElement count = JsonParser.parseString(data).getAsJsonObject().get("count");
            boolean whole = count != null && count.isJsonPrimitive() && count.getAsJsonPrimitive().isNumber() && count.getAsDouble() >= 0 && count.getAsDouble() == Math.rint(count.getAsDouble());
            return whole ? List.of() : List.of(new Diagnostic(Diagnostic.Severity.ERROR, "count", "$.count", "expected a whole number of clicks"));
        }
        @Override public void spawned(NpcModules.Context npc) { getLogger().info(npc.id() + " is active with " + npc.data()); }
        @Override public void clicked(NpcModules.Context npc, UUID player, boolean attack) {
            if (attack) return;
            npcs.updateModule(npc.npc(), CLICKS, (Optional<String> data) -> Optional.of("{\"count\":" + (count(data) + 1) + "}"))
                    .thenAccept((Outcome<DefinitionSnapshot> saved) -> getLogger().info(npc.id() + " click saved: " + saved.status()));
        }
    }

    /** Cancels left clicks on the demo NPCs; right clicks go on to the module and are answered here. */
    @EventHandler public void clicked(VirtualNpcInteractionEvent event) {
        NpcHandle clicked = new NpcHandle(event.getNpcId());
        if (!clicked.equals(walker) && !clicked.equals(bench)) return;
        Player player = event.getViewer();
        if (event.getAction() == VirtualNpcInteractionEvent.Action.ATTACK) {
            event.setCancelled(true);
            report(player, "NpcDemo cancelled that left click");
            return;
        }
        npcs.speak(clicked, "Hello " + player.getName() + ", thanks for the click", Audience.player(player.getUniqueId()), NpcService.Speech.BOTH)
                .thenAccept((Outcome<Integer> said) -> getLogger().info("Answered a right click: " + said.status()));
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        String action = arguments.length == 0 ? "" : arguments[0];
        if (action.equals("remove")) { remove(sender); return true; }
        if (action.equals("count")) { count(sender); return true; }
        if (!(sender instanceof Player player)) { sender.sendMessage("Run this as a player"); return true; }
        switch (action) {
            case "walk" -> walk(player);
            case "bench" -> bench(player);
            case "sit" -> sit(player);
            case "tp" -> teleport(player, arguments);
            case "shout" -> shout(player);
            default -> sender.sendMessage("/npcdemo <walk|bench|sit|tp|shout|remove|count>");
        }
        return true;
    }

    /** A temporary NPC beside the player walks six blocks ahead of them, then speaks. */
    private void walk(Player player) {
        Location here = player.getLocation();
        Vector ahead = here.getDirection().setY(0).normalize();
        Location start = here.clone().add(ahead.getZ() * -2, 0, ahead.getX() * 2);
        start.setYaw(here.getYaw());
        Location goal = start.clone().add(ahead.clone().multiply(6));
        NpcSpec spec = NpcSpec.builder().name("&bDemo walker").location(BukkitNpcs.location(start))
                .equip(NpcSpec.Slot.MAIN_HAND, ItemSpec.of("minecraft:compass")).module(CLICKS, "{\"count\":0}").build();
        npcs.createTemporary(spec).thenCompose((Outcome<DefinitionSnapshot> created) -> {
            if (created.status() != Outcome.Status.OK) { player.sendMessage("Create failed: " + created.diagnostics()); return done(); }
            NpcHandle npc = created.value().orElseThrow().npc();
            walker = npc;
            player.sendMessage("Created " + created.value().get().id() + "; walking six blocks");
            NpcLocation target = BukkitNpcs.location(goal);
            return npcs.navigate(npc, target, 0).thenCompose((Outcome<NpcLocation> arrived) -> {
                report(player, "Walk ended: " + arrived.status() + " " + arrived.diagnostics());
                if (arrived.status() != Outcome.Status.OK) return done();
                return npcs.speak(npc, "&aI made it!", Audience.within(16), NpcService.Speech.BOTH).thenApply((Outcome<Integer> said) -> null);
            });
        }).exceptionally((Throwable failure) -> { getLogger().warning("Walk failed: " + failure); return null; });
    }

    /** Tells the player and the console; both accept messages from any thread. */
    private void report(Player player, String line) {
        player.sendMessage(line);
        getLogger().info(player.getName() + ": " + line);
    }

    /** A persistent NPC saved beside the player. */
    private void bench(Player player) {
        Location at = player.getLocation().clone().add(player.getLocation().getDirection().setY(0).normalize().multiply(2));
        at.setYaw(player.getLocation().getYaw() + 180);
        NpcSpec spec = NpcSpec.builder().id(BENCH).group("npcdemo").name("&eBench").location(BukkitNpcs.location(at)).build();
        npcs.create(spec).thenAccept((Outcome<DefinitionSnapshot> created) -> {
            if (created.status() != Outcome.Status.OK) { player.sendMessage("Bench failed: " + created.status() + " " + created.diagnostics()); return; }
            bench = created.value().orElseThrow().npc();
            player.sendMessage("Saved " + BENCH + " in npcs/npcdemo; /npcdemo sit seats you on it");
        });
    }

    private void sit(Player player) {
        NpcHandle saved = bench;
        if (saved == null) { player.sendMessage("Run /npcdemo bench first"); return; }
        npcs.seat(saved, player.getUniqueId()).thenAccept((Outcome<NpcHandle> seated) -> report(player, "Seat: " + seated.status() + " " + seated.diagnostics()));
    }

    private void teleport(Player player, String[] arguments) {
        NpcHandle npc = arguments.length > 1 && arguments[1].equals("bench") ? bench : walker;
        if (npc == null || arguments.length < 5) { player.sendMessage("/npcdemo tp <walker|bench> <x> <y> <z>, after creating it"); return; }
        NpcLocation to;
        try {
            to = new NpcLocation(player.getWorld().getName(), Double.parseDouble(arguments[2]), Double.parseDouble(arguments[3]), Double.parseDouble(arguments[4]), 180, 0);
        } catch (NumberFormatException notNumbers) { player.sendMessage("x, y and z are numbers"); return; }
        npcs.teleport(npc, to).thenAccept((Outcome<NpcHandle> moved) -> report(player, "Teleport: " + moved.status() + " " + moved.diagnostics()));
    }

    private void shout(Player player) {
        NpcHandle npc = walker;
        if (npc == null) { player.sendMessage("Run /npcdemo walk first"); return; }
        npcs.speak(npc, "&eCan anyone hear me?", Audience.within(128), NpcService.Speech.CHAT)
                .thenAccept((Outcome<Integer> said) -> report(player, "Shout reached " + said.value().map(String::valueOf).orElse(said.status().toString()) + " players"));
    }

    private void count(CommandSender sender) {
        NpcHandle npc = walker;
        if (npc == null) { sender.sendMessage("Run /npcdemo walk first"); return; }
        npcs.definitions().find(npc).thenAccept((Outcome<DefinitionSnapshot> found) -> sender.sendMessage("Right clicks: " + found.value().map((DefinitionSnapshot snapshot) -> {
            JsonObject modules = JsonParser.parseString(snapshot.json()).getAsJsonObject().getAsJsonObject("modules");
            return String.valueOf(modules == null || !modules.has(CLICKS) ? 0 : count(Optional.of(modules.get(CLICKS).toString())));
        }).orElse(found.status().toString())));
    }

    private void remove(CommandSender sender) {
        for (NpcHandle npc : new NpcHandle[] {walker, bench}) {
            if (npc == null) continue;
            npcs.remove(npc).thenAccept((Outcome<NpcHandle> removed) -> sender.sendMessage("Removed " + npc.uuid() + ": " + removed.status()));
        }
        walker = null;
        bench = null;
    }

    /** The count in the module's data, 0 when it has none. */
    private static int count(Optional<String> data) {
        return data.map((String json) -> JsonParser.parseString(json).getAsJsonObject().get("count")).filter(JsonElement::isJsonPrimitive).map(JsonElement::getAsInt).orElse(0);
    }

    private static CompletionStage<Void> done() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
}
