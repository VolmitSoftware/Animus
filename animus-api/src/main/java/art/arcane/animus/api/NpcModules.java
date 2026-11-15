package art.arcane.animus.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Modules a plugin or Java module adds to NPC definitions, the way Citizens plugins added traits. A module owns one key
 * in a definition's {@code modules} object, such as {@code "shop:prices"}, holds its data there as a JSON object, and
 * gets hooks for the NPCs that carry that key: when one becomes active, when a player clicks it, and on a tick interval.
 * <p>
 * The data is saved with the NPC like any other module, kept unchanged while the registering plugin is missing, and
 * written with {@link NpcService#edit} or {@link NpcService#updateModule}. Changing only a plugin module's data leaves
 * the live NPC as it is: it is not shown again, and routes, seats and runtime changes continue.
 * <p>
 * A registration ends when the plugin disables or the Java module stops.
 *
 * @since 1.3
 */
public final class NpcModules {
    /** The most ticks between two tick hooks. */
    public static final int MAXIMUM_TICK_INTERVAL = 1200;

    private NpcModules() {}

    /**
     * One module type. The namespaces {@code animus}, {@code minecraft}, {@code mythic}, {@code citizens},
     * {@code shopkeepers} and {@code denizen} are reserved. {@link Registrar#register} checks the values.
     *
     * @param key definition key, {@code namespace:name}
     * @param description one line about what the module does, at most 2048 bytes
     * @param tickInterval ticks between tick hooks, 1 to {@link #MAXIMUM_TICK_INTERVAL}, or 0 for no tick hook
     */
    public record Descriptor(Capabilities.Key key, String description, int tickInterval) {
        public Descriptor { Objects.requireNonNull(key, "key"); Objects.requireNonNull(description, "description"); }
    }

    /**
     * One NPC carrying the module, as a hook sees it.
     *
     * @param npc the NPC
     * @param id its id
     * @param temporary true for an NPC that is never saved
     * @param data the module's data as a JSON object, read when the hook was called
     */
    public record Context(NpcHandle npc, String id, boolean temporary, String data) {
        public Context { Objects.requireNonNull(npc, "npc"); Objects.requireNonNull(id, "id"); Objects.requireNonNull(data, "data"); }
    }

    /**
     * What a plugin implements. Every hook is optional. Hooks run synchronously on a server thread, so they must be quick
     * and must not wait on a {@link NpcService} result; start the work and return. A hook that throws is reported in the
     * console once a minute and does not stop the others.
     */
    public interface Module {
        /**
         * Checks the module's data before Animus saves a definition whose data for this key changed, from the service,
         * commands or the web editor. Any thread; quick and free of side effects. An ERROR diagnostic refuses the save.
         *
         * @param data the new data as a JSON object
         * @return problems, empty when the data is fine
         */
        default List<Diagnostic> validate(String data) { return List.of(); }

        /**
         * The NPC became active: it spawned, loaded with its chunk or came back after dying. Runs on the NPC's thread.
         *
         * @param npc the NPC
         * @throws Exception reported, never rethrown
         */
        default void spawned(Context npc) throws Exception {}

        /**
         * A player clicked the NPC and no {@code VirtualNpcInteractionEvent} listener cancelled it. Runs on the player's
         * thread, after the click's behavior trigger, whether or not a behavior used the click.
         *
         * @param npc the NPC
         * @param player the player who clicked
         * @param attack true for a left click
         * @throws Exception reported, never rethrown
         */
        default void clicked(Context npc, UUID player, boolean attack) throws Exception {}

        /**
         * The descriptor's tick interval passed while the NPC is active. Runs on the NPC's thread.
         *
         * @param npc the NPC
         * @throws Exception reported, never rethrown
         */
        default void ticked(Context npc) throws Exception {}
    }

    /** Registers modules for the plugin or Java module that owns the service. */
    public interface Registrar {
        /**
         * Registers a module type. Answers INVALID for a reserved namespace, a blank or long description or a tick
         * interval out of range, and CONFLICT when another plugin or Java module has the key. Any thread.
         *
         * @param descriptor the module type
         * @param module its hooks
         * @return the key once registered
         */
        Outcome<Capabilities.Key> register(Descriptor descriptor, Module module);
    }
}
