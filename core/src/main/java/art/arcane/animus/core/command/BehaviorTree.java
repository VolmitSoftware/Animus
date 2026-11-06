package art.arcane.animus.core.command;

import art.arcane.animus.core.text.Palette;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import java.util.UUID;
import java.util.function.Consumer;

/** {@code /animus behaviors}: the behavior catalog, its runs and its stored state. */
final class BehaviorTree {
    private BehaviorTree() {}

    /** Runs the work on the behavior commands, or says they are not initialized yet. */
    static void with(CommandServices services, Consumer<BehaviorCommands> work) {
        CommandActor actor = AnimusDirector.actor();
        BehaviorCommands behaviors = services.behaviors().get();
        if (behaviors == null) actor.tell(Palette.error("Behaviors are not initialized."));
        else work.accept(behaviors);
    }

    @Director(name = "behaviors", description = "Load, run, trace and cancel behaviors")
    static final class Behaviors extends CommandGroup {
        State state;

        Behaviors(CommandServices services) {
            super(services);
            state = new State(services);
        }

        @Director(description = "Show the catalog, runtime and trigger health")
        void status() { with(services, (BehaviorCommands behaviors) -> behaviors.status(actor())); }

        @Director(description = "List behavior documents")
        void list(@Param(name = "page", defaultValue = "1", description = "The page to show") int page) {
            with(services, (BehaviorCommands behaviors) -> behaviors.list(actor(), page));
        }

        @Director(description = "Validate every behavior, or one")
        void validate(@Param(name = "behavior", defaultValue = "", description = "One behavior id; every behavior when left out") String behavior) {
            with(services, (BehaviorCommands behaviors) -> behaviors.validate(actor(), ContentCommands.given(behavior)));
        }

        @Director(description = "Reload the behavior catalog")
        void reload() { with(services, (BehaviorCommands behaviors) -> behaviors.reload(actor())); }

        @Director(description = "Write the behavior JSON schemas")
        void schema() { with(services, (BehaviorCommands behaviors) -> behaviors.schemaCommand(actor())); }

        @Director(description = "List behavior packs")
        void packs(@Param(name = "page", defaultValue = "1", description = "The page to show") int page) {
            with(services, (BehaviorCommands behaviors) -> behaviors.packs(actor(), page));
        }

        @Director(description = "Run a behavior on an NPC")
        void run(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
                 @Param(name = "behavior", description = "The behavior id") String behavior) {
            CommandActor actor = actor();
            npc(actor, npc).ifPresent((String id) -> with(services, (BehaviorCommands behaviors) -> behaviors.run(actor, id, behavior)));
        }

        @Director(description = "Show one execution's steps")
        void inspect(@Param(name = "execution", description = "The execution UUID") UUID execution) {
            with(services, (BehaviorCommands behaviors) -> behaviors.inspect(actor(), execution));
        }

        @Director(description = "Show an NPC's running and recent executions")
        void trace(@Param(name = "npc", description = "The NPC id or UUID, or @selected") String npc) {
            CommandActor actor = actor();
            npc(actor, npc).ifPresent((String id) -> with(services, (BehaviorCommands behaviors) -> behaviors.trace(actor, id)));
        }

        @Director(description = "Cancel a running execution")
        void cancel(@Param(name = "execution", description = "The execution UUID") UUID execution) {
            with(services, (BehaviorCommands behaviors) -> behaviors.cancel(actor(), execution));
        }

        @Director(description = "List trigger bindings and how often they fired")
        void triggers(@Param(name = "page", defaultValue = "1", description = "The page to show") int page) {
            with(services, (BehaviorCommands behaviors) -> behaviors.triggers(actor(), page));
        }
    }

    @Director(name = "state", description = "Stored behavior variables")
    static final class State extends CommandGroup {
        Purge purge;

        State(CommandServices services) {
            super(services);
            purge = new Purge(services);
        }

        @Director(description = "Show stored keys, sizes and flush health")
        void status() { with(services, (BehaviorCommands behaviors) -> behaviors.stateStatus(actor())); }
    }

    @Director(name = "purge", description = "Delete stored behavior variables")
    static final class Purge extends CommandGroup {
        Everything all;

        Purge(CommandServices services) {
            super(services);
            all = new Everything(services);
        }

        @Director(description = "Delete one behavior's variables, or a namespace's flags or quests")
        void behavior(@Param(name = "state", description = "A behavior id, animus:flags/<namespace> or animus:quests/<namespace>") String state) {
            with(services, (BehaviorCommands behaviors) -> behaviors.purgeBehavior(actor(), state));
        }

        @Director(description = "Delete what behaviors stored on an NPC")
        void npc(@Param(name = "npc", description = "The NPC id or UUID") String npc) {
            with(services, (BehaviorCommands behaviors) -> behaviors.purgeNpc(actor(), npc));
        }

        @Director(description = "Delete what behaviors stored on a player")
        void player(@Param(name = "player", description = "The player's UUID") UUID player) {
            with(services, (BehaviorCommands behaviors) -> behaviors.purgePlayer(actor(), player));
        }

        @Director(description = "Delete what behaviors stored between an NPC and a player")
        void relationship(@Param(name = "npc", description = "The NPC id or UUID") String npc,
                          @Param(name = "player", description = "The player's UUID") UUID player) {
            with(services, (BehaviorCommands behaviors) -> behaviors.purgeRelationship(actor(), npc, player));
        }
    }

    @Director(name = "all", description = "Delete every stored behavior variable")
    static final class Everything extends CommandGroup {
        Everything(CommandServices services) { super(services); }

        @Director(description = "Confirm deleting every stored behavior variable")
        void confirm() { with(services, (BehaviorCommands behaviors) -> behaviors.purgeAll(actor())); }
    }
}
