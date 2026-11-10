package art.arcane.animus.core.onboarding;

import art.arcane.animus.core.migration.CitizensDropIn;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.text.ChatLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OnboardingTest {
    @TempDir Path root;
    private static final String SAVES = """
            npc:
              '1':
                uuid: 00000000-0000-0000-0000-000000000001
                name: Guard
                traits:
                  type: PLAYER
                  spawned: true
                  location: {world: world, x: 10.5, y: 64, z: -3.25, yaw: 90, pitch: 0}
            """;

    private CitizensDropIn.Host host() { return new CitizensDropIn.Host(Optional.of(root.resolve("plugins")), () -> false, () -> false, () -> false); }

    @Test void anEmptyServerHintsEachAdminOnceUntilDismissedAndTheFlagOutlivesARestart() throws Exception {
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            Onboarding onboarding = new Onboarding(store, npcs, host());
            UUID admin = UUID.randomUUID(), other = UUID.randomUUID();
            ChatLine hint = onboarding.joinHint(admin).orElseThrow();
            assertEquals("[Animus]: Animus is running with no NPCs yet [Setup] [Dismiss]", SetupGuideTest.plain(List.of(hint)).getFirst());
            assertEquals(List.of("run_command /animus setup", "run_command /animus setup dismiss=true"), SetupGuideTest.clicks(List.of(hint)));
            assertTrue(onboarding.joinHint(admin).isEmpty(), "one hint per admin per server run");
            assertTrue(onboarding.joinHint(other).isPresent());
            assertFalse(onboarding.dismissed());
            onboarding.dismiss();
            assertTrue(Files.isRegularFile(store.resolve(Onboarding.PATH)));
            Onboarding restarted = new Onboarding(store, npcs, host());
            assertTrue(restarted.dismissed());
            assertTrue(restarted.joinHint(UUID.randomUUID()).isEmpty());
        }
    }

    @Test void citizensDataIsNamedUntilTheImportIsDoneAndThenAServerWithNpcsGetsNoHint() throws Exception {
        Files.writeString(Files.createDirectories(root.resolve("plugins/Animus/imports")).resolve("saves.yml"), SAVES);
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            Onboarding onboarding = new Onboarding(store, npcs, host());
            assertEquals("[Animus]: Found imports/saves.yml; nothing imported yet [Setup] [Dismiss]",
                    SetupGuideTest.plain(List.of(onboarding.joinHint(UUID.randomUUID()).orElseThrow())).getFirst());
            CitizensDropIn flow = new CitizensDropIn(store, npcs, host());
            flow.preview("reject", Optional.empty(), Map.of(), false);
            assertEquals("[Animus]: Citizens import staged, not installed yet [Setup] [Dismiss]",
                    SetupGuideTest.plain(List.of(onboarding.joinHint(UUID.randomUUID()).orElseThrow())).getFirst());
            flow.applyStaged();
            assertTrue(onboarding.joinHint(UUID.randomUUID()).isEmpty(), "NPCs exist and the import is done");
            SetupState state = onboarding.state(true, 0);
            assertEquals(1, state.npcs());
            assertEquals(CitizensDropIn.Phase.DONE, state.migration().phase());
        }
    }

    @Test void theStartupNoticeReachesAdminsWithASetupChip() throws Exception {
        Files.writeString(Files.createDirectories(root.resolve("plugins/Animus/imports")).resolve("saves.yml"), SAVES);
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            String notice = new Onboarding(store, npcs, host()).startupNotice().orElseThrow();
            assertTrue(notice.startsWith("Citizens migration: found imports/saves.yml"), notice);
            ChatLine line = Onboarding.adminNotice(notice);
            assertTrue(SetupGuideTest.plain(List.of(line)).getFirst().endsWith(" [Setup]"));
            assertEquals(List.of("run_command /animus setup"), SetupGuideTest.clicks(List.of(line)));
        }
    }
}
