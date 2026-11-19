package art.arcane.animus.mod;

import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.command.ProofControl;
import art.arcane.animus.core.text.Palette;
import java.util.List;
import java.util.Optional;

/** Packet previews read Bukkit's server internals, so on mod servers every preview command refuses and help leaves them out. */
final class ModProof implements ProofControl {
    private static final String REFUSAL = Palette.error(MOD_SERVER);

    @Override public Optional<String> packetsMissing() { return Optional.of(MOD_SERVER); }
    @Override public Optional<String> foliaMissing() { return Optional.of(MOD_SERVER); }
    @Override public void status(CommandActor actor) { actor.tell(REFUSAL); }
    @Override public void spawn(CommandActor actor, String type, Optional<String> skin) { actor.tell(REFUSAL); }
    @Override public void move(CommandActor actor, int entity, double dx, double dy, double dz) { actor.tell(REFUSAL); }
    @Override public void path(CommandActor actor, int entity, int dx, int dy, int dz) { actor.tell(REFUSAL); }
    @Override public void control(CommandActor actor, int entity) { actor.tell(REFUSAL); }
    @Override public void stopControl(CommandActor actor) { actor.tell(REFUSAL); }
    @Override public void controlStatus(CommandActor actor) { actor.tell(REFUSAL); }
    @Override public void mount(CommandActor actor, int vehicle, List<Integer> riders) { actor.tell(REFUSAL); }
    @Override public void clear(CommandActor actor) { actor.tell(REFUSAL); }
    @Override public void foliaStatus(CommandActor actor) { actor.tell(REFUSAL); }
    @Override public void foliaSpawn(CommandActor actor, Optional<String> skin) { actor.tell(REFUSAL); }
    @Override public void foliaClear(CommandActor actor) { actor.tell(REFUSAL); }
}
