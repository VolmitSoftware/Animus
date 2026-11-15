package art.arcane.animus.api;

/**
 * What a {@link TrustedModule} receives in {@link TrustedModule#enable}. Registrations are accepted only during enable; the
 * services stay usable until the module stops, and answer UNAVAILABLE while it is suspended.
 */
public interface ModuleContext {
    /**
     * Creates, edits, removes and drives NPCs for this module, as a Bukkit plugin does through {@code AnimusNpcs}. Its
     * temporary NPCs and definition modules end when this module stops. {@code npcs().definitions()} reads and replaces
     * definitions.
     *
     * @return the module's NPC service
     * @since 1.3
     */
    NpcService npcs();
    /**
     * Action, condition and trigger registration during enable.
     *
     * @return the capability registrar
     */
    Capabilities.Registrar registrations();
    /**
     * Fires triggers this module registered, from any thread.
     *
     * @return the trigger publisher
     */
    Capabilities.TriggerPublisher triggers();
    /**
     * Bounded work on the module workers.
     *
     * @return the scheduler
     */
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
