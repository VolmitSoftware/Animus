package art.arcane.animus.core.command;

import art.arcane.animus.core.text.Palette;
import java.util.List;
import java.util.Optional;

/**
 * The packet preview tools behind {@code /animus proof} and {@code /animus foliaproof}. They read Bukkit's server
 * internals, so every other platform answers with {@link #unavailable}. Called on the actor's owner.
 */
public interface ProofControl {
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

    /** Every preview command refuses with {@code reason}. */
    static ProofControl unavailable(String reason) {
        String refusal = Palette.error(reason);
        return new ProofControl() {
            @Override public void status(CommandActor actor) { actor.tell(refusal); }
            @Override public void spawn(CommandActor actor, String type, Optional<String> skin) { actor.tell(refusal); }
            @Override public void move(CommandActor actor, int entity, double dx, double dy, double dz) { actor.tell(refusal); }
            @Override public void path(CommandActor actor, int entity, int dx, int dy, int dz) { actor.tell(refusal); }
            @Override public void control(CommandActor actor, int entity) { actor.tell(refusal); }
            @Override public void stopControl(CommandActor actor) { actor.tell(refusal); }
            @Override public void controlStatus(CommandActor actor) { actor.tell(refusal); }
            @Override public void mount(CommandActor actor, int vehicle, List<Integer> riders) { actor.tell(refusal); }
            @Override public void clear(CommandActor actor) { actor.tell(refusal); }
            @Override public void foliaStatus(CommandActor actor) { actor.tell(refusal); }
            @Override public void foliaSpawn(CommandActor actor, Optional<String> skin) { actor.tell(refusal); }
            @Override public void foliaClear(CommandActor actor) { actor.tell(refusal); }
        };
    }
}
