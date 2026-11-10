package art.arcane.animus.bukkit.integration.modelengine;

import art.arcane.animus.core.integration.ModelEngineModule;
import art.arcane.animus.core.presentation.BodyPose;
import java.util.UUID;

/**
 * Draws one model per NPC through a model plugin. {@link ModeledTransport} decides when; an implementation only talks
 * to its plugin. Every call is made on the server thread that owns the NPC's viewers.
 *
 * @param <P> the platform's online player
 */
public interface ModelDriver<P> extends AutoCloseable {
    /** A click on a model's hitbox, reported on the owning thread. */
    @FunctionalInterface interface Clicks<P> { void clicked(UUID npc, P player, boolean attack, String hand, boolean secondary); }

    /**
     * Starts showing the model at {@code pose} to nobody yet. {@code name} is the NPC's name for a nametag bone. False
     * when the plugin cannot draw it, such as a model id it does not have.
     */
    boolean show(UUID npc, ModelEngineModule module, String name, BodyPose pose);
    /** Shows or hides the model to one viewer. */
    void view(UUID npc, P viewer, boolean visible);
    void move(UUID npc, BodyPose pose);
    /** The model plays its walk animation while true and its idle animation while false. */
    void walking(UUID npc, boolean walking);
    /** Plays the animation of a one-off state: attack, hurt, death or interact. */
    void play(UUID npc, ModelEngineModule.State state);
    void glowing(UUID npc, boolean glowing);
    /** The drawn model's hitbox at its scale, in blocks; empty when the NPC shows no model. */
    java.util.Optional<Size> hitbox(UUID npc);
    record Size(double width, double height) {}
    /** Stops showing the model to everyone and forgets it. */
    void remove(UUID npc);
    void clicks(Clicks<P> listener);
    @Override void close();
}
