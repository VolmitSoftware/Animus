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
        Administration.Inspection inspection = new Administration.Inspection(bait, "{}", "{}", List.of(), Optional.empty());
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
}
