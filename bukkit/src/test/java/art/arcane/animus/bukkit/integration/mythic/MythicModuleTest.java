package art.arcane.animus.bukkit.integration.mythic;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.extension.CapabilityRefusal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MythicModuleTest {
    private final FakeMythic mythic = new FakeMythic(Set.of("Greet"), Set.of());
    private final MythicModule module = new MythicModule(mythic, mythic);
    private final UUID player = UUID.fromString("abcd0000-0000-0000-0000-000000000000");

    private Capabilities.Invocation call(MythicHost.Npc npc, String arguments, boolean preview) {
        return new Capabilities.Invocation(new NpcHandle(npc.uuid()), Optional.of(player), arguments, preview, () -> false, Optional.of("town:king"));
    }

    private String cast(MythicHost.Npc npc, String arguments) throws Exception {
        java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
        module.enable(new RecordingContext(result, call(npc, arguments, false)));
        return result.get();
    }

    @Test void unknownSkillsAreRefusedWhenTheBehaviorLoads() {
        assertEquals(List.of(), module.validate(MythicModule.CAST, "{\"skill\":\"Greet\"}"));
        assertEquals("unknown-skill", module.validate(MythicModule.CAST, "{\"skill\":\"Nope\"}").getFirst().code());
    }

    @Test void theNpcCastsFromWhereItStandsAtTheTriggeringPlayer() throws Exception {
        MythicHost.Npc king = mythic.add("king", Optional.empty(), true);
        assertEquals("{\"cast\":true}", cast(king, "{\"skill\":\"Greet\",\"power\":2}"));
        assertEquals("{\"cast\":true}", cast(king, "{\"skill\":\"Greet\",\"caster\":\"player\"}"));
        assertEquals(List.of("npc-cast Greet by king at 1,64 for abcd x2.0", "player-cast Greet by Steve"), mythic.calls);
    }

    @Test void previewsCastNothingAndADespawnedNpcIsRefused() throws Exception {
        MythicHost.Npc king = mythic.add("king", Optional.empty(), true);
        java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
        module.enable(new RecordingContext(result, call(king, "{\"skill\":\"Greet\"}", true)));
        assertEquals("{\"cast\":false}", result.get());
        MythicHost.Npc away = mythic.add("away", Optional.empty(), false);
        CapabilityRefusal refused = assertThrows(CapabilityRefusal.class, () -> cast(away, "{\"skill\":\"Greet\"}"));
        assertEquals(Outcome.Status.UNAVAILABLE, refused.status());
        assertTrue(mythic.calls.isEmpty());
    }

    /** Runs the registered action once with {@code invocation}. */
    private record RecordingContext(java.util.concurrent.CompletableFuture<String> result, Capabilities.Invocation invocation) implements art.arcane.animus.api.ModuleContext {
        @Override public Capabilities.Registrar registrations() {
            return new Capabilities.Registrar() {
                @Override public void action(Capabilities.Descriptor descriptor, Capabilities.Action action) {
                    assertEquals(MythicModule.CAST, descriptor.key());
                    assertTrue(descriptor.privileged(), "a skill can run commands, so casting one needs the command allow-list");
                    try { action.run(invocation).thenAccept(result::complete); }
                    catch (CapabilityRefusal refusal) { throw refusal; }
                    catch (Exception failure) { result.completeExceptionally(failure); }
                }
                @Override public void condition(Capabilities.Descriptor descriptor, Capabilities.Condition condition) { fail("no conditions"); }
                @Override public void trigger(Capabilities.Descriptor descriptor) { fail("no triggers"); }
            };
        }
        @Override public art.arcane.animus.api.DefinitionService definitions() { throw new UnsupportedOperationException(); }
        @Override public Capabilities.TriggerPublisher triggers() { throw new UnsupportedOperationException(); }
        @Override public art.arcane.animus.api.ModuleScheduler scheduler() { throw new UnsupportedOperationException(); }
        @Override public void onClose(AutoCloseable resource) {}
        @Override public void onInteraction(art.arcane.animus.api.InteractionNotice.Listener listener) {}
        @Override public art.arcane.animus.api.ImportConverters.Registrar imports() { throw new UnsupportedOperationException(); }
        @Override public art.arcane.animus.api.Integrations.Registrar integrations() { throw new UnsupportedOperationException(); }
    }
}
