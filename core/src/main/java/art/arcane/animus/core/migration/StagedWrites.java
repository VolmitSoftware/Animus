package art.arcane.animus.core.migration;

import art.arcane.animus.core.storage.DocumentStore;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The documents the next {@link CitizensDropIn#applyStaged()} writes, by data folder path, read from the staged previews of
 * the parts it would install: the Citizens application with its dtlTraders shops, the Shopkeepers save and the Denizen
 * scripts. Nothing is installed. The edits apply makes to installed NPCs, binding shops and behaviors, are left out.
 */
final class StagedWrites {
    private StagedWrites() {}

    /** The same parts {@code apply} decides to install from {@code state}. */
    static Map<String, String> of(DocumentStore store, JsonObject state) throws IOException {
        boolean citizensPending = state.has("applicationId") && !CitizensDropIn.applied(state);
        Map<String, String> writes = new TreeMap<>();
        if (citizensPending) {
            writes.putAll(ImportApplications.documents(store, state.get("importId").getAsString(), state.get("applicationId").getAsString()));
            if (state.has("shopImportId")) writes.putAll(new ShopImporter().documents(store, state.get("shopImportId").getAsString()));
        }
        Optional<String> shopkeepers = ShopkeepersDropIn.staged(state) ? ShopkeepersDropIn.importId(state) : Optional.empty();
        if (shopkeepers.isPresent()) writes.putAll(new ShopkeepersImporter().documents(store, shopkeepers.get()));
        if (DenizenDropIn.staged(state) && (citizensPending || CitizensDropIn.applied(state)))
            writes.putAll(DenizenImporter.documents(store, state.getAsJsonObject("denizen").get("importId").getAsString()));
        return writes;
    }
}
