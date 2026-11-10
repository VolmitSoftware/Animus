package art.arcane.animus.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.command.PermissionNodes;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** plugin.yml declares every management node for operators and makes {@code animus.admin} their parent. */
class PluginPermissionsTest {
    @Test void adminIsTheParentOfEveryManagementNode() throws Exception {
        YamlConfiguration plugin = new YamlConfiguration();
        plugin.options().pathSeparator('/');
        plugin.load(Path.of("src/main/resources/plugin.yml").toFile());
        ConfigurationSection permissions = plugin.getConfigurationSection("permissions");
        ConfigurationSection admin = permissions.getConfigurationSection(PermissionNodes.ADMIN);
        assertEquals("op", admin.getString("default"));
        ConfigurationSection children = admin.getConfigurationSection("children");
        assertEquals(PermissionNodes.CHILDREN, List.copyOf(children.getKeys(false)));
        for (String child : PermissionNodes.CHILDREN) {
            assertTrue(children.getBoolean(child), child + " is granted, not negated");
            ConfigurationSection declared = permissions.getConfigurationSection(child);
            assertEquals("op", declared == null ? null : declared.getString("default"), child + " is declared for operators");
            assertTrue(!declared.getString("description", "").isBlank(), child + " has a description");
        }
    }

    @Test void theJournalHasALabelNoQuestPluginTakes() throws Exception {
        YamlConfiguration plugin = new YamlConfiguration();
        plugin.load(Path.of("src/main/resources/plugin.yml").toFile());
        assertEquals(List.of("questlog"), plugin.getStringList("commands.quests.aliases"), "Quests by PikaMug owns /quests when installed");
    }
}
