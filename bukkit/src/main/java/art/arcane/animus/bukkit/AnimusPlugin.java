package art.arcane.animus.bukkit;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class AnimusPlugin extends JavaPlugin {
    private final AnimusBukkit animus = new AnimusBukkit(this);

    @Override
    public void onEnable() {
        animus.enable();
    }

    @Override
    public void onDisable() {
        animus.disable();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        return animus.command(sender, arguments);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] arguments) {
        return animus.complete(sender, arguments);
    }
}
