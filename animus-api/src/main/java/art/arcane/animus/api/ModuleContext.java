package art.arcane.animus.api;

public interface ModuleContext {
    DefinitionService definitions();
    Capabilities.Registrar registrations();
    Capabilities.TriggerPublisher triggers();
    ModuleScheduler scheduler();
    /** Registers startup resources for reverse-order cleanup; registration closes after enable returns. */
    void onClose(AutoCloseable resource);
}
