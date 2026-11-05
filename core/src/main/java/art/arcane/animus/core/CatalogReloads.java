package art.arcane.animus.core;

import java.util.function.Consumer;

/** Reloads the catalogs a committed change touched. The platform registers one with {@link AnimusRuntime#catalogs(CatalogReloads)}; call it on the file lane. */
@FunctionalInterface
public interface CatalogReloads {
    CatalogReloads NONE = (CatalogChange change, Consumer<String> problems) -> {};

    default void reload(CatalogChange change) { reload(change, (String problem) -> {}); }

    /** {@code problems} hears each refusal the synchronous reloads report, such as an NPC set kept because one file is invalid. */
    void reload(CatalogChange change, Consumer<String> problems);
}
