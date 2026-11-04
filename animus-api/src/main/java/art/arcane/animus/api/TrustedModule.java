package art.arcane.animus.api;

/** Startup-loaded trusted extension. Binary replacement requires restart; portable packs are separate. */
public interface TrustedModule {
    ModuleManifest manifest();
    void enable(ModuleContext context) throws Exception;
    void disable() throws Exception;
}
