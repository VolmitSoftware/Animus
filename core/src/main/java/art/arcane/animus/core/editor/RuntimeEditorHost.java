package art.arcane.animus.core.editor;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.CatalogChange;
import art.arcane.animus.core.extension.ExtensionRuntime;
import art.arcane.animus.core.extension.ModuleHost;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.runtime.ViewerPreviews;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The editor host every platform shares: it reaches the persisted NPC runtime, behaviors, modules and GUIs through their
 * core interfaces. A platform supplies only its name, extra features and the admin permission check; previews come
 * from the NPC runtime's viewer-private presentation.
 *
 * @param <P> the platform's online player
 */
public final class RuntimeEditorHost<P> implements EditorHost {
    /**
     * What the platform passes in. Applied changes reload through the runtime's {@link AnimusRuntime#catalogs()}; {@code terrain}
     * may be null. {@code permitted} checks a node for an online player on their owner. {@code offlinePermission} answers for a
     * player who is not online, from the server's operator list and permission plugin where it can; it runs on the asking
     * thread, never the server thread, and answers {@code UNKNOWN} when it cannot tell. {@code standing} reads where an online player
     * stands; it runs on that player's owner and may throw when the position cannot be read.
     */
    public record Parts<P>(AnimusRuntime runtime, Executor files, NpcRuntime<P, ?> living, ExtensionRuntime extensions, String platform, String serverName, Supplier<Map<String, Feature>> features,
                           Supplier<Lookups> lookups, BiPredicate<P, String> permitted, EditorNoticeChannel notices, Consumer<String> log, EditorTerrain terrain,
                           BiFunction<UUID, String, Admin> offlinePermission, Function<P, Optional<Standing>> standing) {
        public Parts {
            Objects.requireNonNull(runtime); Objects.requireNonNull(files); Objects.requireNonNull(living); Objects.requireNonNull(lookups); Objects.requireNonNull(notices); Objects.requireNonNull(log);
            Objects.requireNonNull(permitted); Objects.requireNonNull(offlinePermission); Objects.requireNonNull(standing);
        }
    }

    /** How long a standing read may wait for the player's owner; a Folia task that never runs answers empty after this. */
    private static final long STANDING_SECONDS = 2;

    private final Parts<P> parts;

    public RuntimeEditorHost(Parts<P> parts) { this.parts = Objects.requireNonNull(parts); }

    @Override public String platform() { return parts.platform(); }

    @Override public String serverName() { return parts.serverName(); }

    @Override public Executor files() { return parts.files(); }

    @Override public Map<String, Feature> features() { return parts.features().get(); }
    @Override public art.arcane.animus.core.skin.SkinSigner skinSigner() { return parts.runtime().skinSigner(); }

    @Override public Lookups lookups() { return parts.lookups().get(); }
    @Override public Optional<String> playerName(UUID player) { return parts.runtime().playerName(player); }

    @Override public Map<UUID, String> knownPlayers() { return parts.runtime().knownPlayers(); }

    @Override public List<Map.Entry<String, String>> modules() {
        if (parts.extensions() == null) return List.of();
        return parts.extensions().modules().stream().limit(256)
                .map((ModuleHost.ModuleStatus module) -> Map.entry(module.manifest().id(), module.state().name().toLowerCase(java.util.Locale.ROOT))).toList();
    }

    @Override public List<Capabilities.Availability> capabilities() { return parts.extensions() == null ? List.of() : parts.extensions().capabilities(); }

    @Override public boolean online(UUID player) { return parts.living().players().online(player); }

    @Override public boolean commandGrants() {
        try {
            art.arcane.animus.core.config.BuiltinActionSettings actions = parts.runtime().behaviorSettings().actions();
            return !actions.commandBehaviors().isEmpty() || !actions.commandNamespaces().isEmpty();
        } catch (IllegalStateException stopped) { return true; }
    }

    @Override public List<String> rawTextBehaviors() {
        try { return parts.runtime().behaviorSettings().actions().rawTextBehaviors(); }
        catch (IllegalStateException stopped) { return List.of(); }
    }

    @Override public void permission(UUID player, String node, Consumer<Admin> result) {
        boolean delivered = parts.living().players().deliver(player, (P online) -> result.accept(parts.permitted().test(online, node) ? Admin.ADMIN : Admin.NOT_ADMIN));
        if (delivered) return;
        Admin offline;
        try { offline = parts.offlinePermission().apply(player, node); }
        catch (RuntimeException failure) { offline = Admin.UNKNOWN; }
        result.accept(offline == null ? Admin.UNKNOWN : offline);
    }

    @Override public CompletionStage<Optional<Standing>> standing(UUID player) {
        CompletableFuture<Optional<Standing>> result = new CompletableFuture<>();
        boolean delivered = parts.living().players().deliver(player, (P online) -> {
            Optional<Standing> read;
            try { read = parts.standing().apply(online); }
            catch (RuntimeException unreadable) { read = Optional.empty(); }
            result.complete(read == null ? Optional.empty() : read);
        });
        if (!delivered) result.complete(Optional.empty());
        return result.completeOnTimeout(Optional.empty(), STANDING_SECONDS, TimeUnit.SECONDS);
    }

    @Override public CompletionStage<List<String>> activationProblems(NpcDefinition definition) {
        CompletableFuture<List<String>> result = new CompletableFuture<>();
        boolean accepted = parts.living().players().console(() -> {
            try { result.complete(parts.living().validateActivation(definition)); }
            catch (RuntimeException failure) { result.complete(List.of(String.valueOf(failure.getMessage()))); }
        });
        if (!accepted) result.complete(List.of("the server refused the spawn check; retry shortly"));
        return result;
    }

    @Override public CompletionStage<List<String>> applied(Set<DocumentKind> kinds, Set<String> removedBehaviors, boolean rollback) {
        List<String> problems = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        return parts.runtime().catalogs().reloaded(new CatalogChange(kinds, removedBehaviors, rollback), problems::add).thenApply((Void live) -> List.copyOf(problems));
    }

    @Override public EditorNoticeChannel notices() { return parts.notices(); }

    @Override public Optional<PreviewPresenter> previews() {
        if (!parts.living().available()) return Optional.empty();
        ViewerPreviews previews = parts.living().previews();
        return Optional.of(new PreviewPresenter() {
            @Override public CompletionStage<Void> present(String previewId, UUID viewer, List<NpcDefinition> npcs, Consumer<String> ended) {
                return previews.present(previewId, viewer, npcs, ended);
            }

            @Override public void end(String previewId) { previews.end(previewId); }
        });
    }

    @Override public String previewsUnavailable() { return parts.living().status(); }

    @Override public Optional<EditorTerrain> terrain() { return Optional.ofNullable(parts.terrain()); }

    @Override public Optional<art.arcane.animus.core.virtual.WorldPosition> located(UUID npc) {
        try { return parts.living().state(npc).filter(art.arcane.animus.core.virtual.VirtualNpcState.Snapshot::active).map(art.arcane.animus.core.virtual.VirtualNpcState.Snapshot::position); }
        catch (IllegalStateException stopped) { return Optional.empty(); }
    }
}
