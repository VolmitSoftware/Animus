package art.arcane.animus.core.migration;

import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.TargetCategory;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SentinelMappingTest {
    /** All 69 persisted keys at the pinned source; see docs/upstream-inventory.md. */
    private static final Set<String> SETTINGS = Set.of("allTargets", "allIgnores", "allAvoids", "avoidRange", "range", "damage", "armor", "health", "ranged_chase", "close_chase",
            "invincible", "protected", "protectFromRange", "fightback", "runaway", "attackRate", "attackRateRanged", "healRate", "guardingUpper", "guardingLower", "guardedNPC", "needsAmmo",
            "safeShot", "respawnTime", "chaseRange", "spawnPoint", "avoidReturnPoint", "drops", "dropChances", "enemyDrops", "enemyTargetTime", "speed", "warning_text", "greeting_text",
            "greet_range", "greet_rate", "autoswitch", "squad", "accuracy", "realistic", "ignore_los", "reach", "projectileRange", "guard_distance_minimum", "guard_selection_range", "retain_target",
            "weapon_damage_map", "weapon_redirect_map", "disable_teleporting", "reaction_slowdown", "allow_knockback", "worldguard_region", "death_xp");
    private static final Set<String> STATISTICS = Set.of("stats_ticksSpawned", "stats_timesSpawned", "stats_arrowsFired", "stats_potionsThrow", "stats_fireballsFired", "stats_snowballsThrown",
            "stats_eggsThrown", "stats_skullsThrown", "stats_llamaSpitShot", "stats_shulkerBulletsShot", "stats_evokerFangsSpawned", "stats_pearlsUsed", "stats_punches", "stats_attackAttempts",
            "stats_damageTaken", "stats_damageGiven");
    @TempDir Path root;

    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static SentinelMapping.Result convert(String sentinel) { return SentinelMapping.convert(json(sentinel), JsonNull.INSTANCE, new JsonArray()); }
    private static CombatSettings settings(SentinelMapping.Result result) { return CombatSettings.parseModule(result.module()); }

    @Test void everyPersistedFieldHasADisposition() {
        assertEquals(53, SETTINGS.size());
        assertEquals(16, STATISTICS.size());
        Map<SentinelMapping.Disposition, Integer> counts = new java.util.EnumMap<>(SentinelMapping.Disposition.class);
        for (String field : SETTINGS) {
            SentinelMapping.Disposition disposition = SentinelMapping.disposition(field);
            assertNotEquals(SentinelMapping.Disposition.UNKNOWN, disposition, field);
            assertNotEquals(SentinelMapping.Disposition.STATISTIC, disposition, field);
            counts.merge(disposition, 1, Integer::sum);
        }
        for (String field : STATISTICS) {
            assertEquals(SentinelMapping.Disposition.STATISTIC, SentinelMapping.disposition(field), field);
            counts.merge(SentinelMapping.Disposition.STATISTIC, 1, Integer::sum);
        }
        assertEquals(44, counts.get(SentinelMapping.Disposition.CONVERTED));
        assertEquals(9, counts.get(SentinelMapping.Disposition.UNSUPPORTED));
        assertEquals(16, counts.get(SentinelMapping.Disposition.STATISTIC));
        assertEquals(SentinelMapping.Disposition.UNKNOWN, SentinelMapping.disposition("somethingNew"));
    }
    @Test void anEmptyRecordStillGetsTheSourcesDefaultsAndStaysDisabled() {
        SentinelMapping.Result result = convert("{}");
        assertFalse(result.module().get("enabled").getAsBoolean());
        assertFalse(result.module().get("reviewAccepted").getAsBoolean());
        CombatSettings settings = settings(result);
        assertEquals(30, settings.melee().cooldownTicks(), "attackRate default");
        assertEquals(20, settings.targeting().scanRange(), "range default");
        assertEquals(0, settings.targeting().memoryTicks(), "enemyTargetTime default");
        assertEquals(100, settings.pursuit().giveUpRange(), "chaseRange default");
        assertEquals(100, settings.pursuit().leashRange(), "chaseRange default");
        assertEquals(100, settings.defense().healRateTicks(), "healRate default");
        assertEquals(100, settings.respawn().delayTicks(), "respawnTime default");
        assertFalse(settings.death().victimDrops(), "enemyDrops default");
        assertTrue(settings.pursuit().teleportWhenStuck(), "disable_teleporting default");
        assertTrue(settings.pursuit().closeChase());
        assertFalse(settings.pursuit().rangedChase());
        assertEquals(7, settings.guard().followDistance());
        assertEquals(4, settings.guard().pointRange());
    }
    @Test void directFieldsConvertWithTheirMeaning() {
        CombatSettings settings = settings(convert("""
                {"health":40,"damage":6,"armor":0.25,"attackRate":15,"attackRateRanged":25,"range":24,"reach":4,"chaseRange":48,"speed":0.5,"avoidRange":6,"enemyTargetTime":80,
                 "fightback":false,"runaway":true,"retain_target":true,"reaction_slowdown":10,"safeShot":false,"accuracy":2,"projectileRange":40,"close_chase":false,"ranged_chase":true,
                 "invincible":true,"protected":true,"protectFromRange":30,"allow_knockback":false,"healRate":0,"squad":"town","enemyDrops":true,"death_xp":12,"respawnTime":-1,"disable_teleporting":true}"""));
        assertEquals(40, settings.defense().maximumHealth());
        assertEquals(6, settings.melee().damage());
        assertEquals(.25, settings.defense().armor());
        assertEquals(15, settings.melee().cooldownTicks());
        assertEquals(25, settings.ranged().cooldownTicks());
        assertEquals(24, settings.targeting().scanRange());
        assertEquals(4, settings.melee().reach());
        assertEquals(48, settings.pursuit().leashRange());
        assertEquals(2.15, settings.pursuit().speed(), 1e-9, "1 means normal walking speed");
        assertEquals(6, settings.avoidance().range());
        assertEquals(80, settings.targeting().memoryTicks());
        assertFalse(settings.targeting().fightBack());
        assertTrue(settings.avoidance().fleeWhenAttacked());
        assertTrue(settings.targeting().retainTarget());
        assertEquals(10, settings.targeting().reactionTicks());
        assertFalse(settings.ranged().safeShot());
        assertEquals(2, settings.ranged().inaccuracy());
        assertEquals(40, settings.ranged().range());
        assertFalse(settings.pursuit().closeChase());
        assertTrue(settings.pursuit().rangedChase());
        assertTrue(settings.defense().invincible());
        assertTrue(settings.defense().protectedFromIgnored());
        assertEquals(30, settings.defense().protectFromRange());
        assertFalse(settings.defense().knockback());
        assertEquals(0, settings.defense().healRateTicks());
        assertEquals("town", settings.squad().name().orElseThrow());
        assertTrue(settings.death().victimDrops());
        assertEquals(12, settings.death().experience());
        assertEquals(-1, settings.respawn().delayTicks());
        assertFalse(settings.pursuit().teleportWhenStuck());
    }
    @Test void lineOfSightFollowsIgnoreThenRealistic() {
        assertEquals(CombatSettings.LineOfSight.REQUIRED, settings(convert("{}")).targeting().lineOfSight());
        assertEquals(CombatSettings.LineOfSight.FIELD_OF_VIEW, settings(convert("{\"realistic\":true}")).targeting().lineOfSight());
        assertEquals(CombatSettings.LineOfSight.IGNORED, settings(convert("{\"realistic\":true,\"ignore_los\":true}")).targeting().lineOfSight());
    }
    @Test void targetListsConvertWhatTheyCanAndPreserveTheRest() {
        SentinelMapping.Result result = convert("""
                {"allTargets":{"targets":["MONSTERS","PLAYERS","WITCHES","ZOMBIE","OWNER"],"byPlayerName":["Griefer","^bad.*$"],"byNpcName":["Bandit Boss"],"byEntityName":["Bob"],"byOther":["squad:raiders","uuid:00000000-0000-0000-0000-000000000009","team:red"],"byHeldItem":["diamond_sword"]},
                 "allIgnores":{"targets":["NPCS","PASSIVE_MOBS","MOBS"]},"allAvoids":{"targets":["CREEPER"]}}""");
        CombatSettings settings = settings(result);
        assertEquals(Set.of(TargetCategory.MONSTERS, TargetCategory.PLAYERS), settings.targets().categories());
        assertEquals(Set.of("minecraft:witch", "minecraft:zombie"), settings.targets().entityTypes());
        assertEquals(Set.of("griefer", "00000000-0000-0000-0000-000000000009"), settings.targets().players());
        assertEquals(Set.of("bandit boss"), settings.targets().npcs());
        assertEquals(Set.of("bob"), settings.targets().names());
        assertEquals(Set.of("raiders"), settings.targets().squads());
        assertEquals(Set.of(TargetCategory.NPCS, TargetCategory.ANIMALS, TargetCategory.MOBS), settings.ignores().categories());
        assertEquals(Set.of("minecraft:creeper"), settings.avoids().entityTypes());
        JsonObject residue = result.module().getAsJsonObject("preservedSettings").getAsJsonObject("allTargets");
        assertEquals("[\"OWNER\"]", residue.get("targets").toString());
        assertEquals("[\"^bad.*$\"]", residue.get("byPlayerName").toString());
        assertEquals("[\"team:red\"]", residue.get("byOther").toString());
        assertEquals("[\"diamond_sword\"]", residue.get("byHeldItem").toString());
        assertFalse(result.module().getAsJsonObject("preservedSettings").has("allIgnores"), "Nothing left over");
    }
    @Test void guardingConvertsThePlayerHalvesAndTheResolvedNpc() {
        UUID player = new UUID(5, 9);
        assertEquals(player, settings(convert("{\"guardingUpper\":5,\"guardingLower\":9,\"guard_distance_minimum\":3,\"guard_selection_range\":2}")).guard().player().orElseThrow());
        assertTrue(settings(convert("{\"guardingUpper\":0,\"guardingLower\":0}")).guard().player().isEmpty());
        UUID npc = new UUID(0, 22);
        JsonArray references = new JsonArray();
        references.add(json("{\"source\":\"/traits/sentinel/guardedNPC\",\"kind\":\"guard\",\"status\":\"resolved\",\"npcUuid\":\"" + npc + "\"}"));
        CombatSettings guarding = settings(SentinelMapping.convert(json("{\"guardedNPC\":2}"), JsonNull.INSTANCE, references));
        assertEquals(npc, guarding.guard().npc().orElseThrow());
        JsonArray unresolved = new JsonArray();
        unresolved.add(json("{\"source\":\"/traits/sentinel/guardedNPC\",\"kind\":\"guard\",\"status\":\"missing\"}"));
        SentinelMapping.Result missing = SentinelMapping.convert(json("{\"guardedNPC\":999}"), JsonNull.INSTANCE, unresolved);
        assertTrue(settings(missing).guard().npc().isEmpty());
        assertEquals(999, missing.module().getAsJsonObject("preservedSettings").get("guardedNPC").getAsInt());
    }
    @Test void locationsConvert() {
        CombatSettings settings = settings(convert("{\"spawnPoint\":{\"world\":\"new\",\"x\":8,\"y\":65,\"z\":9,\"yaw\":1},\"avoidReturnPoint\":{\"world\":\"new\",\"x\":1,\"y\":2,\"z\":3}}"));
        assertEquals(List.of(new CombatSettings.Location("new", 8, 65, 9)), settings.respawn().locations());
        assertEquals(new CombatSettings.Location("new", 1, 2, 3), settings.avoidance().returnPoint().orElseThrow());
    }
    @Test void dropsConvertSimpleItemsWithTheirChancesAndPreserveTheRest() {
        SentinelMapping.Result result = convert("""
                {"drops":[{"type":"IRON_INGOT","amount":2},{"id":"minecraft:gold_ingot","count":3},{"type":"DIAMOND_SWORD","meta":{"enchants":{"sharp":5}}}],"dropChances":[0.5,0.25,1]}""");
        assertEquals(List.of(new CombatSettings.DropRule("minecraft:iron_ingot", 2, .5), new CombatSettings.DropRule("minecraft:gold_ingot", 3, .25)), settings(result).death().drops());
        assertTrue(result.module().getAsJsonObject("preservedSettings").has("drops"), "An item with data cannot be represented, so the original list stays");
    }
    @Test void outOfRangeAndSpecialValuesStayValidAndKeepTheOriginal() {
        SentinelMapping.Result result = convert("""
                {"range":500,"reach":40,"health":99999,"attackRate":0,"speed":100,"damage":-1,"armor":-1,"healRate":-5,"respawnTime":9999999,"accuracy":99,"chaseRange":-4,"death_xp":-3,"reaction_slowdown":100000,"enemyTargetTime":999999,"avoidRange":0,"projectileRange":0}""");
        CombatSettings settings = settings(result);
        assertEquals(64, settings.targeting().scanRange());
        assertEquals(6, settings.melee().reach());
        assertEquals(2048, settings.defense().maximumHealth());
        assertEquals(1, settings.melee().cooldownTicks());
        assertEquals(5, settings.pursuit().speed());
        assertEquals(0, settings.defense().armor());
        JsonObject preserved = result.module().getAsJsonObject("preservedSettings");
        for (String field : List.of("range", "reach", "health", "attackRate", "speed", "damage", "armor", "accuracy"))
            assertTrue(preserved.has(field), field);
        assertFalse(result.notes().isEmpty());
        assertEquals(result.notes().size(), result.module().getAsJsonObject("migrationReport").getAsJsonArray("notes").size());
    }
    @Test void malformedFieldsArePreservedInsteadOfRejectingTheImport() {
        SentinelMapping.Result result = convert("{\"health\":\"lots\",\"squad\":7,\"allTargets\":\"everything\",\"spawnPoint\":{\"world\":\"w\"},\"drops\":\"none\",\"guardingUpper\":\"x\",\"fightback\":\"yes\"}");
        assertDoesNotThrow(() -> settings(result));
        JsonObject preserved = result.module().getAsJsonObject("preservedSettings");
        for (String field : List.of("health", "squad", "allTargets", "spawnPoint", "drops", "guardingUpper", "fightback"))
            assertTrue(preserved.has(field), field);
    }
    @Test void aDamageOfZeroDisablesMelee() {
        assertFalse(settings(convert("{\"damage\":0}")).melee().enabled());
    }
    @Test void aHeldBowEnablesTheRangedAttack() {
        CombatSettings settings = settings(SentinelMapping.convert(json("{\"damage\":5}"), json("{\"hand\":{\"type\":\"BOW\"}}"), new JsonArray()));
        assertTrue(settings.ranged().enabled());
        assertEquals(5, settings.ranged().damage());
        assertFalse(settings(SentinelMapping.convert(json("{}"), json("{\"hand\":{\"type\":\"IRON_SWORD\"}}"), new JsonArray())).ranged().enabled());
    }
    @Test void statisticsAndUnsupportedSettingsAreKeptApart() {
        SentinelMapping.Result result = convert("{\"stats_punches\":23,\"stats_potionsThrow\":1,\"needsAmmo\":true,\"warning_text\":\"Halt\",\"worldguard_region\":\"keep\",\"brandNewSetting\":1,\"health\":40}");
        JsonObject module = result.module();
        assertEquals(23, module.getAsJsonObject("historicalStatistics").get("stats_punches").getAsInt());
        assertEquals(1, module.getAsJsonObject("historicalStatistics").get("stats_potionsThrow").getAsInt());
        JsonObject preserved = module.getAsJsonObject("preservedSettings");
        assertTrue(preserved.get("needsAmmo").getAsBoolean());
        assertEquals("Halt", preserved.get("warning_text").getAsString());
        assertEquals("keep", preserved.get("worldguard_region").getAsString());
        assertEquals(1, preserved.get("brandNewSetting").getAsInt());
        assertFalse(preserved.has("stats_punches"));
        assertFalse(preserved.has("health"), "A cleanly converted setting is not repeated");
        JsonObject report = module.getAsJsonObject("migrationReport");
        assertEquals("[\"health\"]", report.get("converted").toString());
        assertEquals(new HashSet<>(List.of("\"needsAmmo\"", "\"warning_text\"", "\"worldguard_region\"")), new HashSet<>(strings(report.getAsJsonArray("unsupported"))));
        assertEquals("[\"brandNewSetting\"]", report.get("unknown").toString());
    }
    private static List<String> strings(JsonArray array) { return array.asList().stream().map(Object::toString).toList(); }

    @Test void aNonObjectRecordIsPreservedAndDisabled() {
        SentinelMapping.Result result = SentinelMapping.convertRaw(new com.google.gson.JsonPrimitive("weird"), JsonNull.INSTANCE, new JsonArray());
        assertFalse(result.module().get("enabled").getAsBoolean());
        assertEquals("weird", result.module().get("preservedSettings").getAsString());
    }

    private static final String RECORD = """
            npc:
              '1':
                name: Guard
                uuid: 00000000-0000-0000-0000-000000000001
                traits:
                  type: PLAYER
                  location: {world: old, x: 1.25, y: 64, z: -2, yaw: 0, pitch: 0}
                  equipment: {hand: {type: BOW}}
                  sentinel:
                    health: 40
                    guardedNPC: 2
                    stats_punches: 23
                    needsAmmo: true
                    spawnPoint: {world: old, x: 8, y: 65, z: 9}
                    allTargets: {targets: [MONSTERS]}
              '2':
                name: Charge
                uuid: 00000000-0000-0000-0000-000000000002
                traits:
                  type: PLAYER
                  location: {world: old, x: 3, y: 64, z: 3, yaw: 0, pitch: 0}
            """;
    @Test void anImportConvertsStaysDisabledAndStillArchivesEverything() throws Exception {
        Path source = Files.writeString(root.resolve("saves.yml"), RECORD);
        CitizensImporter.Preview preview = new CitizensImporter().preview(source, root.resolve("out"), Map.of("old", "new"));
        JsonObject candidate = JsonDocuments.object(Files.readString(preview.directory().resolve("npcs/citizens/citizens-1.json")));
        JsonObject module = candidate.getAsJsonObject("modules").getAsJsonObject(CombatSettings.MODULE);
        assertFalse(module.get("enabled").getAsBoolean());
        assertFalse(module.get("reviewAccepted").getAsBoolean());
        NpcDefinition definition = NpcDefinition.parse(JsonDocuments.write(candidate));
        assertTrue(CombatSettings.active(definition).isEmpty());
        CombatSettings settings = CombatSettings.parse(candidate).orElseThrow();
        assertEquals(40, settings.defense().maximumHealth());
        assertTrue(settings.ranged().enabled());
        assertEquals(new UUID(0, 2), settings.guard().npc().orElseThrow());
        assertEquals("new", settings.respawn().locations().getFirst().world(), "World mapping applies to converted locations");
        assertEquals(23, module.getAsJsonObject("historicalStatistics").get("stats_punches").getAsInt());
        assertTrue(module.getAsJsonObject("preservedSettings").get("needsAmmo").getAsBoolean());
        JsonObject archived = JsonDocuments.object(Files.readString(preview.directory().resolve("records/1.json")));
        assertEquals(40, archived.getAsJsonObject("traits").getAsJsonObject("sentinel").get("health").getAsInt());
        assertEquals(40, candidate.getAsJsonObject("modules").getAsJsonObject("citizens:review").getAsJsonObject("sentinel").get("health").getAsInt());
        JsonObject report = JsonDocuments.object(Files.readString(preview.directory().resolve("reports/1.json"))).getAsJsonObject("fields");
        assertEquals("CONVERTED", report.get("/traits/sentinel/health").getAsString());
        assertEquals("UNSUPPORTED_PRESERVED", report.get("/traits/sentinel/needsAmmo").getAsString());
        assertEquals("UNSUPPORTED_PRESERVED", report.get("/traits/sentinel/stats_punches").getAsString());
        JsonObject counts = preview.summary().getAsJsonObject("sentinelMapping");
        assertTrue(counts.get("converted").getAsInt() >= 4);
        assertEquals(1, counts.get("unsupported").getAsInt());
        assertEquals(1, counts.get("statistics").getAsInt());
    }
    @Test void anAppliedCopyRewritesTheGuardToTheCopysIdentity() throws Exception {
        Path source = Files.writeString(root.resolve("saves.yml"), RECORD);
        CitizensImporter importer = new CitizensImporter();
        CitizensImporter.Preview preview = importer.preview(source, root.resolve("out"), Map.of("old", "new"));
        List<NpcDefinition> imported = new java.util.ArrayList<>();
        for (int id = 1; id <= 2; id++) imported.add(NpcDefinition.parse(Files.readString(preview.directory().resolve("npcs/citizens/citizens-" + id + ".json"))));
        ImportProjection.Result copy = ImportProjection.project(preview.id(), imported, List.of(), ImportProjection.Mode.DUPLICATE, "second");
        NpcDefinition guard = copy.definitions().stream().filter((NpcDefinition candidate) -> candidate.id().equals("citizens-1-second")).findFirst().orElseThrow();
        NpcDefinition charge = copy.definitions().stream().filter((NpcDefinition candidate) -> candidate.id().equals("citizens-2-second")).findFirst().orElseThrow();
        assertEquals(charge.uuid(), CombatSettings.parse(guard.json()).orElseThrow().guard().npc().orElseThrow());
        assertNotEquals(new UUID(0, 2), charge.uuid());
    }
}
