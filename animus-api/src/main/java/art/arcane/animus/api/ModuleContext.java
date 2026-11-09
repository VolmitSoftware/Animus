package art.arcane.animus.api;

public interface ModuleContext {
    DefinitionService definitions();
    Capabilities.Registrar registrations();
    Capabilities.TriggerPublisher triggers();
    ModuleScheduler scheduler();
    /**
     * Registers startup resources for reverse-order cleanup; registration closes after enable returns.
     *
     * @param resource resource closed when the module stops
     */
    void onClose(AutoCloseable resource);
    /**
     * Subscribes to accepted NPC interactions during enable; at most 8 listeners per module.
     *
     * @param listener receives each accepted interaction
     * @since 1.1
     */
    void onInteraction(InteractionNotice.Listener listener);
    /**
     * Import converters for another plugin's data, registered during enable.
     *
     * @return the converter registrar
     * @since 1.1
     */
    ImportConverters.Registrar imports();
    /**
     * Engine-called integration hooks, registered during enable.
     *
     * @return the integration registrar
     * @since 1.1
     */
    Integrations.Registrar integrations();
}
