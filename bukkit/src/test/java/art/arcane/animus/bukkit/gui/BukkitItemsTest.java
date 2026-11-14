package art.arcane.animus.bukkit.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.npc.NpcItem;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

/** What the Bukkit item builder hands the server: upright names and lore on menu items only, and heads that name their owner. */
class BukkitItemsTest {
    final Map<String, Object> written = new HashMap<>();
    final ItemMeta meta = (ItemMeta) Proxy.newProxyInstance(BukkitItemsTest.class.getClassLoader(), new Class<?>[]{ItemMeta.class}, (Object proxy, Method method, Object[] arguments) -> {
        if (method.getName().equals("setDisplayName") || method.getName().equals("setLore")) written.put(method.getName(), arguments[0]);
        return null;
    });

    static NpcItem head(String components) { return new NpcItem("minecraft:player_head", 1, components.isEmpty() ? Optional.empty() : Optional.of(components), Optional.empty()); }

    @Test void everyMenuNameAndLoreLineStartsWithAResetSoTheServerSetsItUpright() {
        BukkitItems.dress(meta, Optional.of("§dWand"), List.of("§7Grey line", "plain", ""));
        assertEquals("§r§dWand", written.get("setDisplayName"));
        assertEquals(List.of("§r§7Grey line", "§rplain", "§r"), written.get("setLore"));
    }

    @Test void storedGivenAndComparedItemsKeepTheirTextAsWritten() {
        BukkitItems.label(meta, Optional.of("Key"), List.of("plain", "§7Grey line"));
        assertEquals("Key", written.get("setDisplayName"), "a key a GUI gave out before still matches the one its take builds");
        assertEquals(List.of("plain", "§7Grey line"), written.get("setLore"));
    }

    @Test void nothingIsWrittenWithoutANameOrLore() {
        BukkitItems.dress(meta, Optional.empty(), List.of());
        assertTrue(written.isEmpty(), written.toString());
    }

    @Test void anOwnersHeadGetsAProfileTheClientResolvesKeptOutOfTheTooltip() {
        assertEquals("[profile=\"Notch\",tooltip_display={hidden_components:[\"minecraft:profile\",\"minecraft:attribute_modifiers\"]}]",
                BukkitItems.owned(head("[tooltip_display={hidden_components:[\"minecraft:attribute_modifiers\"]}]"), "Notch").components().orElseThrow());
        assertEquals("[profile=\"jeb_\",tooltip_display={hidden_components:[\"minecraft:profile\"]}]", BukkitItems.owned(head(""), "jeb_").components().orElseThrow());
        assertEquals("[profile=\"jeb_\",tooltip_display={hidden_components:[\"minecraft:profile\"]}]", BukkitItems.owned(head("[tooltip_display={hidden_components:[]}]"), "jeb_").components().orElseThrow());
        assertEquals("[profile=\"jeb_\",custom_name=\"x\",tooltip_display={hidden_components:[\"minecraft:profile\"]}]",
                BukkitItems.owned(head("[custom_name=\"x\"]"), "jeb_").components().orElseThrow());
    }

    @Test void skinHeadsOtherItemsAndNamesNoAccountCanHaveStayAsTheyAre() {
        NpcItem skin = head("[profile={properties:[{name:\"textures\",value:\"abc\"}]}]");
        assertEquals(skin, BukkitItems.owned(skin, "Notch"));
        NpcItem stone = new NpcItem("minecraft:stone", 1, Optional.empty(), Optional.empty());
        assertEquals(stone, BukkitItems.owned(stone, "Notch"));
        for (String name : List.of("", "%viewer%", "two words", "a_name_far_too_long")) assertEquals(head(""), BukkitItems.owned(head(""), name), name);
    }
}
