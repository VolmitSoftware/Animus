package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.extension.CapabilityRefusal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandPlaceholdersTest {
    private final List<String> asked = new ArrayList<>();

    private UnaryOperator<String> values(Map<String, String> filled) {
        return (String token) -> { asked.add(token); return filled.getOrDefault(token, token); };
    }

    @Test void fillsEachPlaceholderOnItsOwn() {
        String filled = CommandPlaceholders.fill("eco give Alex %vault_eco_balance% %player_level%", values(Map.of("%vault_eco_balance%", "12.50", "%player_level%", "7")), false);
        assertEquals("eco give Alex 12.50 7", filled);
        assertEquals(List.of("%vault_eco_balance%", "%player_level%"), asked, "each token is filled separately, so one value cannot join the next");
    }

    @Test void aPlaceholderNoPluginKnowsStaysAsWritten() {
        assertEquals("say %unknown_thing% 50% off", CommandPlaceholders.fill("say %unknown_thing% 50% off", values(Map.of()), false));
        assertEquals("say 100%", CommandPlaceholders.fill("say 100%", values(Map.of()), false));
        assertTrue(asked.contains("%unknown_thing%"));
    }

    @Test void aFilledValueOutsideTheSafeSetStopsTheCommand() {
        for (String value : List.of("[VIP] Alex", "Alex @a", "a;b", "§cRed", "x/y", "a b")) {
            CapabilityRefusal refused = assertThrows(CapabilityRefusal.class,
                    () -> CommandPlaceholders.fill("lp user Alex parent add %luckperms_primary_group_name%", values(Map.of("%luckperms_primary_group_name%", value)), false), value);
            assertEquals(Outcome.Status.INVALID, refused.status());
            assertEquals("arguments-invalid", refused.diagnostics().getFirst().code());
        }
        assertEquals("give Alex minecraft:stone 1", CommandPlaceholders.fill("give Alex %item% 1", values(Map.of("%item%", "minecraft:stone")), false));
        assertEquals("tp Alex -12.5 64 +3", CommandPlaceholders.fill("tp Alex %x% 64 %z%", values(Map.of("%x%", "-12.5", "%z%", "+3")), false));
    }

    @Test void aRawTextBehaviorRefusesOnlyNewLinesControlCharactersAndALeadingSlash() {
        assertEquals("say [VIP] Alex", CommandPlaceholders.fill("say %prefix% Alex", values(Map.of("%prefix%", "[VIP]")), true));
        for (String value : List.of("one\ntwo", "bell\u0007")) {
            CapabilityRefusal refused = assertThrows(CapabilityRefusal.class, () -> CommandPlaceholders.fill("say %line%", values(Map.of("%line%", value)), true));
            assertEquals("arguments-invalid", refused.diagnostics().getFirst().code());
        }
        CapabilityRefusal slashed = assertThrows(CapabilityRefusal.class, () -> CommandPlaceholders.fill("%start% Alex", values(Map.of("%start%", "/op")), true));
        assertEquals("arguments-invalid", slashed.diagnostics().getFirst().code());
    }

    @Test void textWithoutAPairIsNeverAsked() {
        assertEquals("spawn", CommandPlaceholders.fill("spawn", values(Map.of()), false));
        assertTrue(asked.isEmpty());
    }
}
