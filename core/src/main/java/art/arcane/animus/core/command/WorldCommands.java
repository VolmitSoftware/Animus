package art.arcane.animus.core.command;

import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** {@code /animus navigation}, {@code ride} and {@code combat}: NPCs moving, carrying riders and fighting. */
final class WorldCommands {
    /** The word that ends guarding or mob control. */
    static final String STOP = "stop";

    private WorldCommands() {}

    /** A combat answer, or its refusal in red. */
    static void combat(CommandActor actor, Supplier<String> work) {
        try { actor.tell(work.get()); }
        catch (RuntimeException refused) { actor.tell(Palette.error("Combat: " + refused.getMessage())); }
    }

    @Director(name = "navigation", description = "Send NPCs somewhere, turn them, watch or stop their routes")
    @AnimusPermission(PermissionNodes.NPC_MOVE)
    static final class Navigation extends CommandGroup {
        Navigation(CommandServices services) { super(services); }

        @Director(description = "Walk, swim or fly an NPC to a point, or to you; whole-number x and z mean the block centre")
        void go(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
                @Param(name = "x", defaultValue = "", description = "Target X: a number, ~ or ~N; yours when left out") String x,
                @Param(name = "y", defaultValue = "", description = "Target Y: a number, ~ or ~N; yours when left out") String y,
                @Param(name = "z", defaultValue = "", description = "Target Z: a number, ~ or ~N; yours when left out") String z,
                @Param(name = "mode", defaultValue = "walk", description = "walk, swim or fly") MovementProfile.Mode mode,
                @Param(name = "speed", defaultValue = "2.0", description = "Blocks per second") double speed) {
            CommandActor actor = actor();
            Vec3 target;
            try { target = Coordinates.point(x, y, z, Coordinates.origin(actor)); }
            catch (IllegalArgumentException unreadable) { actor.tell(Palette.error(unreadable.getMessage())); return; }
            npc(actor, npc).ifPresent((String id) -> onNpcOwner(actor, id, "Navigation", () -> {
                Vec3 goal = goal(target, mode);
                WorldPosition destination = new WorldPosition(living().navigationOrigin(id).world(), goal);
                return id + " heading to " + goal.brief() + " (" + mode.name().toLowerCase(Locale.ROOT) + "); route " + ChatLine.shortId(living().navigate(id, destination, mode, speed).toString());
            }));
        }

        /** Whole-block x and z at the block centre; a walking goal stands on a block, so its feet height is rounded. */
        static Vec3 goal(Vec3 target, MovementProfile.Mode mode) {
            return new Vec3(blockCenter(target.x()), mode == MovementProfile.Mode.WALK ? Math.rint(target.y()) : target.y(), blockCenter(target.z()));
        }

        /**
         * A whole-number x or z names a block, so the goal is its centre; a goal on the block corner makes the body clip the
         * next block. A fractional value is an exact point and stays.
         */
        static double blockCenter(double coordinate) { return coordinate == Math.floor(coordinate) ? coordinate + 0.5 : coordinate; }

        @Director(description = "Turn an NPC to look at a point, or at you")
        void look(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
                  @Param(name = "x", defaultValue = "", description = "Target X: a number, ~ or ~N; yours when left out") String x,
                  @Param(name = "y", defaultValue = "", description = "Target Y: a number, ~ or ~N; yours when left out") String y,
                  @Param(name = "z", defaultValue = "", description = "Target Z: a number, ~ or ~N; yours when left out") String z) {
            CommandActor actor = actor();
            Vec3 target;
            try { target = Coordinates.point(x, y, z, Coordinates.origin(actor)); }
            catch (IllegalArgumentException unreadable) { actor.tell(Palette.error(unreadable.getMessage())); return; }
            npc(actor, npc).ifPresent((String id) -> onNpcOwner(actor, id, "Navigation", () -> {
                living().lookAt(id, new WorldPosition(living().navigationOrigin(id).world(), target));
                return id + " looks at " + target.brief();
            }));
        }

        @Director(description = "Show an NPC's route and progress")
        void status(@Param(name = "npc", description = "The NPC id, or @selected") String npc) {
            CommandActor actor = actor();
            npc(actor, npc).ifPresent((String id) -> {
                try { actor.tell(NavigationReport.readable(living().navigationStatus(id))); }
                catch (RuntimeException refused) { actor.tell(Palette.error("Navigation: " + refused.getMessage())); }
            });
        }

        @Director(description = "Stop an NPC's route")
        void stop(@Param(name = "npc", description = "The NPC id, or @selected") String npc) {
            CommandActor actor = actor();
            npc(actor, npc).ifPresent((String id) -> onNpcOwner(actor, id, "Navigation", () -> stopped(id, living().stopNavigation(id))));
        }

        static String stopped(String id, boolean running) { return running ? "Stopped " + id + "'s route" : id + " has no route to stop"; }
    }

    @Director(name = "ride", description = "Watch or end NPC rides")
    @AnimusPermission(PermissionNodes.NPC_MOVE)
    static final class Ride extends CommandGroup {
        Ride(CommandServices services) { super(services); }

        @Director(description = "Show who rides which NPC")
        void status() {
            CommandActor actor = actor();
            try { actor.tell(living().rideStatus()); }
            catch (RuntimeException refused) { actor.tell(Palette.error("Ride: " + refused.getMessage())); }
        }

        @Director(description = "Dismount a player from their NPC")
        void stop(@Param(name = "player", description = "The riding player's name") String player) {
            CommandActor actor = actor();
            try { actor.tell(living().stopRider(player) ? Palette.success("Dismounted " + player) : Palette.error(player + " is not riding")); }
            catch (RuntimeException refused) { actor.tell(Palette.error("Ride: " + refused.getMessage())); }
        }
    }

    @Director(name = "combat", description = "Make NPCs fight, guard, attack once or steer a mob")
    @AnimusPermission(PermissionNodes.COMBAT)
    static final class Combat extends CommandGroup {
        Combat(CommandServices services) { super(services); }

        @Director(description = "Show combat on this server, or one NPC's fight")
        @Positional("npc")
        void status(@Param(name = "npc", defaultValue = "", description = "One NPC id, or @selected") String npc) {
            CommandActor actor = actor();
            if (npc.isEmpty()) { combat(actor, services.combat()::status); return; }
            run(actor, npc, CombatControl.Operation.STATUS, Optional.empty(), "");
        }

        @Director(description = "Let an NPC fight")
        void engage(@Param(name = "npc", description = "The NPC id, or @selected") String npc) { run(actor(), npc, CombatControl.Operation.ENGAGE, Optional.empty(), ""); }

        @Director(description = "Stop an NPC fighting")
        void disengage(@Param(name = "npc", description = "The NPC id, or @selected") String npc) { run(actor(), npc, CombatControl.Operation.DISENGAGE, Optional.empty(), ""); }

        @Director(description = "Hand an NPC back to its own combat rules")
        void release(@Param(name = "npc", description = "The NPC id, or @selected") String npc) { run(actor(), npc, CombatControl.Operation.RELEASE, Optional.empty(), ""); }

        @Director(description = "Set an NPC's target")
        void target(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
                    @Param(name = "target", description = TARGET) String target) {
            CommandActor actor = actor();
            entity(actor, target).ifPresent((UUID found) -> run(actor, npc, CombatControl.Operation.TARGET, Optional.of(found), target));
        }

        @Director(description = "Make an NPC guard a player or NPC, or stop with stop")
        void guard(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
                   @Param(name = "charge", description = "The guarded player's name, an NPC id, @me, a UUID, or stop") String charge) {
            CommandActor actor = actor();
            if (charge.equalsIgnoreCase(STOP)) { run(actor, npc, CombatControl.Operation.GUARD, Optional.empty(), charge); return; }
            entity(actor, charge).ifPresent((UUID found) -> run(actor, npc, CombatControl.Operation.GUARD, Optional.of(found), charge));
        }

        @Director(description = "Make an NPC hit a living entity once")
        void attack(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
                    @Param(name = "target", description = TARGET) String target,
                    @Param(name = "damage", defaultValue = "1", description = "Damage dealt") double damage) {
            CommandActor actor = actor();
            entity(actor, target).ifPresent((UUID found) -> npc(actor, npc).ifPresent((String id) ->
                    combat(actor, () -> Targets.named(services.combat().attack(id, found, damage), found, shown(actor, found, target)))));
        }

        @Director(description = "Lend a mob to an NPC's control, or stop with stop")
        void mob(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
                 @Param(name = "mob", description = "The mob's UUID, or stop to end every control") String mob) {
            CommandActor actor = actor();
            Optional<UUID> lent;
            try { lent = mob.equalsIgnoreCase(STOP) ? Optional.empty() : Optional.of(UUID.fromString(mob)); }
            catch (IllegalArgumentException invalid) { actor.tell(Palette.error("Combat: mob is a UUID or stop; tab completion lists nearby ones")); return; }
            npc(actor, npc).ifPresent((String id) -> combat(actor, () -> lent.isEmpty() ? services.combat().releaseMobs(id) : services.combat().control(id, lent.get())));
        }

        private static final String TARGET = "A player's name, an NPC id, @me, @selected, or an entity's UUID";

        /** The entity a word names, as {@link Targets} reads it; {@code @selected} is the selected NPC. Empty after telling the actor why. */
        private Optional<UUID> entity(CommandActor actor, String word) {
            Optional<String> npc = word.equalsIgnoreCase(SELECTED) ? npc(actor, word) : Optional.of(word);
            if (npc.isEmpty()) return Optional.empty();
            Optional<UUID> found = new Targets(actor.player(), services.shops()::playerNamed,
                    (String id) -> runtime().npcs().find(id).map(art.arcane.animus.core.npc.NpcDefinition::uuid)).resolve(npc.get());
            if (found.isEmpty()) actor.tell(Palette.error("Combat: no online player or NPC named " + word + "; give a player name, an NPC id or a UUID"));
            return found;
        }

        /** How a reply names the target: {@code you} for the sender, else the word typed, unless that was a raw UUID. */
        private static String shown(CommandActor actor, UUID target, String typed) {
            if (actor.player().map(target::equals).orElse(false)) return "you";
            try { UUID.fromString(typed); return ChatLine.shortId(target.toString()); }
            catch (IllegalArgumentException named) { return typed; }
        }

        /** Runs the operation; a reply that names the target by UUID names it as typed, or says {@code you} for the sender. */
        private void run(CommandActor actor, String npc, CombatControl.Operation operation, Optional<UUID> target, String typed) {
            npc(actor, npc).ifPresent((String id) -> combat(actor, () -> {
                String reply = services.combat().npc(id, operation, target);
                return target.map((UUID found) -> Targets.named(reply, found, shown(actor, found, typed))).orElse(reply);
            }));
        }
    }

    /**
     * What a combat command's player or NPC word means: {@code @me} is the sender, then a UUID, an online player's
     * exact name, then an NPC id. A player name wins over an NPC id spelled the same, as Sentinel's guard reads it.
     */
    record Targets(Optional<UUID> self, Function<String, Optional<UUID>> players, Function<String, Optional<UUID>> npcs) {
        static final String ME = "@me";

        Optional<UUID> resolve(String word) {
            if (word.equalsIgnoreCase(ME)) return self;
            try { return Optional.of(UUID.fromString(word)); }
            catch (IllegalArgumentException named) { return players.apply(word).or(() -> npcs.apply(word)); }
        }

        /** The reply with the target's UUID, whole or short, replaced by how the sender named it. */
        static String named(String reply, UUID target, String shown) {
            return reply.replace(target.toString(), shown).replace(ChatLine.shortId(target.toString()), shown);
        }

        /** Completion: {@code stop} where it ends the command, {@code @me} for a player, names, NPC ids, then nearby entities by UUID. */
        static List<String> offered(boolean stop, boolean player, List<String> players, List<String> npcs, List<UUID> nearby) {
            List<String> offered = new ArrayList<>();
            if (stop) offered.add(STOP);
            if (player) offered.add(ME);
            offered.addAll(players);
            offered.addAll(npcs);
            nearby.forEach((UUID id) -> offered.add(id.toString()));
            return offered;
        }
    }
}
