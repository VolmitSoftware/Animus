package art.arcane.animus.core.migration;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Citizens' own say, npc, waypoints and wp lines, read the way Citizens 2.0.44's Util.runCommand rewrites them. */
class CitizensCommandLinesTest {
    private static JsonObject json(String text) {
        try { return JsonDocuments.object(text); } catch (IOException invalid) { throw new AssertionError(invalid); }
    }

    private static CitizensCommandLines.Mapped mapped(String line, boolean clicker) {
        CitizensCommandLines.Line read = CitizensCommandLines.read(line, clicker, "7");
        return assertInstanceOf(CitizensCommandLines.Mapped.class, read, line + " -> " + read);
    }
    private static JsonObject node(String line) { return mapped(line, true).node(); }
    private static String review(String line, boolean clicker) {
        CitizensCommandLines.Line read = CitizensCommandLines.read(line, clicker, "7");
        return assertInstanceOf(CitizensCommandLines.Review.class, read, line + " -> " + read).reason();
    }
    private static JsonObject action(String capability, String arguments) {
        return json("{\"type\":\"action\",\"capability\":\"" + capability + "\",\"arguments\":" + arguments + "}");
    }

    @Test void aSayLineSpeaksToTheClickerWhoeverRunsIt() {
        assertEquals(action("animus:say", "{\"text\":\"Scribe stamps a letter\"}"), node("say Scribe stamps a letter"));
        assertEquals(action("animus:say", "{\"text\":\"Scribe stamps a letter\"}"), mapped("say Scribe stamps a letter", false).node(), "a console line is the same speech");
        assertEquals(action("animus:say", "{\"text\":\"hi {player_name}\"}"), node("sayhi {player_name}"), "Citizens only checks that the line starts with say");
        assertEquals(Optional.empty(), mapped("say hi", true).note());
        assertInstanceOf(CitizensCommandLines.Plain.class, CitizensCommandLines.read("Say hi", true, "7"), "the check is case-sensitive");
        assertTrue(review("say   ", true).contains("no text"));
    }

    @Test void speakFollowsItsTargetRangeAndBubble() {
        assertEquals(action("animus:say", "{\"text\":\"Welcome in\"}"), node("npc speak Welcome in --target {player_name}"));
        assertEquals(action("animus:say", "{\"text\":\"Welcome in\"}"), node("npc speak \"Welcome in\" --target {player_name} --id 7"));
        assertEquals(action("animus:say", "{\"text\":\"Gather round\",\"audience\":\"nearby\",\"radius\":12.0}"), node("npc speak Gather round --range 12"));
        assertEquals(action("animus:say", "{\"text\":\"Hello\",\"audience\":\"nearby\",\"radius\":5.0}"), node("npc speak Hello"), "no target or range talks to bystanders in Citizens' chat range");
        assertEquals(action("animus:say", "{\"text\":\"Psst\",\"bubbleTicks\":60,\"chat\":false}"), node("npc speak Psst --bubble 3s"), "a bubble is a hologram line only");
        assertEquals(action("animus:say", "{\"text\":\"Psst\",\"bubbleTicks\":40,\"chat\":false}"), node("npc speak Psst --bubble 40"));
        assertEquals(action("animus:say", "{\"text\":\"Psst\",\"bubbleTicks\":30,\"chat\":false}"), node("npc speak Psst --bubble 30t"));
        CitizensCommandLines.Mapped far = mapped("npc speak Hear ye --range 100", true);
        assertEquals(64.0, far.node().getAsJsonObject("arguments").get("radius").getAsDouble());
        assertTrue(far.note().orElseThrow().contains("64"));
        CitizensCommandLines.Mapped both = mapped("npc speak Hear ye --target {player_name} --range 8", true);
        assertEquals("nearby", both.node().getAsJsonObject("arguments").get("audience").getAsString());
        assertTrue(both.note().isPresent());
        assertTrue(review("npc speak Hi --target Notch", true).contains("Notch"));
        assertTrue(review("npc speak Hi --bubble soon", true).contains("soon"));
        assertTrue(review("npc speak --target {player_name}", true).contains("no text"));
    }

    @Test void teleportsNeedTheClickerAsSender() {
        assertEquals(action("animus:teleport", "{\"to\":\"npc\"}"), node("npc tp"));
        assertEquals(action("animus:teleport", "{\"target\":\"npc\",\"to\":\"player\"}"), node("npc tphere"));
        assertEquals(action("animus:teleport", "{\"target\":\"npc\",\"to\":\"player\"}"), node("npc move --id 7"));
        assertTrue(review("npc tp", false).contains("console"));
        assertTrue(review("npc tphere cursor", true).contains("looks"));
        assertEquals(action("animus:teleport", "{\"target\":\"npc\",\"x\":10.5,\"y\":64.0,\"z\":-3.0}"), node("npc moveto 10.5:64:-3"));
        assertEquals(action("animus:teleport", "{\"target\":\"npc\",\"x\":1.0,\"y\":70.0,\"z\":2.0}"), mapped("npc moveto 1 70 2", false).node(), "moving the NPC needs no player");
        assertEquals(action("animus:teleport", "{\"target\":\"npc\",\"x\":1.0,\"y\":70.0,\"z\":2.0,\"yaw\":90.0}"), node("npc moveto --x 1 --y 70 --z 2 --yaw 90"));
        assertTrue(review("npc moveto 1:70:2:world_nether", true).contains("world"));
        assertTrue(review("npc moveto --y 80", true).contains("x, y and z"));
    }

    @Test void walkingTurningPosingAndAnimating() {
        assertEquals(action("animus:navigate", "{\"x\":12.0,\"y\":64.0,\"z\":-8.0}"), node("npc pathto 12 64 -8"));
        assertTrue(mapped("npc pathto 12 64 -8 -s", true).note().isPresent(), "straight-line walking is not kept");
        assertTrue(review("npc pathto me", true).contains("point"));
        assertEquals(action("animus:look", "{\"x\":1.0,\"y\":65.0,\"z\":3.0}"), node("npc rotate --towards 1,65,3"));
        assertEquals(action("animus:look", "{\"target\":\"player\"}"), node("npc rotate --toentity {player_name}"));
        assertEquals(action("animus:look", "{\"target\":\"player\"}"), node("npc rotate --toentity me"));
        assertTrue(review("npc rotate --toentity me", false).contains("console"));
        assertTrue(review("npc rotate --body 90", true).contains("yaw"));
        assertEquals(action("animus:pose", "{\"pose\":\"bow-low\"}"), node("npc pose --assume \"Bow Low\""), "named the way the poses trait import names it");
        assertEquals(action("animus:pose", "{\"pose\":\"wave_hi\"}"), node("npc pose --assume Wave_Hi"));
        assertTrue(review("npc pose --save wave", true).contains("pose"));
        assertEquals(action("animus:animate", "{\"animation\":\"swing\"}"), node("npc panimate ARM_SWING"));
        assertEquals(action("animus:animate", "{\"animation\":\"stand\"}"), node("npc panimate stop_sitting"));
        assertEquals(action("animus:animate", "{\"animation\":\"wake\"}"), node("npc panimate LEAVE_BED"));
        assertEquals(action("animus:animate", "{\"animation\":\"use_item\"}"), node("npc panimate START_USE_MAINHAND_ITEM"));
        assertEquals(action("animus:animate", "{\"animation\":\"use_item\"}"), node("npc panimate EAT_FOOD"), "the same table as waypoint animation triggers");
        assertTrue(review("npc panimate START_ELYTRA", true).contains("START_ELYTRA"));
    }

    @Test void renameAndDespawnBecomeAnimusCommands() {
        assertEquals(action("animus:command", "{\"command\":\"animus npc rename citizens-7 \\\"Sir {player_name}\\\"\",\"as\":\"console\"}"), node("npc rename Sir {player_name}"));
        assertEquals(action("animus:command", "{\"command\":\"animus despawn citizens-7\",\"as\":\"console\"}"), mapped("npc despawn", false).node());
        assertTrue(review("npc despawn 12", true).contains("12"));
        assertEquals(action("animus:command", "{\"command\":\"animus npc rename citizens-7 \\\"Old Tom\\\"\",\"as\":\"console\"}"), node("npc rename \"Old Tom\""), "Citizens drops the quotes");
        assertTrue(review("npc rename The \"Great", true).contains("quote"));
    }

    @Test void otherNpcsWaypointsAndUnknownSubcommandsGoToReview() {
        assertTrue(review("npc speak Hi --id 12", true).contains("NPC 12"));
        assertTrue(review("npc glowing --color red", true).contains("npc glowing"));
        assertTrue(review("npc speak", true).contains("no text"));
        assertTrue(review("waypoints provider wander", true).contains("waypoint"));
        assertTrue(review("wp disableteleport", false).contains("waypoint"));
        for (String plain : new String[] {"give {player_name} minecraft:bread", "npcs list", "spawn", "wpx", "npc"})
            assertInstanceOf(CitizensCommandLines.Plain.class, CitizensCommandLines.read(plain, true, "7"), plain + " is not one of Citizens' own lines");
    }
}
