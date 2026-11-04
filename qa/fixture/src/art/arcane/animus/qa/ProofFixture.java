package art.arcane.animus.qa;

import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/** QA-only third-party listener. Never ship this fixture. */
public final class ProofFixture extends JavaPlugin implements Listener {
    private enum Mode { CANCEL, CLEAR }
    private final Map<UUID, Mode> next = new HashMap<>();
    private final Set<UUID> production = new HashSet<>();
    private CombatFixture combat;
    private MotionFixture motion;
    @Override public void onEnable() {
        combat = new CombatFixture(this);
        motion = new MotionFixture(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(combat, this);
        getServer().getPluginManager().registerEvents(motion, this);
    }
    @Override public void onDisable() { if (motion != null) motion.close(); if (combat != null) combat.close(); next.clear(); production.clear(); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        if (!(sender instanceof Player player) || !sender.isOp()) { sender.sendMessage("QA requires an operator player"); return true; }
        try {
            if (arguments.length == 2 && (arguments[0].equals("expect") || arguments[0].equals("forget"))) {
                UUID id = UUID.fromString(arguments[1]);
                if (arguments[0].equals("expect")) {
                    if (production.size() >= 32 && !production.contains(id)) throw new IllegalStateException("Expected NPC limit reached");
                    production.add(id);
                } else production.remove(id);
                sender.sendMessage("QA production " + arguments[0] + " id=" + id); return true;
            }
            if (motion.command(player, arguments) || combat.command(player, arguments)) return true;
        } catch (IllegalArgumentException | IllegalStateException error) {
            sender.sendMessage("QA error: " + error.getMessage()); return true;
        }
        if (arguments.length != 1) return false;
        switch (arguments[0]) {
            case "cancel" -> next.put(player.getUniqueId(), Mode.CANCEL);
            case "clear" -> next.put(player.getUniqueId(), Mode.CLEAR);
            case "allow" -> next.remove(player.getUniqueId());
            default -> { return false; }
        }
        sender.sendMessage("QA next interaction=" + arguments[0]);
        if (arguments[0].equals("cancel")) sender.sendMessage("QA armed interaction=cancel");
        return true;
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteraction(VirtualNpcInteractionEvent event) {
        if (!event.isPreview() && !production.contains(event.getNpcId())) return;
        if (production.contains(event.getNpcId()) && event.isPreview()) throw new AssertionError("Production NPC interaction was marked preview");
        if (getServer().getEntity(event.getNpcId()) != null) throw new AssertionError("Virtual body was registered in the world");
        if (!event.isPreview()) event.getViewer().sendMessage("QA production interaction id=" + event.getNpcId()
                + " action=" + event.getAction() + " preview=false world-registered=0");
        Mode mode = next.remove(event.getViewer().getUniqueId());
        if (mode == Mode.CANCEL) { event.setCancelled(true); event.getViewer().sendMessage("QA cancelled " + (event.isPreview() ? "preview" : "production") + " interaction"); }
        if (mode == Mode.CLEAR && event.isPreview()) {
            getServer().dispatchCommand(event.getViewer(), "animus proof clear");
            event.getViewer().sendMessage("QA cleared preview during event");
        }
    }
}
