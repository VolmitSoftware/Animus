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

    @Test void aColouredNameShowsItsColoursNotItsCodes() throws Exception {
        NpcDefinition trader = NpcDefinition.parse("{\"schemaVersion\":2,\"revision\":1,\"uuid\":\"" + UUID.randomUUID() + "\",\"id\":\"dock-trader\",\"group\":\"\","
                + "\"name\":\"&bDock Trader\",\"type\":\"minecraft:villager\",\"spawned\":true,\"position\":{\"world\":\"world\",\"x\":0,\"y\":61,\"z\":6,\"yaw\":0,\"pitch\":0}}");
        Administration.Inspection inspection = new Administration.Inspection(trader, "{}", "{}", List.of(), Optional.empty(), Optional.empty());
        List<ChatLine> rich = new ArrayList<>();
        List<String> told = new ArrayList<>();
        CommandActor player = new CommandActor() {
            @Override public boolean permitted(String node) { return true; }
            @Override public void tell(String message) { told.add(message); }
            @Override public void rich(ChatLine line) { rich.add(line); }
            @Override public Optional<UUID> player() { return Optional.of(UUID.randomUUID()); }
            @Override public boolean reply(Runnable action) { action.run(); return true; }
        };
        AnimusCommandTree.inspect(player, inspection, "Runtime dock-trader: active", Optional.empty());
        String head = rich.getFirst().visible();
        assertTrue(head.contains("Dock Trader") && !head.contains("&b"), head);
        java.util.List<net.kyori.adventure.text.Component> parts = new ArrayList<>();
        collect(rich.getFirst().component(), parts);
        net.kyori.adventure.text.Component name = parts.stream().filter((net.kyori.adventure.text.Component part) -> plain(part).contains("Dock Trader")).findFirst().orElseThrow();
        net.kyori.adventure.text.Component type = parts.stream().filter((net.kyori.adventure.text.Component part) -> plain(part).contains("minecraft:villager")).findFirst().orElseThrow();
        assertTrue(name.color() != null && !name.color().equals(type.color()), "the name's colour stops at the name: " + name.color() + " then " + type.color());
        CommandActor console = new CommandActor() {
            @Override public boolean permitted(String node) { return true; }
            @Override public void tell(String message) { told.add(art.arcane.animus.core.text.RichText.plain(art.arcane.animus.core.text.Palette.reply(message))); }
            @Override public Optional<UUID> player() { return Optional.empty(); }
            @Override public boolean reply(Runnable action) { action.run(); return true; }
        };
        AnimusCommandTree.inspect(console, inspection, "Runtime dock-trader: active", Optional.empty());
        assertTrue(told.getFirst().contains("Dock Trader") && !told.getFirst().contains("&b"), told.getFirst());
    }

    private static void collect(net.kyori.adventure.text.Component component, java.util.List<net.kyori.adventure.text.Component> out) {
        if (component instanceof net.kyori.adventure.text.TextComponent text && !text.content().isEmpty()) out.add(component);
        for (net.kyori.adventure.text.Component child : component.children()) collect(child, out);
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return component instanceof net.kyori.adventure.text.TextComponent text ? text.content() : "";
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
