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
        if (behaviors == null) actor.tell(Palette.error("Behaviors unavailable; the startup log names why, fix it and restart"));
        else work.accept(behaviors);
    }

    @Director(name = "behaviors", aliases = "behavior", description = "Load, run, trace and cancel behaviors")
    @AnimusPermission(PermissionNodes.BEHAVIORS)
    static final class Behaviors extends CommandGroup {
        State state;

        Behaviors(CommandServices services) {
            super(services);
            state = new State(services);
        }

        @Director(description = "Show the catalog, runtime and trigger health")
        void status() { with(services, (BehaviorCommands behaviors) -> behaviors.status(actor())); }

        @Director(description = "List behavior documents")
        @Positional("page")
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
        @Positional("page")
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
        @Positional("page")
        void trace(@Param(name = "npc", description = "The NPC id or UUID, or @selected") String npc,
                   @Param(name = "page", defaultValue = "1", description = "The page to show") int page) {
            CommandActor actor = actor();
            npc(actor, npc).ifPresent((String id) -> with(services, (BehaviorCommands behaviors) -> behaviors.trace(actor, id, page)));
        }

        @Director(description = "Cancel a running execution")
        void cancel(@Param(name = "execution", description = "The execution UUID") UUID execution) {
            with(services, (BehaviorCommands behaviors) -> behaviors.cancel(actor(), execution));
        }

        @Director(description = "List trigger bindings and how often they fired")
        @Positional("page")
        void triggers(@Param(name = "page", defaultValue = "1", description = "The page to show") int page) {
            with(services, (BehaviorCommands behaviors) -> behaviors.triggers(actor(), page));
        }
    }

    @Director(name = "state", description = "Stored behavior variables")
    @AnimusPermission(PermissionNodes.BEHAVIOR_STATE)
    static final class State extends CommandGroup {
        Delete delete;

        State(CommandServices services) {
            super(services);
            delete = new Delete(services);
        }

        @Director(description = "Show stored keys, sizes and flush health")
        void status() { with(services, (BehaviorCommands behaviors) -> behaviors.stateStatus(actor())); }
    }

    @Director(name = "delete", description = "Delete stored behavior variables")
    static final class Delete extends CommandGroup {
        Everything all;

        Delete(CommandServices services) {
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
        void player(@Param(name = "uuid", description = "The player's UUID") UUID player) {
            with(services, (BehaviorCommands behaviors) -> behaviors.purgePlayer(actor(), player));
        }

        @Director(description = "Delete what behaviors stored between an NPC and a player")
        void relationship(@Param(name = "npc", description = "The NPC id or UUID") String npc,
                          @Param(name = "uuid", description = "The player's UUID") UUID player) {
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
