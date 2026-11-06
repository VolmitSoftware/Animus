package art.arcane.animus.core;

import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.Subsystem;
import art.arcane.animus.core.command.BehaviorCommands;
import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.rig.RigCatalog;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The catalog reloads every platform shares: NPCs, behaviors, shops and chest GUIs, holograms and menus, and rigs, each
 * only when a change touched its kind. Runs on the file lane; the shop and hologram reloads queue their own file work.
 */
public final class RuntimeCatalogs implements CatalogReloads {
    /**
     * @param refresh asks the NPC runtime to pick up changed definitions, on any thread
     * @param shops reloads shops and chest GUIs
     * @param holograms reloads holograms, menus and text animations
     */
    public record Parts(AnimusRuntime runtime, Runnable refresh, Supplier<BehaviorCommands> behaviors, Runnable shops, Runnable holograms, Consumer<String> log) {
        public Parts {
            Objects.requireNonNull(runtime); Objects.requireNonNull(refresh); Objects.requireNonNull(behaviors);
            Objects.requireNonNull(shops); Objects.requireNonNull(holograms); Objects.requireNonNull(log);
        }
    }

    private static final Set<DocumentKind> NPCS = Set.of(DocumentKind.NPC, DocumentKind.SKIN, DocumentKind.ROUTE, DocumentKind.GROUP);
    private static final Set<DocumentKind> BEHAVIORS = Set.of(DocumentKind.BEHAVIOR, DocumentKind.PACK);
    private static final Set<DocumentKind> SHOPS = Set.of(DocumentKind.SHOP, DocumentKind.INVENTORY);
    private static final Set<DocumentKind> HOLOGRAMS = Set.of(DocumentKind.MENU, DocumentKind.HOLOGRAM, DocumentKind.TEXT_ANIMATION, DocumentKind.PRESETS);
    private static final Set<DocumentKind> RIGS = Set.of(DocumentKind.RIG, DocumentKind.ANIMATION);
    private final Parts parts;

    public RuntimeCatalogs(Parts parts) { this.parts = Objects.requireNonNull(parts); }

    @Override public void reload(CatalogChange change, Consumer<String> reported) {
        AnimusMetrics metrics = AnimusMetrics.current();
        long started = metrics.start();
        try { apply(change, reported); }
        finally { metrics.stop(Subsystem.HOTLOAD, started); }
    }

    private void apply(CatalogChange change, Consumer<String> reported) {
        if (touches(change, NPCS)) {
            try {
                List<String> problems = change.rollback() ? parts.runtime().npcs().reloadAfterRollback() : parts.runtime().npcs().reload();
                problems.stream().limit(8).forEach((String problem) -> parts.log().accept("Catalog reload: NPC file refused: " + problem));
                problems.forEach(reported);
            } catch (IOException failure) {
                parts.log().accept("Catalog reload: NPC reload failed: " + failure.getMessage());
                reported.accept(String.valueOf(failure.getMessage()));
            }
            parts.refresh().run();
        }
        if (touches(change, BEHAVIORS) || !change.removedBehaviors().isEmpty()) {
            BehaviorCommands behaviors = parts.behaviors().get();
            if (behaviors != null) behaviors.reload(console(), change.removedBehaviors());
        }
        if (touches(change, SHOPS)) parts.shops().run();
        if (touches(change, HOLOGRAMS)) parts.holograms().run();
        if (touches(change, RIGS)) {
            RigCatalog rigs = parts.runtime().rigs().reload();
            if (!rigs.problems().isEmpty()) parts.log().accept("Catalog reload: " + rigs.problems().size() + " rig problems; see /animus rigs problems");
            rigs.problems().forEach(reported);
            parts.refresh().run();
        }
    }

    private static boolean touches(CatalogChange change, Set<DocumentKind> kinds) { return change.kinds().stream().anyMatch(kinds::contains); }

    /** Reports behavior reload lines to the server log. */
    private CommandActor console() {
        return new CommandActor() {
            @Override public boolean permitted(String node) { return true; }
            @Override public void tell(String message) { parts.log().accept("Catalog reload: " + message); }
            @Override public Optional<UUID> player() { return Optional.empty(); }
            @Override public boolean reply(Runnable action) { action.run(); return true; }
        };
    }
}
