package art.arcane.animus.core.command;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.admin.Administration;
import art.arcane.animus.core.extension.ExtensionRuntime;
import art.arcane.animus.core.extension.ModuleHost;
import art.arcane.animus.core.extension.loader.StartupModules;
import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.Subsystem;
import art.arcane.animus.core.migration.CitizensDropIn;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.rig.RigCatalog;
import art.arcane.animus.core.rig.pack.RigPackBuilder;
import art.arcane.animus.core.text.Palette;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/** {@code /animus modules}, {@code rigs}, {@code pack} and {@code import}: what Animus loads and converts. */
final class ContentCommands {
    private ContentCommands() {}

    static String rigSummary(RigCatalog catalog) {
        return "Rigs reloaded: rigs=" + catalog.rigs().size() + " animations=" + catalog.animations().size() + " problems=" + catalog.problems().size()
                + (catalog.problems().isEmpty() ? "" : " (see /animus rigs problems)");
    }

    /** {@code a:b,c:d} as an ordered map; blank means none. */
    static Map<String, String> worlds(String text) {
        Map<String, String> worlds = new TreeMap<>();
        if (text.isBlank()) return worlds;
        for (String pair : text.split(",")) {
            String[] parts = pair.split(":", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank() || worlds.putIfAbsent(parts[0].strip(), parts[1].strip()) != null)
                throw new IllegalArgumentException("Invalid or repeated world mapping " + pair + "; write worlds=old:new,other:renamed");
        }
        return worlds;
    }

    static Optional<String> given(String value) { return value.isBlank() ? Optional.empty() : Optional.of(value); }

    @Director(name = "modules", description = "Inspect the module runtime and call module capabilities")
    static final class Modules extends CommandGroup {
        Modules(CommandServices services) { super(services); }

        private ExtensionRuntime extensions(CommandActor actor) {
            ExtensionRuntime extensions = services.extensions();
            if (extensions == null) actor.tell(Palette.error("Modules are not initialized."));
            return extensions;
        }

        @Director(description = "Show module runtime health and file work")
        void status() {
            CommandActor actor = actor();
            ExtensionRuntime extensions = extensions(actor);
            if (extensions == null) return;
            AnimusRuntime active = runtime();
            ExtensionRuntime.Status snapshot = extensions.status();
            actor.tell("Modules " + snapshot.state() + " | startup=" + extensions.startupStatuses().size()
                    + " | " + ModuleCapabilityListing.of(extensions.capabilities()).counts() + " | failure=" + snapshot.failure());
            actor.tell("Module invocations: " + snapshot.invocations() + " | dropped interaction notices=" + extensions.droppedInteractionNotices());
            active.moduleHooks().status().forEach(actor::tell);
            if (snapshot.lifecycle() != null) actor.tell("Module lifecycle: " + snapshot.lifecycle());
            if (snapshot.cleanup() != null) actor.tell("Module cleanup: " + snapshot.cleanup());
            actor.tell("File work: " + active.fileStatus());
        }

        @Director(description = "List installed modules and their state")
        void list(@Param(name = "page", defaultValue = "1", description = "The page to show") int page) {
            CommandActor actor = actor();
            ExtensionRuntime extensions = extensions(actor);
            if (extensions == null) return;
            Map<String, ModuleHost.ModuleStatus> live = extensions.modules().stream()
                    .collect(Collectors.toMap((ModuleHost.ModuleStatus module) -> module.manifest().id(), (ModuleHost.ModuleStatus module) -> module));
            List<String> rows = extensions.startupStatuses().stream().map((StartupModules.Status startup) -> {
                ModuleHost.ModuleStatus current = live.get(startup.id());
                return startup.id() + " | " + startup.archive() + " | " + (current == null ? startup.state() : current.state())
                        + " | " + (current == null ? startup.reason() : current.reason());
            }).toList();
            listing(actor, "Modules (" + rows.size() + ")", "/" + BuildIdentity.ID + " modules list", rows, page, "No modules are installed.");
        }

        @Director(description = "List the actions and conditions modules offer")
        void capabilities(@Param(name = "page", defaultValue = "1", description = "The page to show") int page) {
            CommandActor actor = actor();
            ExtensionRuntime extensions = extensions(actor);
            if (extensions == null) return;
            ModuleCapabilityListing listing = ModuleCapabilityListing.of(extensions.capabilities());
            listing(actor, "Module capabilities", "/" + BuildIdentity.ID + " modules capabilities", listing.rows(), page, "No module offers a capability.");
        }

        @Director(description = "Run a module action on an NPC")
        void action(@Param(name = "capability", description = "namespace:name") String capability,
                    @Param(name = "npc", description = "The NPC id, or @selected") String npc,
                    @Param(name = "input", description = "A JSON object for the action") String input) {
            invoke(actor(), true, capability, npc, input);
        }

        @Director(description = "Check a module condition on an NPC")
        void condition(@Param(name = "capability", description = "namespace:name") String capability,
                       @Param(name = "npc", description = "The NPC id, or @selected") String npc,
                       @Param(name = "input", description = "A JSON object for the condition") String input) {
            invoke(actor(), false, capability, npc, input);
        }

        private void invoke(CommandActor actor, boolean action, String capability, String npc, String input) {
            ExtensionRuntime extensions = extensions(actor);
            if (extensions == null) return;
            String[] parts = capability.split(":", -1);
            if (parts.length != 2) { actor.tell(Palette.error("Capability must be namespace:name")); return; }
            Optional<String> id = npc(actor, npc);
            if (id.isEmpty()) return;
            Optional<NpcDefinition> definition = runtime().npcs().find(id.get());
            if (definition.isEmpty()) { actor.tell(Palette.error("Unknown NPC: " + id.get())); return; }
            Capabilities.Key key = new Capabilities.Key(parts[0], parts[1]);
            NpcHandle handle = new NpcHandle(definition.get().uuid());
            CompletionStage<? extends Outcome<?>> result;
            try { result = action ? extensions.action(key, handle, actor.player(), input) : extensions.condition(key, handle, actor.player(), input); }
            catch (RuntimeException refused) { actor.tell(Palette.error("Module " + key + ": " + refused.getMessage())); return; }
            result.whenComplete((Outcome<?> outcome, Throwable failure) -> {
                if (failure != null) reply(actor, Palette.error("Module " + key + ": FAILED; result delivery failed"));
                else reply(actor, "Module " + key + ": " + outcome.status() + outcome.value().map((Object value) -> "; result " + value).orElseGet(() ->
                        "; " + outcome.diagnostics().stream().map(Diagnostic::code).collect(Collectors.joining(", "))));
            });
        }
    }

    @Director(name = "rigs", description = "Load rigs and play their animations")
    static final class Rigs extends CommandGroup {
        Rigs(CommandServices services) { super(services); }

        @Director(description = "Show loaded rigs and animations")
        void status() { actor().tell(runtime().rigs().status()); }

        @Director(description = "Reload rigs and animations from disk")
        void reload() {
            CommandActor actor = actor();
            AnimusRuntime active = runtime();
            files(actor, () -> {
                RigCatalog catalog = active.rigs().reload();
                living().refresh();
                return List.of(rigSummary(catalog));
            });
        }

        @Director(description = "List rig and animation problems")
        void problems(@Param(name = "page", defaultValue = "1", description = "The page to show") int page) {
            CommandActor actor = actor();
            listing(actor, "Rig problems", "/" + BuildIdentity.ID + " rigs problems", runtime().rigs().catalog().problems(), page, "No rig problems.");
        }

        @Director(description = "Play an animation on an NPC's rig")
        void play(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
                  @Param(name = "animation", description = "The animation id") String animation,
                  @Param(name = "layer", defaultValue = "0", description = "The layer it plays on") int layer,
                  @Param(name = "speed", defaultValue = "1", description = "Playback speed") double speed,
                  @Param(name = "loop", defaultValue = "default", description = "default keeps the animation's own; once, loop, pingpong or hold") Loop loop) {
            CommandActor actor = actor();
            Optional<String> mode = loop == Loop.DEFAULT ? Optional.empty() : Optional.of(loop.name().toLowerCase(Locale.ROOT));
            npc(actor, npc).ifPresent((String id) -> onNpcOwner(actor, id, "Animus rigs", () -> {
                living().playAnimation(id, animation, speed, mode, layer);
                return "Playing " + animation + " on " + id + " layer " + layer;
            }));
        }

        @Director(description = "Stop an NPC's animation")
        void stop(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
                  @Param(name = "layer", defaultValue = "-1", description = "The layer to stop; -1 stops every layer") int layer,
                  @Param(name = "fade", defaultValue = "0", description = "Fade-out ticks") int fade) {
            CommandActor actor = actor();
            OptionalInt chosen = layer < 0 ? OptionalInt.empty() : OptionalInt.of(layer);
            npc(actor, npc).ifPresent((String id) -> onNpcOwner(actor, id, "Animus rigs", () -> {
                living().stopAnimation(id, chosen, fade);
                return "Stopped animation on " + id;
            }));
        }

        /** How {@code rigs play} repeats; {@code default} keeps the animation's own setting. */
        enum Loop { DEFAULT, ONCE, LOOP, PINGPONG, HOLD }
    }

    @Director(name = "pack", description = "Build the resource pack for rigged models")
    static final class Pack extends CommandGroup {
        Pack(CommandServices services) { super(services); }

        @Director(description = "Build models/ Blockbench files into pack/out/")
        void build() {
            CommandActor actor = actor();
            AnimusRuntime active = runtime();
            files(actor, () -> {
                Path data = active.rigs().data();
                RigPackBuilder.Result result = RigPackBuilder.build(data, data.resolve("pack/out"), RigPackBuilder.Limits.DEFAULT);
                List<String> lines = new ArrayList<>();
                lines.add(Palette.success("Pack built: models=" + result.models() + " files=" + result.files() + " sha1=" + result.sha1() + " -> pack/out/" + result.zip().getFileName()));
                lines.add("Rig and animation documents for the models are in pack/out/documents; copy them into rigs/ and animations/ to use them.");
                result.problems().stream().limit(20).forEach((String problem) -> lines.add("Pack: " + problem));
                if (result.problems().size() > 20) lines.add("Pack: " + (result.problems().size() - 20) + " more problems not shown");
                return lines;
            });
        }
    }

    @Director(name = "import", description = "Convert Citizens and module formats into dormant NPCs")
    static final class Import extends CommandGroup {
        Citizens citizens;

        Import(CommandServices services) {
            super(services);
            citizens = new Citizens(services);
        }

        @Director(description = "Convert a file in imports/inbox/ into a reviewable preview")
        void preview(@Param(name = "file", description = "A file name directly under imports/inbox/") String file,
                     @Param(name = "converter", defaultValue = "", description = "A module converter key, namespace:name") String converter,
                     @Param(name = "worlds", defaultValue = "", description = "Citizens world renames, old:new,other:renamed") String worlds) {
            CommandActor actor = actor();
            Map<String, String> renames;
            try { renames = worlds(worlds); }
            catch (IllegalArgumentException invalid) { actor.tell(Palette.error(invalid.getMessage())); return; }
            imported(actor, () -> new Administration(runtime()).preview(file, given(converter), renames));
        }

        @Director(description = "Stage how a preview lands beside existing NPCs")
        void stage(@Param(name = "preview", description = "The preview id") String preview,
                   @Param(name = "mode", defaultValue = "reject", description = "reject, replace or duplicate") Mode mode,
                   @Param(name = "copy", defaultValue = "", description = "The copy label mode=duplicate needs") String copy) {
            CommandActor actor = actor();
            imported(actor, () -> new Administration(runtime()).stage(preview, mode.name().toLowerCase(Locale.ROOT), given(copy)));
        }

        @Director(description = "Install a preview as dormant NPCs")
        void apply(@Param(name = "preview", description = "The preview id") String preview) {
            CommandActor actor = actor();
            imported(actor, () -> new Administration(runtime()).apply(preview));
        }

        @Director(name = "apply-plan", description = "Install a staged application of a preview")
        void applyPlan(@Param(name = "preview", description = "The preview id") String preview,
                       @Param(name = "application", description = "The application id stage reported") String application) {
            CommandActor actor = actor();
            imported(actor, () -> new Administration(runtime()).applyPlan(preview, application));
        }

        /** Import file work, measured as imports, then the NPC runtime picks up what changed. */
        void imported(CommandActor actor, Work work) {
            files(actor, () -> {
                AnimusMetrics metrics = AnimusMetrics.current();
                long started = metrics.start();
                try { return work.run(); }
                finally { metrics.stop(Subsystem.IMPORTS, started); living().refresh(); }
            });
        }

        /** How staged records meet existing definitions with the same id. */
        enum Mode { REJECT, REPLACE, DUPLICATE }
    }

    @Director(name = "citizens", description = "Migrate a Citizens server: preview, apply, review, accept, rollback")
    static final class Citizens extends CommandGroup {
        Citizens(CommandServices services) { super(services); }

        private void step(Administration.CitizensStep step) {
            CommandActor actor = actor();
            Administration administration = new Administration(runtime());
            files(actor, () -> {
                AnimusMetrics metrics = AnimusMetrics.current();
                long started = metrics.start();
                try { return administration.citizens(step); }
                finally { metrics.stop(Subsystem.IMPORTS, started); living().refresh(); }
            });
        }

        @Director(description = "Preview the Citizens, Shopkeepers, dtlTraders and Denizen data found and stage it")
        void preview(@Param(name = "mode", defaultValue = "reject", description = "reject, replace or duplicate") Import.Mode mode,
                     @Param(name = "copy", defaultValue = "", description = "The copy label mode=duplicate needs") String copy,
                     @Param(name = "worlds", defaultValue = "", description = "World renames, old:new,other:renamed") String worlds,
                     @Param(name = "factor-groups", defaultValue = "false", description = "Move settings two or more imported NPCs share into trait groups") boolean factorGroups) {
            Map<String, String> renames;
            try { renames = worlds(worlds); }
            catch (IllegalArgumentException invalid) { actor().tell(Palette.error(invalid.getMessage())); return; }
            step((CitizensDropIn dropIn) -> dropIn.preview(mode.name().toLowerCase(Locale.ROOT), given(copy), renames, factorGroups));
        }

        @Director(description = "Install the staged preview")
        void apply() { step(CitizensDropIn::applyStaged); }

        @Director(description = "List the imported settings still waiting for review")
        void review() { step(CitizensDropIn::reviewApplied); }

        @Director(description = "Turn on reviewed categories, or allow the command namespaces converted behaviors run")
        void accept(@Param(name = "categories", description = "Comma-separated categories, all, or commands") String categories) {
            List<String> chosen = Arrays.stream(categories.split(",")).map(String::strip).filter((String category) -> !category.isEmpty()).toList();
            step((CitizensDropIn dropIn) -> dropIn.acceptCategories(chosen));
        }

        @Director(description = "Undo the import and every acceptance")
        void rollback() { step(CitizensDropIn::rollbackApplied); }
    }
}
