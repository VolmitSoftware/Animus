package art.arcane.animus.core.command;

import art.arcane.animus.api.Capabilities.Availability;
import art.arcane.animus.core.behavior.builtin.BuiltinCapabilities;
import java.util.ArrayList;
import java.util.List;

/** Module capabilities list and count apart from built-ins, so installing nothing still reads as zero modules. */
public record ModuleCapabilityListing(int modules, int builtIn, List<String> rows) {
    public static ModuleCapabilityListing of(List<Availability> capabilities) {
        List<String> external = new ArrayList<>(), builtIn = new ArrayList<>();
        for (Availability capability : capabilities) {
            String row = capability.descriptor().key() + " | " + capability.descriptor().kind() + " | available=" + capability.available() + " | " + capability.reason();
            (capability.descriptor().key().namespace().equals(BuiltinCapabilities.NAMESPACE) ? builtIn : external).add(row);
        }
        List<String> rows = new ArrayList<>(external);
        rows.addAll(builtIn);
        return new ModuleCapabilityListing(external.size(), builtIn.size(), List.copyOf(rows));
    }

    public String header(int page) { return "Module capabilities: " + modules + "; built-in: " + builtIn + "; page " + page; }
    public String counts() { return "capabilities=" + modules + " | built-in=" + builtIn; }
}
