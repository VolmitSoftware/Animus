package art.arcane.animus.core.command;

import art.arcane.animus.core.admin.Administration;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.text.ChatLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What /animus inspect sends a player. */
class InspectLinesTest {
    @Test void aPlayerReadsTheFightsLineWithoutRawColourCodes() throws Exception {
        NpcDefinition bait = NpcDefinition.parse("{\"schemaVersion\":2,\"revision\":2,\"uuid\":\"" + UUID.randomUUID() + "\",\"id\":\"bait\",\"group\":\"test\",\"name\":\"Bait\","
                + "\"type\":\"minecraft:player\",\"spawned\":true,\"position\":{\"world\":\"world\",\"x\":0.5,\"y\":61,\"z\":6.5,\"yaw\":0,\"pitch\":0}}");
        Administration.Inspection inspection = new Administration.Inspection(bait, "{}", "{}", List.of(), Optional.empty(), Optional.empty());
        List<String> told = new ArrayList<>();
        CommandActor player = new CommandActor() {
            @Override public boolean permitted(String node) { return true; }
            @Override public void tell(String message) { told.add(message); }
            @Override public void plain(String line) { told.add(line); }
            @Override public void rich(ChatLine line) {}
            @Override public Optional<UUID> player() { return Optional.of(UUID.randomUUID()); }
            @Override public boolean reply(Runnable action) { action.run(); return true; }
        };
        AnimusCommandTree.inspect(player, inspection, "Runtime bait: active; health=11", Optional.of("time spawned 1m, spawns 1, damage taken 49"));
        String text = String.join("\n", told);
        assertTrue(text.contains("Fights: time spawned 1m, spawns 1, damage taken 49"), text);
        assertTrue(told.stream().noneMatch((String line) -> line.contains("&")), "a told line is shown as written: " + text);
    }

    @Test void theHeadLineNamesThePlayerWhoOwnsIt() throws Exception {
        UUID alex = UUID.fromString("0000000a-0000-4000-8000-0000000000a1");
        NpcDefinition stall = NpcDefinition.parse("{\"schemaVersion\":2,\"revision\":1,\"uuid\":\"" + UUID.randomUUID() + "\",\"id\":\"stall\",\"group\":\"\",\"name\":\"Stall\","
                + "\"type\":\"minecraft:player\",\"spawned\":true,\"owner\":\"" + alex + "\",\"position\":{\"world\":\"world\",\"x\":0,\"y\":61,\"z\":6,\"yaw\":0,\"pitch\":0}}");
        assertTrue(new Administration.Inspection(stall, "{}", "{}", List.of(), Optional.empty(), Optional.of("Alex")).summary().endsWith(" | owner Alex"));
        assertTrue(new Administration.Inspection(stall, "{}", "{}", List.of(), Optional.empty(), Optional.empty()).summary().endsWith(" | owner " + alex), "an unknown name shows the UUID");
        com.google.gson.JsonObject unowned = stall.json();
        unowned.remove("owner");
        NpcDefinition mayor = NpcDefinition.parse(unowned.toString());
        assertTrue(!new Administration.Inspection(mayor, "{}", "{}", List.of(), Optional.empty(), Optional.empty()).summary().contains("owner"), "a server NPC names none");
    }
}
