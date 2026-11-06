package art.arcane.animus.core.command;

import art.arcane.animus.core.text.Palette;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** {@code /animus navigation}, {@code ride} and {@code combat}: NPCs moving, carrying riders and fighting. */
final class WorldCommands {
    private WorldCommands() {}

    /** A combat answer, or its refusal in red. */
    static void combat(CommandActor actor, Supplier<String> work) {
        try { actor.tell(work.get()); }
        catch (RuntimeException refused) { actor.tell(Palette.error("Combat: " + refused.getMessage())); }
    }

    @Director(name = "navigation", description = "Watch or stop an NPC's route")
    static final class Navigation extends CommandGroup {
        Navigation(CommandServices services) { super(services); }

        @Director(description = "Show an NPC's route and progress")
        void status(@Param(name = "npc", description = "The NPC id, or @selected") String npc) {
            CommandActor actor = actor();
            npc(actor, npc).ifPresent((String id) -> {
                try { actor.tell(living().navigationStatus(id)); }
                catch (RuntimeException refused) { actor.tell(Palette.error("Navigation: " + refused.getMessage())); }
            });
        }

        @Director(description = "Stop an NPC's route")
        void stop(@Param(name = "npc", description = "The NPC id, or @selected") String npc) {
            CommandActor actor = actor();
            npc(actor, npc).ifPresent((String id) -> onNpcOwner(actor, id, "Animus navigation",
                    () -> "Navigation stop requested for " + id + "; stopped=" + living().stopNavigation(id) + "\n" + living().navigationStatus(id)));
        }
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
            try { actor.tell(living().stopRider(player) ? Palette.success("Dismounted " + player) : player + " is not riding"); }
            catch (RuntimeException refused) { actor.tell(Palette.error("Ride: " + refused.getMessage())); }
        }
    }

    @Director(name = "combat", description = "Drive the combat module of NPCs")
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
                   @Param(name = "target", description = "The guarded UUID, or stop") String target) {
            CommandActor actor = actor();
            Optional<UUID> charge;
            try { charge = target.equalsIgnoreCase("stop") ? Optional.empty() : Optional.of(UUID.fromString(target)); }
            catch (IllegalArgumentException invalid) { actor.tell(Palette.error("Combat: target is a UUID or stop")); return; }
            run(actor, npc, CombatControl.Operation.GUARD, charge);
        }

        private void run(CommandActor actor, String npc, CombatControl.Operation operation, Optional<UUID> target) {
            npc(actor, npc).ifPresent((String id) -> combat(actor, () -> services.combat().npc(id, operation, target)));
        }
    }
}
