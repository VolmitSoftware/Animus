package art.arcane.animus.core;

/** Reloads the catalogs a committed change touched. The platform registers one with {@link AnimusRuntime#catalogs(CatalogReloads)}; call it on the file lane. */
@FunctionalInterface
public interface CatalogReloads {
    CatalogReloads NONE = (CatalogChange change) -> {};

    void reload(CatalogChange change);
}
