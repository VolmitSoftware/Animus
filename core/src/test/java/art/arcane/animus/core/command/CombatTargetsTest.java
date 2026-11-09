package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Combat commands name players and NPCs as people type them: a player name, an NPC id, {@code @me}, or a UUID. */
class CombatTargetsTest {
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000a"), BODYGUARD = UUID.fromString("00000000-0000-0000-0000-00000000000b"),
            ME = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private final WorldCommands.Targets targets = new WorldCommands.Targets(Optional.of(ME),
            (String name) -> Optional.ofNullable(Map.of("Steve", STEVE).get(name)), (String id) -> Optional.ofNullable(Map.of("bodyguard", BODYGUARD).get(id)));

    @Test void namesIdsAndUuidsResolve() {
        assertEquals(Optional.of(STEVE), targets.resolve("Steve"));
        assertEquals(Optional.of(BODYGUARD), targets.resolve("bodyguard"));
        assertEquals(Optional.of(ME), targets.resolve("@me"));
        UUID mob = UUID.randomUUID();
        assertEquals(Optional.of(mob), targets.resolve(mob.toString()));
        assertEquals(Optional.empty(), targets.resolve("nobody"));
        assertEquals(Optional.empty(), new WorldCommands.Targets(Optional.empty(), (String name) -> Optional.empty(), (String id) -> Optional.empty()).resolve("@me"),
                "the console is nobody");
    }

    @Test void aPlayerNameWinsOverAnNpcIdOfTheSameName() {
        WorldCommands.Targets shared = new WorldCommands.Targets(Optional.empty(), (String name) -> name.equals("guard") ? Optional.of(STEVE) : Optional.empty(),
                (String id) -> id.equals("guard") ? Optional.of(BODYGUARD) : Optional.empty());
        assertEquals(Optional.of(STEVE), shared.resolve("guard"));
    }

    @Test void repliesNameTheTargetAsTyped() {
        String reply = "Combat bodyguard: APPLIED Guarding " + art.arcane.animus.core.text.ChatLine.shortId(STEVE.toString());
        assertEquals("Combat bodyguard: APPLIED Guarding Steve", WorldCommands.Targets.named(reply, STEVE, "Steve"));
        assertEquals("Combat bodyguard: APPLIED Guarding you", WorldCommands.Targets.named(reply, STEVE, "you"));
        assertEquals("Combat bodyguard: APPLIED Guarding Steve", WorldCommands.Targets.named("Combat bodyguard: APPLIED Guarding " + STEVE, STEVE, "Steve"));
    }

    @Test void completionLabelsTargetsByName() {
        UUID mob = UUID.randomUUID();
        assertEquals(List.of("stop", "@me", "Steve", "bodyguard", mob.toString()), WorldCommands.Targets.offered(true, true, List.of("Steve"), List.of("bodyguard"), List.of(mob)));
        assertEquals(List.of("Steve", "bodyguard", mob.toString()), WorldCommands.Targets.offered(false, false, List.of("Steve"), List.of("bodyguard"), List.of(mob)));
    }
}
