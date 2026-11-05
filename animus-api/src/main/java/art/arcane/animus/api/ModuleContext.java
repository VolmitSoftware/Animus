package art.arcane.animus.api;

public interface ModuleContext {
    DefinitionService definitions();
    Capabilities.Registrar registrations();
    Capabilities.TriggerPublisher triggers();
    ModuleScheduler scheduler();
    /** Registers startup resources for reverse-order cleanup; registration closes after enable returns. */
    void onClose(AutoCloseable resource);
    /** Subscribes to accepted NPC interactions during enable; at most 8 listeners per module. @since 1.1 */
    void onInteraction(InteractionNotice.Listener listener);
    /** Import converters for another plugin's data, registered during enable. @since 1.1 */
    ImportConverters.Registrar imports();
    /** Engine-called integration hooks, registered during enable. @since 1.1 */
    Integrations.Registrar integrations();
}
