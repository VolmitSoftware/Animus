package art.arcane.animus.core.behavior.schema;

import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.presentation.BodyRules;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The server, the NPC schema and the web editor agree on which equipment slots each body shows. */
class BodyEquipmentSchemaTest {
    private static final Pattern CASE = Pattern.compile("((?:'[a-z0-9_:]+'\\s*\\|\\|\\s*)*'[a-z0-9_:]+')\\s*=>\\s*const <NpcSlot>\\[([^\\]]*)\\]");

    @Test void theEditorDrawsTheSameSlotsTheServerSends() throws Exception {
        String dart = Files.readString(RepositoryFiles.root().resolve("animus-web/lib/mc/npc/npc_look.dart"));
        int start = dart.indexOf("List<NpcSlot> mcDrawnSlots(");
        assertTrue(start >= 0, "mcDrawnSlots moved");
        String block = dart.substring(start, dart.indexOf("};", start));
        Map<String, Set<EquipmentSlot>> editor = new TreeMap<>();
        Matcher match = CASE.matcher(block);
        while (match.find()) {
            Set<EquipmentSlot> slots = EnumSet.noneOf(EquipmentSlot.class);
            for (String name : match.group(2).split(",")) {
                String slot = name.replace("NpcSlot.", "").replace("//", "").trim();
                if (!slot.isEmpty()) slots.add(EquipmentSlot.valueOf(slot.replaceAll("([A-Z])", "_$1").toUpperCase(Locale.ROOT)));
            }
            for (String type : match.group(1).split("\\|\\|")) editor.put(type.trim().replace("'", ""), slots);
        }
        assertEquals(new TreeMap<>(BodyRules.limitedEquipment()), editor);
    }

    @Test void theSchemaListsOnlyTheSlotsEachBodyShows() {
        Map<String, JsonObject> rules = new TreeMap<>();
        for (JsonElement element : NpcSchemaGenerator.schema().getAsJsonArray("allOf")) {
            JsonObject rule = element.getAsJsonObject();
            if (!rule.has("then") || !rule.getAsJsonObject("then").getAsJsonObject("properties").has("equipment")) continue;
            String type = rule.getAsJsonObject("if").getAsJsonObject("properties").getAsJsonObject("type").get("const").getAsString();
            rules.put(type, rule.getAsJsonObject("then").getAsJsonObject("properties").getAsJsonObject("equipment"));
        }
        assertEquals(BodyRules.limitedEquipment().keySet(), rules.keySet(), "a body without slots of its own takes every slot");
        List<String> horse = new ArrayList<>();
        rules.get("minecraft:horse").getAsJsonObject("propertyNames").getAsJsonArray("enum").forEach((JsonElement slot) -> horse.add(slot.getAsString()));
        assertEquals(List.of("body", "saddle"), horse);
        assertEquals(0, rules.get("minecraft:turtle").get("maxProperties").getAsInt());
        assertEquals(6, rules.get("minecraft:player").getAsJsonObject("propertyNames").getAsJsonArray("enum").size());
    }
}
