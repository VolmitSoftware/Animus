package art.arcane.animus.bukkit;

import art.arcane.animus.core.editor.EditorHost;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandMap;

/**
 * The lists {@code GET /v1/lookups} returns on Bukkit: registry ids (read once, they are fixed after startup), the worlds,
 * the command map's plain labels, and PlaceholderAPI's identifiers when it is enabled. Read from the editor's threads; a
 * list that cannot be read comes back empty.
 */
final class BukkitLookups {
    private final Server server;
    private volatile Map<String, List<String>> registries;

    BukkitLookups(Server server) { this.server = server; }

    EditorHost.Lookups read() {
        Map<String, List<String>> lists = new LinkedHashMap<>(registries());
        List<String> worlds = new ArrayList<>();
        for (World world : server.getWorlds()) worlds.add(world.getName());
        lists.put("worlds", worlds);
        return new EditorHost.Lookups(lists, commands(), papi());
    }

    private Map<String, List<String>> registries() {
        Map<String, List<String>> known = registries;
        if (known != null) return known;
        Map<String, List<String>> lists = new LinkedHashMap<>();
        List<String> items = new ArrayList<>(), blocks = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material.isLegacy()) continue;
            if (material.isItem()) items.add(material.getKey().toString());
            if (material.isBlock()) blocks.add(material.getKey().toString());
        }
        lists.put("items", items);
        lists.put("blocks", blocks);
        lists.put("sounds", keys(Registry.SOUNDS));
        lists.put("particles", keys(Registry.PARTICLE_TYPE));
        lists.put("entityTypes", keys(Registry.ENTITY_TYPE));
        lists.put("enchantments", keys(Registry.ENCHANTMENT));
        lists.put("effects", keys(Registry.EFFECT));
        lists.put("attributes", keys(Registry.ATTRIBUTE));
        registries = lists;
        return lists;
    }

    private static <T extends Keyed> List<String> keys(Registry<T> registry) {
        List<String> out = new ArrayList<>();
        try {
            for (T entry : registry) out.add(entry.getKey().toString());
        } catch (RuntimeException unreadable) {
            return List.of();
        }
        return out;
    }

    private List<String> commands() {
        try {
            CommandMap map = (CommandMap) server.getClass().getMethod("getCommandMap").invoke(server);
            List<String> labels = new ArrayList<>();
            for (String label : List.copyOf(BukkitServerCommands.knownCommands(map).keySet())) if (label.indexOf(':') < 0) labels.add(label);
            return labels;
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return List.of();
        }
    }

    private List<String> papi() {
        if (!server.getPluginManager().isPluginEnabled("PlaceholderAPI")) return List.of();
        try {
            Object identifiers = Class.forName("me.clip.placeholderapi.PlaceholderAPI").getMethod("getRegisteredIdentifiers").invoke(null);
            List<String> out = new ArrayList<>();
            if (identifiers instanceof Set<?> set) for (Object identifier : List.copyOf(set)) if (identifier instanceof String name) out.add(name);
            return out;
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return List.of();
        }
    }
}
