package art.arcane.animus.core;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Reloads the catalogs a committed change touched. The platform registers one with {@link AnimusRuntime#catalogs(CatalogReloads)}; call it on the file lane. */
@FunctionalInterface
public interface CatalogReloads {
    CatalogReloads NONE = (CatalogChange change, Consumer<String> problems) -> {};

    default void reload(CatalogChange change) { reload(change, (String problem) -> {}); }

    /** {@code problems} hears each refusal the synchronous reloads report, such as an NPC set kept because one file is invalid. */
    void reload(CatalogChange change, Consumer<String> problems);

    /**
     * {@link #reload(CatalogChange, Consumer)}, and a result that completes once every touched catalog is live. Some reloads
     * queue their own work behind the calling task on the file lane, so a caller on the lane chains on the result and never
     * waits for it there. Implementations without queued work complete at once.
     */
    default CompletableFuture<Void> reloaded(CatalogChange change, Consumer<String> problems) {
        reload(change, problems);
        return CompletableFuture.completedFuture(null);
    }
}
