package art.arcane.animus.qa;

import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/** QA-only third-party listener: cancellation and synchronous preview teardown. Never ship this fixture. */
public final class ProofFixture extends JavaPlugin implements Listener {
    private enum Mode { CANCEL, CLEAR }
    private final Map<UUID, Mode> next = new HashMap<>();
    @Override public void onEnable() { getServer().getPluginManager().registerEvents(this, this); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        if (!(sender instanceof Player player) || !sender.isOp()) { sender.sendMessage("QA requires an operator player"); return true; }
        if (arguments.length != 1) return false;
        switch (arguments[0]) {
            case "cancel" -> next.put(player.getUniqueId(), Mode.CANCEL);
            case "clear" -> next.put(player.getUniqueId(), Mode.CLEAR);
            case "allow" -> next.remove(player.getUniqueId());
            default -> { return false; }
        }
        sender.sendMessage("QA next interaction=" + arguments[0]);
        return true;
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteraction(VirtualNpcInteractionEvent event) {
        if (!event.isPreview()) return;
        if (getServer().getEntity(event.getNpcId()) != null) throw new AssertionError("Preview body was registered in the world");
        Mode mode = next.remove(event.getViewer().getUniqueId());
        if (mode == Mode.CANCEL) { event.setCancelled(true); event.getViewer().sendMessage("QA cancelled preview interaction"); }
        if (mode == Mode.CLEAR) {
            getServer().dispatchCommand(event.getViewer(), "animus proof clear");
            event.getViewer().sendMessage("QA cleared preview during event");
        }
    }
}
