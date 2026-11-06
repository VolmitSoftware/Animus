package art.arcane.animus.core;

import art.arcane.animus.core.behavior.catalog.BehaviorPath;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.storage.DocumentStore;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * What one committed change touched: the document kinds it wrote and the behavior ids whose sources it deleted on
 * purpose. {@code rollback} is true when older revisions were restored on purpose.
 */
public record CatalogChange(Set<DocumentKind> kinds, Set<String> removedBehaviors, boolean rollback) {
    public CatalogChange {
        kinds = Set.copyOf(kinds);
        removedBehaviors = Set.copyOf(removedBehaviors);
    }

    /** The kinds and deleted behaviors of committed transactions, read from their rollback receipts. A path now absent was deleted. */
    public static CatalogChange of(DocumentStore store, List<String> transactions, boolean rollback) throws IOException {
        Set<DocumentKind> kinds = EnumSet.noneOf(DocumentKind.class);
        Set<String> removed = new TreeSet<>();
        for (String transaction : transactions) {
            for (String path : store.paths(transaction)) {
                // The enabled server behaviors are read with the behavior catalog.
                if (path.equals(art.arcane.animus.core.behavior.catalog.ServerBehaviorList.PATH)) { kinds.add(DocumentKind.BEHAVIOR); continue; }
                Optional<DocumentKind> kind = DocumentKind.forPath(path);
                if (kind.isEmpty()) continue;
                kinds.add(kind.get());
                if ((kind.get() == DocumentKind.BEHAVIOR || kind.get() == DocumentKind.PACK) && store.read(path) == null) {
                    try { removed.add(BehaviorPath.idFor(path)); }
                    catch (IllegalArgumentException notBehavior) { /* A pack manifest is not a behavior source. */ }
                }
            }
        }
        return new CatalogChange(kinds, removed, rollback);
    }

    public boolean empty() { return kinds.isEmpty() && removedBehaviors.isEmpty(); }

    /** The same change without kinds the caller has already reloaded. */
    public CatalogChange without(DocumentKind... handled) {
        Set<DocumentKind> rest = EnumSet.noneOf(DocumentKind.class);
        rest.addAll(kinds);
        Arrays.asList(handled).forEach(rest::remove);
        return new CatalogChange(rest, removedBehaviors, rollback);
    }
}
