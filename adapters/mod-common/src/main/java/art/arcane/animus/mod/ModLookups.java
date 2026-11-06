package art.arcane.animus.mod;

import art.arcane.animus.core.editor.EditorHost;
import com.mojang.brigadier.tree.CommandNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

/**
 * The lists {@code GET /v1/lookups} returns on mod servers: the built-in registries (modded entries included), the
 * data-driven enchantments, the loaded dimensions and the command dispatcher's top-level names. Mod servers have no
 * PlaceholderAPI. Read from the editor's threads; a list that cannot be read comes back empty.
 */
final class ModLookups {
    private final MinecraftServer server;

    ModLookups(MinecraftServer server) { this.server = server; }

    EditorHost.Lookups read() {
        Map<String, List<String>> lists = new LinkedHashMap<>();
        lists.put("items", ids(BuiltInRegistries.ITEM));
        lists.put("blocks", ids(BuiltInRegistries.BLOCK));
        lists.put("sounds", ids(BuiltInRegistries.SOUND_EVENT));
        lists.put("particles", ids(BuiltInRegistries.PARTICLE_TYPE));
        lists.put("entityTypes", ids(BuiltInRegistries.ENTITY_TYPE));
        lists.put("effects", ids(BuiltInRegistries.MOB_EFFECT));
        lists.put("attributes", ids(BuiltInRegistries.ATTRIBUTE));
        List<String> enchantments;
        try {
            enchantments = ids(server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT));
        } catch (RuntimeException unreadable) {
            enchantments = List.of();
        }
        lists.put("enchantments", enchantments);
        lists.put("worlds", List.copyOf(ModWorldKeys.loaded(server)));
        return new EditorHost.Lookups(lists, commands(), List.of());
    }

    private static List<String> ids(Registry<?> registry) {
        List<String> out = new ArrayList<>();
        try {
            for (Identifier id : registry.keySet()) out.add(id.toString());
        } catch (RuntimeException unreadable) {
            return List.of();
        }
        return out;
    }

    private List<String> commands() {
        List<String> out = new ArrayList<>();
        try {
            for (CommandNode<CommandSourceStack> node : List.copyOf(server.getCommands().getDispatcher().getRoot().getChildren())) out.add(node.getName());
        } catch (RuntimeException unreadable) {
            return List.of();
        }
        return out;
    }
}
