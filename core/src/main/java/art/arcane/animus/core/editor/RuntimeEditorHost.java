package art.arcane.animus.core.editor;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.command.BehaviorCommands;
import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.extension.ExtensionRuntime;
import art.arcane.animus.core.extension.ModuleHost;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.runtime.NpcRuntime;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The editor host every platform shares: it reaches the persisted NPC runtime, behaviors, modules and GUIs through their
 * core interfaces. A platform supplies only its name, extra features, the admin permission check and, when it has one,
 * a preview presenter.
 *
 * @param <P> the platform's online player
 */
public final class RuntimeEditorHost<P> implements EditorHost {
    /** What the platform passes in. {@code reloadGuis} reloads chest GUIs and shops after an apply. */
    public record Parts<P>(AnimusRuntime runtime, Executor files, NpcRuntime<P, ?> living, Supplier<BehaviorCommands> behaviors, ExtensionRuntime extensions,
                           Runnable reloadGuis, String platform, String serverName, Supplier<Map<String, Feature>> features, Predicate<P> admin,
                           Optional<PreviewPresenter> previews, String previewsUnavailable, Consumer<String> log) {
        public Parts { Objects.requireNonNull(runtime); Objects.requireNonNull(files); Objects.requireNonNull(living); Objects.requireNonNull(log); }
    }

    private final Parts<P> parts;

    public RuntimeEditorHost(Parts<P> parts) { this.parts = Objects.requireNonNull(parts); }

    @Override public String platform() { return parts.platform(); }

    @Override public String serverName() { return parts.serverName(); }

    @Override public Executor files() { return parts.files(); }

    @Override public Map<String, Feature> features() { return parts.features().get(); }

    @Override public List<Map.Entry<String, String>> modules() {
        if (parts.extensions() == null) return List.of();
        return parts.extensions().modules().stream().limit(256)
                .map((ModuleHost.ModuleStatus module) -> Map.entry(module.manifest().id(), module.state().name().toLowerCase(java.util.Locale.ROOT))).toList();
    }

    @Override public List<Capabilities.Availability> capabilities() { return parts.extensions() == null ? List.of() : parts.extensions().capabilities(); }

    @Override public boolean online(UUID player) { return parts.living().players().online(player); }

    @Override public void admin(UUID player, Consumer<Admin> result) {
        boolean delivered = parts.living().players().deliver(player, (P online) -> result.accept(parts.admin().test(online) ? Admin.ADMIN : Admin.NOT_ADMIN));
        if (!delivered) result.accept(Admin.UNKNOWN);
    }

    @Override public CompletionStage<List<String>> activationProblems(NpcDefinition definition) {
        CompletableFuture<List<String>> result = new CompletableFuture<>();
        boolean accepted = parts.living().players().console(() -> {
            try { result.complete(parts.living().validateActivation(definition)); }
            catch (RuntimeException failure) { result.complete(List.of(String.valueOf(failure.getMessage()))); }
        });
        if (!accepted) result.complete(List.of("the server refused the activation check; retry shortly"));
        return result;
    }

    @Override public void applied(Set<DocumentKind> kinds, boolean rollback) {
        Set<DocumentKind> npcs = EnumSet.of(DocumentKind.NPC, DocumentKind.SKIN, DocumentKind.ROUTE);
        try {
            if (kinds.stream().anyMatch(npcs::contains)) {
                List<String> problems = rollback ? parts.runtime().npcs().reloadAfterRollback() : parts.runtime().npcs().reload();
                problems.stream().limit(8).forEach((String problem) -> parts.log().accept("Editor apply: NPC reload kept the last valid set: " + problem));
                parts.living().refresh();
            }
        } catch (IOException failure) { parts.log().accept("Editor apply: NPC reload failed: " + failure.getMessage()); }
        if (kinds.contains(DocumentKind.BEHAVIOR) || kinds.contains(DocumentKind.PACK)) {
            BehaviorCommands behaviors = parts.behaviors().get();
            if (behaviors != null) behaviors.execute(console(), new String[]{"behaviors", "reload"});
        }
        if (kinds.contains(DocumentKind.SHOP) || kinds.contains(DocumentKind.INVENTORY)) parts.reloadGuis().run();
    }

    /** Reports behavior reload lines to the server log. */
    private CommandActor console() {
        return new CommandActor() {
            @Override public boolean permitted(String node) { return true; }
            @Override public void tell(String message) { parts.log().accept("Editor apply: " + message); }
            @Override public Optional<UUID> player() { return Optional.empty(); }
            @Override public boolean reply(Runnable action) { action.run(); return true; }
        };
    }

    @Override public Optional<PreviewPresenter> previews() { return parts.previews(); }

    @Override public String previewsUnavailable() { return parts.previewsUnavailable(); }
}
