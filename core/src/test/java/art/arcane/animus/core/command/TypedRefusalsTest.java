package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** A refusal names the value as the command took it, never the schema path it was checked at. */
class TypedRefusalsTest {
    @Test void aNewNpcsIdFolderOrNameIsNamedAsTyped() {
        assertEquals("id takes 1–64 lowercase letters, digits, _ or -, not Guard",
                AnimusCommandTree.made("$.id: expected 1–64 lowercase letters, digits, _ or -", "Guard", "", ""));
        assertEquals("folder takes the folder under npcs/: empty, or up to 8 names, not Town/Hall",
                AnimusCommandTree.made("$.group: expected the folder under npcs/: empty, or up to 8 names", "guard", "Town/Hall", ""));
        assertEquals("name takes at most 256 characters without control characters",
                AnimusCommandTree.made("$.name: expected at most 256 characters without control characters", "guard", "", "x".repeat(300)));
        assertEquals("An NPC named guard already exists", AnimusCommandTree.made("An NPC named guard already exists", "guard", "", ""));
    }

    @Test void aManualHitAnswersInWordsOnEveryPlatform() {
        assertEquals("guard hit for 2 damage; health 20 to 18", CombatControl.attacked("guard", "APPLIED", 2, 20.0, 18.0));
        assertEquals("guard's hit did nothing: out of reach", CombatControl.attacked("guard", "OUT_OF_REACH", 0, 20.0, 20.0));
        assertEquals("guard's hit did nothing: unavailable chunk, the line to the target crosses chunks another region owns",
                CombatControl.attacked("guard", "UNAVAILABLE_CHUNK the line to the target crosses chunks another region owns", 0, null, null));
        assertEquals("guard's hit did nothing: the target took no damage", CombatControl.attacked("guard", "NO_CHANGE", 0, 20.0, 20.0));
    }

    @Test void aSettingIsNamedAsTypedWithItsRangeInWords() {
        assertEquals("range takes 1-32 blocks, not 99", CommandGroup.typed("$.modules.animus:mount.range: expected 1-32 blocks", "modules.animus:mount.range", "range", "99"));
        assertEquals("maximum must be from 1 to 2048, not 5000",
                CommandGroup.typed("$.modules.animus:health.maximumHealth: must be within 1.0..2048.0; $.name: x", "modules.animus:health.maximumHealth", "maximum", "5000"));
        assertEquals("NPCs do not ride players", CommandGroup.problem("$.modules.animus:mount.entity: NPCs do not ride players", "modules.animus:mount.entity"));
        assertEquals("Something else broke", CommandGroup.typed("Something else broke", "modules.animus:mount.range", "range", "99"));
        assertEquals("chance takes 1-100", CommandGroup.field("$.modules.animus:schedule.locations[2].chance: expected 1-100"));
        assertEquals("pose takes standing, sneaking, sleeping or sitting", CommandGroup.field("$.modules.animus:schedule.locations[0].pose: expected standing, sneaking, sleeping or sitting; $.x: y"));
    }
}
