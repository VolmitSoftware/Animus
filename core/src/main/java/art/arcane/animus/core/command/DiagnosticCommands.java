package art.arcane.animus.core.command;

import art.arcane.animus.core.AnimusLog;
import art.arcane.animus.core.text.Palette;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import java.util.ArrayList;
import java.util.List;

/** {@code /animus debug}, {@code proof} and {@code foliaproof}: measurements and packet previews. */
final class DiagnosticCommands {
    private DiagnosticCommands() {}

    @Director(name = "debug", description = "What Animus measured on this server")
    @AnimusPermission(DebugCommands.PERMISSION)
    static final class Debug extends CommandGroup {
        /** Who may switch debug logging; it can fill the server log. */
        static final String MODE_PERMISSION = "animus.debug.mode";

        Debug(CommandServices services) { super(services); }

        @Director(description = "NPCs, network, server time and memory at a glance")
        void summary() { services.debug().summary(actor()); }

        @Director(description = "Packets by kind, or one viewer's traffic")
        void network(@Param(name = "player", defaultValue = "", description = "One viewer's name or UUID") String player) {
            services.debug().network(actor(), ContentCommands.given(player));
        }

        @Director(description = "Server-thread and off-thread time by subsystem")
        void performance() { services.debug().performance(actor()); }

        @Director(description = "One NPC's packets and server time")
        void npc(@Param(name = "npc", description = "The NPC id, or @selected") String npc) {
            CommandActor actor = actor();
            npc(actor, npc).ifPresent((String id) -> services.debug().npc(actor, id));
        }

        @Director(description = "Toggle logging of every per-NPC diagnostic until the server restarts")
        @AnimusPermission(MODE_PERMISSION)
        void mode(@Param(name = "enabled", defaultValue = "toggle", description = "on, off, or toggle when left out") String enabled) {
            CommandActor actor = actor();
            AnimusLog log = AnimusLog.shared();
            Boolean chosen = switch (enabled.toLowerCase(java.util.Locale.ROOT)) {
                case "toggle" -> !log.debug();
                case "on", "true", "yes", "enabled" -> true;
                case "off", "false", "no", "disabled" -> false;
                default -> null;
            };
            if (chosen == null) { actor.tell(Palette.error("Unknown state " + enabled + "; use on, off or toggle")); return; }
            log.debug(chosen);
            actor.tell(Palette.success(chosen ? "Debug mode on: every NPC's route, activation and packet diagnostics log until restart"
                    : "Debug mode off: per-NPC diagnostics summarize every " + AnimusLog.WINDOW.toMinutes() + " min"));
        }

        @Director(description = "Toggle a live action-bar readout", origin = DirectorOrigin.PLAYER)
        void watch() { services.debug().watch(actor()); }
    }

    @Director(name = "proof", description = "Packet previews only you see; Bukkit and Paper only")
    static final class Proof extends CommandGroup {
        ProofControlGroup control;

        Proof(CommandServices services) {
            super(services);
            control = new ProofControlGroup(services);
        }

        private ProofControl proof() { return services.proof(); }

        @Director(description = "Show the preview state")
        void status() { proof().status(actor()); }

        @Director(description = "Spawn a preview entity in front of you", origin = DirectorOrigin.PLAYER)
        void spawn(@Param(name = "type", description = "The entity type, namespace:type") String type,
                   @Param(name = "skin", defaultValue = "", description = "A stored skin's SHA-256 hash") String skin) {
            CommandActor actor = actor();
            if (!skin.isEmpty() && !skin.matches("[a-f0-9]{64}")) { actor.tell(Palette.error("A skin is the SHA-256 hash of a stored skin")); return; }
            proof().spawn(actor, type, ContentCommands.given(skin));
        }

        @Director(description = "Move a preview entity by an offset", origin = DirectorOrigin.PLAYER)
        void move(@Param(name = "entity", description = "The preview entity id") int entity,
                  @Param(name = "dx", description = "X offset") double dx,
                  @Param(name = "dy", description = "Y offset") double dy,
                  @Param(name = "dz", description = "Z offset") double dz) { proof().move(actor(), entity, dx, dy, dz); }

        @Director(description = "Walk a preview entity along a path to an offset", origin = DirectorOrigin.PLAYER)
        void path(@Param(name = "entity", description = "The preview entity id") int entity,
                  @Param(name = "dx", description = "X offset in blocks") int dx,
                  @Param(name = "dy", description = "Y offset in blocks") int dy,
                  @Param(name = "dz", description = "Z offset in blocks") int dz) { proof().path(actor(), entity, dx, dy, dz); }

        @Director(description = "Seat preview entities on a vehicle", origin = DirectorOrigin.PLAYER)
        void mount(@Param(name = "vehicle", description = "The vehicle entity id") int vehicle,
                   @Param(name = "riders", defaultValue = "", description = "Comma-separated rider entity ids, up to 8") String riders) {
            CommandActor actor = actor();
            List<Integer> seated = new ArrayList<>();
            try { for (String rider : riders.split(",")) if (!rider.isBlank()) seated.add(Integer.parseInt(rider.strip())); }
            catch (NumberFormatException invalid) { actor.tell(Palette.error("riders are entity ids, such as riders=12,13")); return; }
            if (seated.size() > 8) { actor.tell(Palette.error("At most 8 riders")); return; }
            proof().mount(actor, vehicle, seated);
        }

        @Director(description = "Remove your preview entities", origin = DirectorOrigin.PLAYER)
        void clear() { proof().clear(actor()); }
    }

    @Director(name = "control", description = "Steer a preview entity")
    static final class ProofControlGroup extends CommandGroup {
        ProofControlGroup(CommandServices services) { super(services); }

        @Director(description = "Take control of a preview entity", origin = DirectorOrigin.PLAYER)
        void start(@Param(name = "entity", description = "The preview entity id") int entity) { services.proof().control(actor(), entity); }

        @Director(description = "Let go of the preview entity", origin = DirectorOrigin.PLAYER)
        void stop() { services.proof().stopControl(actor()); }

        @Director(description = "Show what you control", origin = DirectorOrigin.PLAYER)
        void status() { services.proof().controlStatus(actor()); }
    }

    @Director(name = "foliaproof", description = "Folia's stationary packet preview; Folia only")
    static final class FoliaProof extends CommandGroup {
        FoliaProof(CommandServices services) { super(services); }

        @Director(description = "Show the Folia preview state")
        void status() { services.proof().foliaStatus(actor()); }

        @Director(description = "Spawn a stationary preview in front of you", origin = DirectorOrigin.PLAYER)
        void spawn(@Param(name = "skin", defaultValue = "", description = "A stored skin's SHA-256 hash") String skin) {
            services.proof().foliaSpawn(actor(), ContentCommands.given(skin));
        }

        @Director(description = "Remove your stationary preview", origin = DirectorOrigin.PLAYER)
        void clear() { services.proof().foliaClear(actor()); }
    }
}
