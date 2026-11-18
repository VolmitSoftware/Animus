package art.arcane.animus.core.command;

import art.arcane.animus.api.Capabilities.Availability;
import art.arcane.animus.api.Capabilities.Descriptor;
import art.arcane.animus.api.Capabilities.Key;
import art.arcane.animus.api.Capabilities.Kind;
import art.arcane.animus.api.Platform;
import art.arcane.animus.core.extension.ExtensionRuntime;
import art.arcane.animus.core.extension.ModuleHost;
import art.arcane.animus.core.extension.MutationLane;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModuleCapabilityListingTest {
    private static Availability entry(String namespace, String name, boolean available, String reason) {
        return new Availability(new Descriptor(new Key(namespace, name), Kind.ACTION, "Test", "{}", Set.of(Platform.PAPER), false), available, reason);
    }

    @Test void countsSeparatelyAndListsModulesFirstInPlainWords() {
        ModuleCapabilityListing listing = ModuleCapabilityListing.of(List.of(entry("animus", "say", true, "available"), entry("zeta", "act", false, "dependency-unavailable"), entry("alpha", "act", true, "available")));
        assertEquals(2, listing.modules());
        assertEquals(1, listing.builtIn());
        assertEquals(List.of("zeta:act | action | unavailable: dependency unavailable", "alpha:act | action | available", "animus:say | action | available"), listing.rows());
    }

    @Test void headersKeepModuleCountsApartFromBuiltIns() {
        ModuleCapabilityListing listing = ModuleCapabilityListing.of(List.of(entry("animus", "say", true, "available"), entry("alpha", "act", true, "available")));
        assertEquals("Module capabilities: 1; built-in: 1; page 2", listing.header(2));
        assertEquals("1 module capability, 1 built in", listing.counts());
        assertEquals("0 module capabilities, 0 built in", ModuleCapabilityListing.of(List.of()).counts());
    }

    @Test void aModuleCallAnswersInWordsNotStatusCodes() {
        assertEquals("[Animus]: animus:actionbar did not run: Only an action can run here, not a condition",
                art.arcane.animus.core.text.RichText.plain(ContentCommands.outcome("animus:actionbar", false,
                        art.arcane.animus.api.Outcome.rejected(art.arcane.animus.api.Outcome.Status.UNAVAILABLE, "condition-unavailable", "Only an action can run here, not a condition"))));
        assertEquals("[Animus]: town:near is true", art.arcane.animus.core.text.RichText.plain(ContentCommands.outcome("town:near", false, art.arcane.animus.api.Outcome.ok(true))));
        assertEquals("[Animus]: town:missing was not found", art.arcane.animus.core.text.RichText.plain(ContentCommands.outcome("town:missing", true,
                new art.arcane.animus.api.Outcome<>(art.arcane.animus.api.Outcome.Status.NOT_FOUND, java.util.Optional.empty(), List.of()))));
    }

    @Test void aCapabilityOfTheOtherKindIsNamedAsSuch() {
        List<Availability> all = List.of(entry("animus", "actionbar", true, "available"));
        assertEquals(java.util.Optional.of("animus:actionbar is an action; run it with /animus modules action"),
                ContentCommands.Modules.wrongKind(all, new Key("animus", "actionbar"), false));
        assertEquals(java.util.Optional.of("No capability named town:nothing; /animus modules capabilities lists them"),
                ContentCommands.Modules.wrongKind(all, new Key("town", "nothing"), true));
        assertEquals(java.util.Optional.empty(), ContentCommands.Modules.wrongKind(all, new Key("animus", "actionbar"), true));
    }

    @Test void moduleStatusReadsAsSentencesWithoutRecordDumps() {
        ExtensionRuntime.Status snapshot = new ExtensionRuntime.Status(ExtensionRuntime.State.READY, "none", null, null,
                new ModuleHost.InvocationStatus(3, 1, 0, 0, 0, 0, 0, 0, false));
        MutationLane.Status files = new MutationLane.Status(5, 2, 1, 0, 0, 0, 0, false, true, false, false, 0, 0, 0, 0, MutationLane.CleanupState.NOT_SCHEDULED, "none", true);
        ContentCommands.Modules.Report report = ContentCommands.Modules.report(snapshot, 0, ModuleCapabilityListing.of(List.of(entry("animus", "say", true, "available"))), 0,
                List.of("protection hook: town"), files);
        assertEquals("Modules ready: 0 installed, 0 module capabilities, 1 built in, 1 call running", report.headline());
        String all = report.headline() + "\n" + String.join("\n", report.details());
        assertTrue(all.contains("Module calls: 3 admitted, 1 running, 0 timed out, 0 unanswered, 0 results waiting, 0 delivering, 0 over budget, 0 slow"), all);
        assertTrue(all.contains("File work: 5 admitted, 2 queued, 1 running, 0 results waiting, 0 delivering, 0 failed"), all);
        assertTrue(all.contains("protection hook: town"), all);
        for (String leak : List.of("InvocationStatus", "Status[", "READY", "=", "none"))
            assertFalse(all.contains(leak), leak + " in " + all);
        ExtensionRuntime.Status failed = new ExtensionRuntime.Status(ExtensionRuntime.State.FAILED, "module-startup: IOException", null, null,
                new ModuleHost.InvocationStatus(0, 0, 0, 0, 0, 0, 0, 0, true));
        String headline = ContentCommands.Modules.report(failed, 2, ModuleCapabilityListing.of(List.of()), 0, List.of(), files).headline();
        assertEquals("Modules failed: 2 installed, 0 module capabilities, 0 built in, 0 calls running; modules did not start, the server log has the cause", headline);
    }
}
