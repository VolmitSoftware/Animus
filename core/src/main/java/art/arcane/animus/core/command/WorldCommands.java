package art.arcane.animus.core.command;

import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
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
    static final class Combat extends CommandGroup {
        Combat(CommandServices services) { super(services); }

        @Director(description = "Show combat on this server, or one NPC's fight")
        void status(@Param(name = "npc", defaultValue = "", description = "One NPC id, or @selected") String npc) {
            CommandActor actor = actor();
            if (npc.isEmpty()) { combat(actor, services.combat()::status); return; }
            run(actor, npc, CombatControl.Operation.STATUS, Optional.empty());
        }

        @Director(description = "Let an NPC fight")
        void engage(@Param(name = "npc", description = "The NPC id, or @selected") String npc) { run(actor(), npc, CombatControl.Operation.ENGAGE, Optional.empty()); }

        @Director(description = "Stop an NPC fighting")
        void disengage(@Param(name = "npc", description = "The NPC id, or @selected") String npc) { run(actor(), npc, CombatControl.Operation.DISENGAGE, Optional.empty()); }

        @Director(description = "Hand an NPC back to its own combat rules")
        void release(@Param(name = "npc", description = "The NPC id, or @selected") String npc) { run(actor(), npc, CombatControl.Operation.RELEASE, Optional.empty()); }

        @Director(description = "Set an NPC's target")
        void target(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
                    @Param(name = "target", description = "The target's UUID") UUID target) {
            run(actor(), npc, CombatControl.Operation.TARGET, Optional.of(target));
        }

        @Director(description = "Make an NPC guard a player or NPC, or stop with stop")
        void guard(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
                   @Param(name = "charge", description = "The guarded player's or NPC's UUID, or stop") String charge) {
            CommandActor actor = actor();
            Optional<UUID> guarded;
            try { guarded = charge.equalsIgnoreCase(STOP) ? Optional.empty() : Optional.of(UUID.fromString(charge)); }
            catch (IllegalArgumentException invalid) { actor.tell(Palette.error("Combat: charge is a UUID or stop; tab completion lists nearby ones")); return; }
            run(actor, npc, CombatControl.Operation.GUARD, guarded);
        }

        @Director(description = "Make an NPC hit a living entity once")
        void attack(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
                    @Param(name = "target", description = "The target's UUID") UUID target,
                    @Param(name = "damage", defaultValue = "1", description = "Damage dealt") double damage) {
            CommandActor actor = actor();
            npc(actor, npc).ifPresent((String id) -> combat(actor, () -> services.combat().attack(id, target, damage)));
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

        private void run(CommandActor actor, String npc, CombatControl.Operation operation, Optional<UUID> target) {
            npc(actor, npc).ifPresent((String id) -> combat(actor, () -> services.combat().npc(id, operation, target)));
        }
    }
}
