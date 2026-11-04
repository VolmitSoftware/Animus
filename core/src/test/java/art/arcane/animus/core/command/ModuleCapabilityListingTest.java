package art.arcane.animus.core.command;

import art.arcane.animus.api.Capabilities.Availability;
import art.arcane.animus.api.Capabilities.Descriptor;
import art.arcane.animus.api.Capabilities.Key;
import art.arcane.animus.api.Capabilities.Kind;
import art.arcane.animus.api.Platform;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModuleCapabilityListingTest {
    private static Availability entry(String namespace, String name, boolean available, String reason) {
        return new Availability(new Descriptor(new Key(namespace, name), Kind.ACTION, "Test", "{}", Set.of(Platform.PAPER), false), available, reason);
    }

    @Test void countsSeparatelyAndListsModulesFirst() {
        ModuleCapabilityListing listing = ModuleCapabilityListing.of(List.of(entry("animus", "say", true, "available"), entry("zeta", "act", false, "dependency-unavailable"), entry("alpha", "act", true, "available")));
        assertEquals(2, listing.modules());
        assertEquals(1, listing.builtIn());
        assertEquals(List.of("zeta:act | ACTION | available=false | dependency-unavailable", "alpha:act | ACTION | available=true | available", "animus:say | ACTION | available=true | available"), listing.rows());
    }

    @Test void headersKeepModuleCountsApartFromBuiltIns() {
        ModuleCapabilityListing listing = ModuleCapabilityListing.of(List.of(entry("animus", "say", true, "available"), entry("alpha", "act", true, "available")));
        assertEquals("Module capabilities: 1; built-in: 1; page 2", listing.header(2));
        assertEquals("capabilities=1 | built-in=1", listing.counts());
        assertEquals("capabilities=0 | built-in=0", ModuleCapabilityListing.of(List.of()).counts());
    }
}
