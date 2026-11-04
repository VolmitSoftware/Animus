package art.arcane.animus.bukkit;

import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.admin.Administration;
import java.util.concurrent.*;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.AnimusSplash;
import art.arcane.animus.core.RuntimeEnvironment;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class AnimusPlugin extends JavaPlugin {
    private volatile AnimusRuntime runtime;
    private ThreadPoolExecutor commands;
    private ReplyScheduler replies;
    private volatile boolean stopping;


    @Override
    public void onEnable() {
        try {
            RuntimeEnvironment environment = RuntimeEnvironment.bukkit(
                    getServer().getBukkitVersion(), getDataFolder().toPath());
            runtime = new AnimusRuntime(environment);
            runtime.start();
            replies = new ReplyScheduler(this);
            AnimusRuntime ownedRuntime = runtime;
            commands = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), runnable -> {
                Thread thread = new Thread(runnable, "Animus-files");
                thread.setDaemon(true);
                return thread;
            }) {
                @Override protected void terminated() { ownedRuntime.close(); }
            };
            printSplash(AnimusSplash.render(environment, getServer().getVersion(), true));
        } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError exception) {
            getLogger().log(Level.SEVERE, "Failed to start " + BuildIdentity.NAME, exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        stopping = true;
        AnimusRuntime activeRuntime = runtime;
        runtime = null;
        if (commands != null) {
            commands.shutdownNow();
            try {
                if (!commands.awaitTermination(5, TimeUnit.SECONDS))
                    getLogger().warning("File worker is finishing; a pending transaction will recover on the next startup.");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        } else if (activeRuntime != null) activeRuntime.close();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        AnimusRuntime activeRuntime = runtime;
        if (stopping || activeRuntime == null) { sender.sendMessage("Animus is not running."); return true; }
        boolean status = arguments.length == 0 || arguments[0].equalsIgnoreCase("status");
        if (!sender.hasPermission(status ? "animus.status" : "animus.admin")) {
            sender.sendMessage("You do not have permission to use this command."); return true;
        }
        String[] args = arguments.clone();
        if (args.length > 0) args[0] = args[0].toLowerCase(Locale.ROOT);
        try {
            commands.execute(() -> {
                List<String> result;
                try { result = new Administration(activeRuntime).execute(args); }
                catch (IOException | RuntimeException e) { result = List.of("Animus: " + e.getMessage()); }
                List<String> messages = result;
                if (!stopping) replies.reply(sender, () -> {
                    if (!stopping) {
                        messages.stream().limit(30).map(message -> message.length() > 2048 ? message.substring(0, 2048) + " … (truncated; inspect the JSON file for full details)" : message).forEach(sender::sendMessage);
                        if (messages.size() > 30) sender.sendMessage("Showing 30 of " + messages.size() + " results; inspect the files for the complete report.");
                    }
                });
            });
        } catch (RejectedExecutionException e) { sender.sendMessage("Animus file queue is full; retry shortly."); }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] arguments) {
        if (arguments.length == 1) {
            List<String> available = sender.hasPermission("animus.admin") ? Administration.COMMANDS : List.of("status");
            return available.stream().filter(c -> c.startsWith(arguments[0].toLowerCase(Locale.ROOT))).toList();
        }
        AnimusRuntime activeRuntime = runtime;
        if (arguments.length == 2 && activeRuntime != null && sender.hasPermission("animus.admin")
                && List.of("inspect", "edit", "clone", "delete").contains(arguments[0]))
            return activeRuntime.npcs().all().stream().map(d -> d.id()).filter(id -> id.startsWith(arguments[1])).limit(100).toList();
        if (arguments.length == 2 && arguments[0].equals("import") && sender.hasPermission("animus.admin"))
            return List.of("preview", "apply").stream().filter(s -> s.startsWith(arguments[1])).toList();
        return List.of();
    }

    private void printSplash(String splash) {
        StringBuilder colored = new StringBuilder();
        ChatColor previous = null;
        for (int index = 0; index < splash.length(); index++) {
            char glyph = splash.charAt(index);
            ChatColor color;
            if (glyph == '█') {
                color = ChatColor.DARK_GRAY;
            } else if (glyph >= '\u2500' && glyph <= '\u257f') {
                color = ChatColor.GOLD;
            } else {
                color = ChatColor.GRAY;
            }
            if (color != previous) {
                colored.append(color);
                previous = color;
            }
            colored.append(glyph);
        }
        getServer().getConsoleSender().sendMessage(colored.toString()
                .replace("Startup: READY", "Startup: " + ChatColor.GREEN + "READY" + ChatColor.GRAY)
                + ChatColor.RESET);
    }
}
