package art.arcane.animus.bukkit.integration.mythic;

import art.arcane.animus.core.integration.NpcTemplates;
import art.arcane.animus.core.npc.NpcItem;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MobLookTest {
    private static final Function<String, Optional<NpcItem>> VANILLA = (String name) -> name.equalsIgnoreCase("NoSuchItem") ? Optional.empty()
            : Optional.of(new NpcItem("minecraft:" + name.toLowerCase(java.util.Locale.ROOT), 1, Optional.empty(), Optional.empty()));

    private static final java.util.Set<String> SHOWN = java.util.Set.of("minecraft:player", "minecraft:armor_stand", "minecraft:horse", "minecraft:llama", "minecraft:zombie");

    private static NpcTemplates.Template template(MobSource mob, LookHooks hooks) { return MobLook.template(mob, VANILLA, hooks, SHOWN); }

    private static MobSource mob(String type, List<String> equipment, Map<String, String> options, Optional<String> disguise, List<String> skills) {
        return new MobSource("SkeletonKing", type, Optional.of("&6Skeleton King"), equipment, options, disguise, skills);
    }

    @Test void aShownBodyKeepsItsTypeEquipmentAndOptions() {
        NpcTemplates.Template template = template(mob("ARMOR_STAND", List.of("GOLDEN_HELMET:4", "BOW HAND", "SHIELD OFFHAND"),
                Map.of("Glowing", "true"), Optional.empty(), List.of()), LookHooks.NONE);
        JsonObject fields = template.fields();
        assertEquals("minecraft:armor_stand", fields.get("type").getAsString());
        assertEquals("&6Skeleton King", fields.get("name").getAsString());
        assertTrue(fields.get("glowing").getAsBoolean());
        JsonObject equipment = fields.getAsJsonObject("equipment");
        assertEquals("minecraft:golden_helmet", equipment.getAsJsonObject("head").get("id").getAsString());
        assertEquals("minecraft:bow", equipment.getAsJsonObject("main_hand").get("id").getAsString());
        assertEquals("minecraft:shield", equipment.getAsJsonObject("off_hand").get("id").getAsString());
        assertEquals("SkeletonKing", fields.getAsJsonObject("modules").getAsJsonObject("mythic:mob").get("mob").getAsString());
        assertTrue(template.notes().isEmpty(), template.notes().toString());
    }

    @Test void everyBodyTheServerShowsIsKeptAsTheMobType() {
        NpcTemplates.Template template = template(mob("BABY_ZOMBIE", List.of("IRON_SWORD:0", "NoSuchItem:4"), Map.of(), Optional.empty(), List.of()), LookHooks.NONE);
        assertEquals("minecraft:zombie", template.fields().get("type").getAsString());
        assertTrue(template.fields().get("baby").getAsBoolean());
        assertTrue(template.notes().stream().anyMatch((String note) -> note.contains("NoSuchItem")), template.notes().toString());
        assertEquals("minecraft:iron_sword", template.fields().getAsJsonObject("equipment").getAsJsonObject("main_hand").get("id").getAsString());
    }

    @Test void aTypeTheServerCannotShowBecomesAPlayerBodyAndSaysSo() {
        NpcTemplates.Template template = template(mob("SKELETON", List.of(), Map.of(), Optional.empty(), List.of()), LookHooks.NONE);
        assertEquals("minecraft:player", template.fields().get("type").getAsString());
        assertTrue(template.notes().stream().anyMatch((String note) -> note.contains("minecraft:skeleton") && note.contains("player body")), template.notes().toString());
    }

    @Test void babyOptionsAndSlotsFollowTheBody() {
        NpcTemplates.Template baby = template(mob("HORSE", List.of("DIAMOND_HORSE_ARMOR BODY", "IRON_HELMET HEAD"), Map.of("Baby", "true"),
                Optional.empty(), List.of()), LookHooks.NONE);
        assertEquals("minecraft:horse", baby.fields().get("type").getAsString());
        assertTrue(baby.fields().get("baby").getAsBoolean());
        assertTrue(baby.fields().getAsJsonObject("equipment").has("body"));
        assertFalse(baby.fields().getAsJsonObject("equipment").has("head"));
        assertTrue(baby.notes().stream().anyMatch((String note) -> note.contains("head")), baby.notes().toString());
        assertTrue(template(mob("LLAMA", List.of(), Map.of("Age", "-24000"), Optional.empty(), List.of()), LookHooks.NONE).fields().get("baby").getAsBoolean());
        assertTrue(template(mob("LLAMA", List.of(), Map.of("Adult", "false"), Optional.empty(), List.of()), LookHooks.NONE).fields().get("baby").getAsBoolean());
        assertFalse(template(mob("LLAMA", List.of(), Map.of("Age", "0"), Optional.empty(), List.of()), LookHooks.NONE).fields().has("baby"));
    }

    @Test void disguisesAndModelEngineModelsGoThroughTheHooks() {
        List<String> seen = new ArrayList<>();
        LookHooks hooks = new LookHooks() {
            @Override public Optional<String> disguise(JsonObject fields, String spec) { seen.add("disguise " + spec); fields.addProperty("disguised", spec); return Optional.empty(); }
            @Override public Optional<String> model(JsonObject fields, String model) { seen.add("model " + model); return Optional.of("model " + model + " applied"); }
        };
        NpcTemplates.Template template = template(mob("ZOMBIE", List.of(), Map.of(), Optional.of("player:Notch"),
                List.of("- model{mid=skeleton_king;n=king} @self ~onSpawn", "- message{m=\"Hi\"} @trigger ~onInteract")), hooks);
        assertEquals(List.of("disguise player:Notch", "model skeleton_king"), seen);
        assertEquals("player:Notch", template.fields().get("disguised").getAsString());
        assertTrue(template.notes().contains("model skeleton_king applied"));
        NpcTemplates.Template plain = template(mob("ZOMBIE", List.of(), Map.of(), Optional.of("player:Notch"),
                List.of("- model{modelid=crown} @self ~onSpawn")), LookHooks.NONE);
        assertTrue(plain.notes().stream().anyMatch((String note) -> note.contains("player:Notch")), plain.notes().toString());
        assertTrue(plain.notes().stream().anyMatch((String note) -> note.contains("crown")), plain.notes().toString());
    }

    @Test void theDefaultHooksWriteTheDisguiseField() {
        NpcTemplates.Template template = template(mob("ZOMBIE", List.of(), Map.of(), Optional.of("player:Notch"), List.of()), LookHooks.DEFAULT);
        assertEquals("player Notch", template.fields().get("disguise").getAsString(), "Mythic's type:skin is written as LibsDisguises reads it");
        assertEquals("zombie setBaby true", template(mob("ZOMBIE", List.of(), Map.of(), Optional.of("zombie setBaby true"), List.of()), LookHooks.DEFAULT)
                .fields().get("disguise").getAsString());
    }

    @Test void theDefaultHooksPutTheModelInTheModelEngineModule() {
        NpcTemplates.Template template = template(mob("ZOMBIE", List.of(), Map.of(), Optional.empty(),
                List.of("- model{mid=skeleton_king} @self ~onSpawn")), LookHooks.DEFAULT);
        JsonObject modules = template.fields().getAsJsonObject("modules");
        assertEquals("skeleton_king", modules.getAsJsonObject("animus:modelengine").get("model").getAsString());
        assertEquals("SkeletonKing", modules.getAsJsonObject("mythic:mob").get("mob").getAsString());
        assertTrue(template.notes().contains("shows Model Engine model skeleton_king"), template.notes().toString());
    }

    @Test void theModelIdIsReadFromTheModelMechanic() {
        assertEquals(Optional.of("dragon"), MobLook.model(List.of("- model{mid=dragon} @self ~onSpawn")));
        assertEquals(Optional.of("crown"), MobLook.model(List.of("- particles{p=flame} @self ~onTimer:20", "- model{n=x;modelid=crown;drive=true} @self ~onSpawn")));
        assertEquals(Optional.empty(), MobLook.model(List.of("- modelpart{mid=dragon;pid=head} @self ~onSpawn")));
    }

    /** Model Engine's Mythic mechanics need a real entity; the NPC's model comes from animus:modelengine instead. */
    @Test void modelEngineMechanicsAreLeftToTheModelModule() {
        assertTrue(MobLook.drawnByAnimus("com.ticxo.modelengine.core21.mythic.mechanics.entity.ModelMechanic"));
        assertTrue(MobLook.drawnByAnimus("com.ticxo.modelengine.core.mythic.mechanics.StateMechanic"));
        assertFalse(MobLook.drawnByAnimus("io.lumine.mythic.core.skills.mechanics.MessageMechanic"));
    }
}
