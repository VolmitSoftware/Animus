package art.arcane.animus.core.runtime;

import art.arcane.animus.core.npc.NpcDefinition;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The appearance rules both runtimes share, so Paper and Folia present the same NPC the same way. */
class NpcPresentationTest {
    private static final Set<String> TYPES = Set.of("minecraft:player", "minecraft:horse", "minecraft:armor_stand");
    private static final String WORLD = UUID.randomUUID().toString();
    private static final DefinitionSpec.Items<String> ITEMS = (item, path, errors) -> item.id();

    private static DefinitionSpec<String> spec(String type, String extra) throws Exception {
        return DefinitionSpec.parse(NpcDefinition.parse("""
                {"schemaVersion":1,"revision":1,"uuid":"00000000-0000-0000-0000-000000000001","id":"guard",
                 "group":"test","name":"Captain of the Northern Watch","type":"%s","spawned":true,
                 "position":{"world":"world","x":1,"y":64,"z":2,"yaw":0,"pitch":0}%s}
                """.formatted(type, extra)), TYPES, ignored -> WORLD, ITEMS);
    }

    @Test void longNamesAreAcceptedOnEveryBodyAndSkinsOnlyOnPlayers() throws Exception {
        DefinitionSpec<String> horse = spec("minecraft:horse", "");
        assertDoesNotThrow(() -> NpcPresentation.validateChange(horse, Optional.of("A name far longer than any profile allows"), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> NpcPresentation.validateChange(horse, Optional.empty(), Optional.of("a".repeat(64))));
        assertThrows(IllegalArgumentException.class, () -> NpcPresentation.validateChange(horse, Optional.of(" "), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> NpcPresentation.validateChange(horse, Optional.of("bad\nname"), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> NpcPresentation.validateChange(horse, Optional.of("x".repeat(257)), Optional.empty()));
        assertDoesNotThrow(() -> NpcPresentation.validateChange(spec("minecraft:player", ""), Optional.empty(), Optional.of("a".repeat(64))));
    }

    @Test void inspectionReportsTheDefinitionAppearance() throws Exception {
        DefinitionSpec<String> posed = spec("minecraft:player", ",\"pose\":\"sneaking\",\"hologram\":{\"lines\":[\"Guard\",\"Ask me\"]}");
        String described = NpcPresentation.describe(posed, 2, true);
        assertTrue(described.contains("pose=sneaking"), described);
        assertTrue(described.contains("equipment=2"), described);
        assertTrue(described.contains("text-lines=2"), described);
        assertFalse(described.contains("presentation=unavailable"), described);
        assertTrue(NpcPresentation.describe(posed, 0, false).contains("presentation=unavailable on this server build"));
    }

    @Test void definitionEquipmentStartsEmptyWithoutItems() throws Exception {
        assertTrue(NpcPresentation.equipment(spec("minecraft:player", ""), (String item) -> item).isEmpty());
    }
}
