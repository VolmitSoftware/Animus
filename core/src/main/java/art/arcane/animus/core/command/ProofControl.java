package art.arcane.animus.core.command;

import java.util.List;
import java.util.Optional;

/**
 * The packet preview tools behind {@code /animus proof} and {@code /animus proof folia}. They read Bukkit's server
 * internals, so every other platform refuses them with {@link #MOD_SERVER}. Called on the actor's owner.
 */
public interface ProofControl {
    String NOT_ON_FOLIA = "Not on Folia; use /animus proof folia";
    String FOLIA_ONLY = "Only on Folia; on this server use /animus proof spawn";
    String MOD_SERVER = "Packet previews need Paper, Spigot or Folia";

    /** Why the packet previews of {@code /animus proof} do not run on this platform; empty where they do. */
    default Optional<String> packetsMissing() { return Optional.empty(); }
    /** Why the stationary preview of {@code /animus proof folia} does not run on this platform; empty where it does. */
    default Optional<String> foliaMissing() { return Optional.empty(); }

    void status(CommandActor actor);
    void spawn(CommandActor actor, String type, Optional<String> skin);
    void move(CommandActor actor, int entity, double dx, double dy, double dz);
    void path(CommandActor actor, int entity, int dx, int dy, int dz);
    void control(CommandActor actor, int entity);
    void stopControl(CommandActor actor);
    void controlStatus(CommandActor actor);
    void mount(CommandActor actor, int vehicle, List<Integer> riders);
    void clear(CommandActor actor);
    void foliaStatus(CommandActor actor);
    void foliaSpawn(CommandActor actor, Optional<String> skin);
    void foliaClear(CommandActor actor);
    /** The actor's preview body ids, for completing {@code entity}, {@code vehicle} and {@code riders}. */
    default List<Integer> bodies(CommandActor actor) { return List.of(); }
}
