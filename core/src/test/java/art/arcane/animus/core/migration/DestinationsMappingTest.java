package art.arcane.animus.core.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.schedule.ScheduleSettings;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** NPC Destinations saves, shaped from what its trait writes through Citizens' storage, become an animus:schedule. */
class DestinationsMappingTest {
    @TempDir Path root;
    private static final String TEXTURE = "ZXlKMFpYaDBkWEpsY3lJNmV5SlRTMGxPSWpwN2ZYMTk=";
    private static final String SAVES = """
            npc:
              '3':
                uuid: 00000000-0000-0000-0000-000000000003
                name: Baker
                traits:
                  type: PLAYER
                  spawned: true
                  location: {world: old_town, x: 0.5, y: 64, z: 0.5, yaw: 0, pitch: 0}
                  waypoints: {provider: npcdestinations}
                  npcdestinations:
                    PauseForPlayers: 5
                    PauseTimeout: 25
                    blocksUnderSurface: 0
                    Locations: ['30:64:30:18000']
                    OpensGates: true
                    OpensWoodDoors: false
                    OpensMetalDoors: true
                    TeleportOnFailedStartLoc: true
                    TeleportOnNoPath: false
                    MaxDistFromDestination: 2
                    enabledplugins: [Animations]
                    Destinations:
                      citizens_Swim: true
                      citizens_NewPathFinder: true
                      citizens_AvoidWater: true
                      citizens_DistanceMargin: 1.0
                      citizens_PathDistanceMargin: 1.0
                      maxprocessingtime: -1
                      '0':
                        locationid: 7f0c2c52-6f0e-4f43-a3a3-9d1f8f8a0c12
                        Location: {world: old_town, x: 10, y: 64, z: 4, pitch: '0.0', yaw: '90.0'}
                        MaxDistance: 2.0
                        Probability: {ChancePercent: 0, Min_Time: 0, Max_Time: 0}
                        TimeOfDay: 1000
                        WanderSettings: {Wait_Maximum: 8, Wait_Minimum: 3, Wandering_Region: '', Wandering_Distance: 6.0, Wandering_UseBlocks: false}
                        AliasName: Bakery Counter
                        UseBlockSetting: false
                        WeatherFlag: 0
                        Items:
                          Head: {==: org.bukkit.inventory.ItemStack, DataVersion: 4440, id: 'minecraft:leather_helmet', count: 1, schema_version: 1,
                                 components: {'minecraft:dyed_color': '16711680'}}
                          Hand: {==: org.bukkit.inventory.ItemStack, v: 3465, type: BREAD, amount: 3}
                          Clear: true
                        Skin: {Name: Baker, UUID: '', ApplyOnArrival: false, MetaData: '%s', Signature: c2ln}
                        Commands: {arrival: ['/say The bakery is open', '']}
                        Pause: {Distance: 5, TimeOut: 25, Type: ALL}
                        PluginSettings: ''
                      '1':
                        locationid: 7f0c2c52-6f0e-4f43-a3a3-9d1f8f8a0c13
                        Location: {world: old_town, x: -20, y: 63, z: 8, pitch: '0.0', yaw: '0.0'}
                        MaxDistance: 2.0
                        Probability: {ChancePercent: 40, Min_Time: 60, Max_Time: 120}
                        TimeOfDay: 13000
                        WanderSettings: {Wait_Maximum: 0, Wait_Minimum: 0, Wandering_Region: market, Wandering_Distance: 0.0, Wandering_UseBlocks: false}
                        AliasName: ''
                        UseBlockSetting: false
                        WeatherFlag: 2
                        Skin: {Name: '', UUID: '', ApplyOnArrival: false, MetaData: '', Signature: ''}
                        Pause: {Distance: 5, TimeOut: 25, Type: ALL}
                        PluginSettings:
                          Animations: {Setting: SLEEP, Interval: 500, Volume: '1.0', Pitch: '0.0'}
                          JobsReborn: {JobName: Baker, JobMax: 2}
                      '2':
                        Location: {world: elsewhere, x: 0, y: 64, z: 0}
                        TimeOfDay: 6000
                      '3':
                        Location: {world: old_town, x: 0, y: 64, z: 0}
                        TimeOfDay: -1
            """.formatted(TEXTURE);

    @Test void destinationsBecomeAScheduleThatWaitsForReview() throws Exception {
        Path source = Files.writeString(root.resolve("saves.yml"), SAVES);
        CitizensImporter importer = new CitizensImporter();
        CitizensImporter.Preview preview = importer.preview(source, root.resolve("out"), Map.of("old_town", "town"));
        assertEquals(1, preview.valid(), preview.summary().toString());
        assertEquals(1, preview.skins());
        String warnings = preview.summary().getAsJsonArray("migrationWarnings").toString();
        assertTrue(warnings.contains("a destination in elsewhere was left out"), warnings);
        assertTrue(warnings.contains("WorldGuard region market"), warnings);
        assertTrue(warnings.contains("commandNamespaces"), warnings);
        assertFalse(warnings.contains("waypoint provider npcdestinations is not supported"), warnings);
        JsonObject report = JsonDocuments.object(Files.readString(preview.directory().resolve("reports/3.json")));
        JsonObject fields = report.getAsJsonObject("fields");
        assertEquals("CONVERTED", fields.get("/traits/npcdestinations/Destinations/0/TimeOfDay").getAsString());
        assertEquals("CONVERTED", fields.get("/traits/npcdestinations/Destinations/0/Items/Head/components/minecraft:dyed_color").getAsString());
        assertEquals("CONVERTED", fields.get("/traits/npcdestinations/Destinations/1/PluginSettings/Animations/Setting").getAsString());
        assertEquals("REQUIRES_INTEGRATION", fields.get("/traits/npcdestinations/Destinations/1/PluginSettings/JobsReborn/JobName").getAsString());
        assertEquals("REQUIRES_REVIEW", fields.get("/traits/npcdestinations/Destinations/1/WanderSettings/Wandering_Region").getAsString());
        assertEquals("UNSUPPORTED_PRESERVED", fields.get("/traits/npcdestinations/Destinations/0/Pause/Type").getAsString());
        assertEquals("UNSUPPORTED_PRESERVED", fields.get("/traits/npcdestinations/OpensMetalDoors").getAsString());
        assertEquals("UNSUPPORTED_PRESERVED", fields.get("/traits/npcdestinations/Destinations/3/TimeOfDay").getAsString());
        assertEquals("CONVERTED", fields.get("/traits/waypoints/provider").getAsString());
        try (DocumentStore store = new DocumentStore(root.resolve("out"))) {
            importer.apply(store, preview.id());
            JsonObject json = NpcDefinition.parse(store.read("npcs/town/citizens-3.json")).json();
            JsonObject module = json.getAsJsonObject("modules").getAsJsonObject(ScheduleSettings.MODULE);
            assertFalse(module.get("enabled").getAsBoolean());
            assertFalse(module.get("reviewAccepted").getAsBoolean());
            assertFalse(json.getAsJsonObject("modules").has("animus:navigation"));
            assertTrue(json.getAsJsonObject("movement").get("openDoors").getAsBoolean());
            assertFalse(json.getAsJsonObject("movement").getAsJsonObject("stuck").get("teleport").getAsBoolean());

            CitizensDropIn.acceptCategory(json, "schedules");
            ScheduleSettings schedule = ScheduleSettings.parse(json).orElseThrow();
            assertEquals(3, schedule.locations().size(), "two destinations and the old x:y:z:time location");
            ScheduleSettings.Location bakery = schedule.locations().getFirst();
            assertEquals("bakery-counter", bakery.name().orElseThrow());
            assertEquals(1000, bakery.time());
            assertEquals(List.of(10, 64, 4), List.of(bakery.x(), bakery.y(), bakery.z()));
            assertEquals(90, bakery.yaw().getAsDouble());
            assertEquals(6, bakery.wander().orElseThrow().radius());
            assertEquals(new ScheduleSettings.Seconds(3, 8), bakery.wander().orElseThrow().pause());
            assertEquals("minecraft:leather_helmet", bakery.equipment().get(EquipmentSlot.HEAD).id());
            assertEquals("[minecraft:dyed_color=16711680]", bakery.equipment().get(EquipmentSlot.HEAD).components().orElseThrow());
            assertEquals(3, bakery.equipment().get(EquipmentSlot.MAIN_HAND).count());
            assertTrue(bakery.clearEquipment());
            assertTrue(bakery.skinOnDeparture());
            assertEquals(List.of("say The bakery is open"), bakery.commands());
            assertTrue(store.read("skins/" + bakery.skin().orElseThrow() + ".json").contains(TEXTURE));
            ScheduleSettings.Location night = schedule.locations().get(1);
            assertEquals(13000, night.time());
            assertEquals(40, night.chance());
            assertEquals(ScheduleSettings.Weather.STORM, night.weather());
            assertEquals(new ScheduleSettings.Seconds(60, 120), night.stay().orElseThrow());
            assertEquals(NpcPose.SLEEPING, night.pose().orElseThrow());
            assertTrue(night.yaw().isEmpty());
            assertEquals(18000, schedule.locations().get(2).time());
        }
    }

    @Test void importedSchedulesMustArriveOff() throws Exception {
        Path source = Files.writeString(root.resolve("saves.yml"), SAVES);
        CitizensImporter importer = new CitizensImporter();
        CitizensImporter.Preview preview = importer.preview(source, root.resolve("out"), Map.of("old_town", "town"));
        Path staged = preview.directory().resolve("npcs/town/citizens-3.json");
        JsonObject json = JsonDocuments.object(Files.readString(staged));
        json.getAsJsonObject("modules").getAsJsonObject(ScheduleSettings.MODULE).addProperty("enabled", true);
        Files.writeString(staged, JsonDocuments.write(json));
        try (DocumentStore store = new DocumentStore(root.resolve("out"))) {
            assertThrows(java.io.IOException.class, () -> importer.apply(store, preview.id()));
        }
    }

    @Test void aTraitWithoutDestinationsSchedulesNothing() throws Exception {
        DestinationsMapping.Result result = DestinationsMapping.convert(JsonDocuments.object("{\"PauseForPlayers\":5}"), "minecraft:player", "world", Map.of());
        assertTrue(result.module().isEmpty());
        assertTrue(DestinationsMapping.convert(null, "minecraft:player", "world", Map.of()).warnings().getFirst().contains("not a mapping"));
    }

    @Test void skinsAndPosesOnOtherBodiesStayForReview() throws Exception {
        JsonObject trait = JsonDocuments.object("""
                {"Destinations":{"0":{"Location":{"world":"world","x":1,"y":64,"z":1},"TimeOfDay":500,
                  "Skin":{"Name":"Baker","MetaData":"abc","Signature":"def","ApplyOnArrival":true},
                  "PluginSettings":{"Animations":{"Setting":"SIT"}}}}}
                """);
        DestinationsMapping.Result result = DestinationsMapping.convert(trait, "minecraft:villager", "world", Map.of());
        JsonObject location = result.module().orElseThrow().getAsJsonArray("locations").get(0).getAsJsonObject();
        assertFalse(location.has("skin"));
        assertFalse(location.has("pose"));
        assertTrue(result.skins().isEmpty());
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, result.notes().get("/traits/npcdestinations/Destinations/0/Skin").status());
    }
}
