package art.arcane.animus.core.editor;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.npc.NpcDefinition;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * What the editor API needs from the platform. Bukkit and the mod loaders each implement it once; everything else about the
 * API lives in core. Methods without a thread note may be called from any thread and must not block.
 */
public interface EditorHost {
    /**
     * Whether the issuing admin still holds a permission node: {@code animus.editor.pair} for the token, {@code animus.editor.commands}
     * for its {@code apply:commands} scope. {@code UNKNOWN} keeps what the node grants for at most
     * {@link EditorApi#UNCONFIRMED_ADMIN} after the last confirmation.
     */
    enum Admin { ADMIN, NOT_ADMIN, UNKNOWN }

    /** One entry of {@code GET /v1/capabilities}. */
    record Feature(boolean available, String reason) {
        public static Feature yes() { return new Feature(true, null); }

        public static Feature no(String reason) { return new Feature(false, reason); }
    }

    /** {@code paper}, {@code folia}, {@code spigot}, {@code purpur}, {@code fabric}, {@code forge}, {@code neoforge} or {@code other}. */
    String platform();

    String serverName();

    /** The shared file lane. Rejects with {@code RejectedExecutionException} when full, which the API answers as busy. */
    Executor files();

    /** Platform features for {@code GET /v1/capabilities}: combat, rigs, gloss, placeholderapi and the like. */
    Map<String, Feature> features();

    /** Module ids and states for {@code GET /v1/capabilities}. */
    List<Map.Entry<String, String>> modules();

    /**
     * What {@code GET /v1/lookups} lists for the editor's completion: registry ids by kind ({@code items}, {@code blocks},
     * {@code sounds}, {@code particles}, {@code entityTypes}, {@code enchantments}, {@code effects}, {@code attributes},
     * {@code worlds}), command names and PlaceholderAPI identifiers.
     */
    record Lookups(Map<String, List<String>> registries, List<String> commands, List<String> papi) {
        public static final Lookups NONE = new Lookups(Map.of(), List.of(), List.of());

        public Lookups { registries = Map.copyOf(registries); commands = List.copyOf(commands); papi = List.copyOf(papi); }
    }

    /** The lists for {@code GET /v1/lookups}, on any thread; a platform without them answers {@link Lookups#NONE}. */
    default Lookups lookups() { return Lookups.NONE; }

    /** The live capability registry the generated behavior schema describes. */
    List<Capabilities.Availability> capabilities();

    boolean online(UUID player);

    /** Reports whether the player still holds the permission node, online or offline where the platform can tell, on any thread. */
    void permission(UUID player, String node, Consumer<Admin> result);

    /**
     * {@code [behaviors.actions] rawTextBehaviors}. Flags and quest stages are shared by a behavior namespace, so any behavior in
     * the namespace of one of these can steer its raw command text, and writing one needs {@code apply:commands}.
     */
    default List<String> rawTextBehaviors() { return List.of(); }

    /**
     * Whether {@code [behaviors.actions]} lets any behavior or namespace run commands. While it does, an editor import could
     * install a document that runs them, so import steps need {@code apply:commands}.
     */
    default boolean commandGrants() { return true; }

    /** Problems that stop an NPC definition from activating, checked where the platform requires. */
    CompletionStage<List<String>> activationProblems(NpcDefinition definition);

    /**
     * Called on the file lane after an apply, undo or import committed. The platform reloads every catalog of these kinds:
     * NPCs, behaviors, shops, GUIs, holograms and rigs. {@code removedBehaviors} names behaviors whose sources the change
     * deleted, so the catalog forgets them. {@code rollback} is true when older revisions were restored on purpose. Completes
     * once every touched catalog is live, with the refusals the reload reported; chain on it, never wait for it on the lane.
     */
    CompletionStage<List<String>> applied(Set<DocumentKind> kinds, Set<String> removedBehaviors, boolean rollback);

    /** Where in-game notices of editor events go. */
    EditorNoticeChannel notices();

    /** Viewer-private previews, or empty with the reason they are unavailable. */
    Optional<PreviewPresenter> previews();

    String previewsUnavailable();

    /** Loaded-chunk reads for {@code GET /v1/terrain/...}, or empty when the platform has none. */
    default Optional<EditorTerrain> terrain() { return Optional.empty(); }

    /** Shows candidate NPCs to one player only. */
    interface PreviewPresenter {
        /**
         * Presents (or replaces) a preview for the viewer. Completes once the platform accepted it. {@code ended} receives
         * {@code player-left} when the viewer leaves; the API also ends it on expiry and on token close.
         */
        CompletionStage<Void> present(String previewId, UUID viewer, List<NpcDefinition> npcs, Consumer<String> ended);

        void end(String previewId);
    }
}
