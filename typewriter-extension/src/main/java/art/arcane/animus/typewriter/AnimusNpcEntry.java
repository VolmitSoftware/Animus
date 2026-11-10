package art.arcane.animus.typewriter;

import com.typewritermc.engine.paper.entry.StaticEntry;
import com.typewritermc.engine.paper.entry.entries.ConstVar;
import com.typewritermc.engine.paper.entry.entries.SoundEmitter;
import com.typewritermc.engine.paper.entry.entries.SoundSourceEntry;
import com.typewritermc.engine.paper.entry.entries.SpeakerEntry;
import com.typewritermc.engine.paper.entry.entries.Var;
import com.typewritermc.engine.paper.utils.Sound;
import org.bukkit.entity.Player;

/**
 * The {@code animus_npc} entry: an Animus NPC, named by its id, as a Typewriter speaker and sound source. Typewriter fills
 * the fields from the page; the defaults match an empty entry.
 */
public final class AnimusNpcEntry implements SoundSourceEntry, SpeakerEntry, StaticEntry {
    private String id = "";
    private String name = "";
    private Var<String> displayName = new ConstVar<>("");
    private Var<Sound> sound = new ConstVar<>(Sound.Companion.getEMPTY());
    private String npc = "";

    @Override public String getId() { return id; }
    @Override public String getName() { return name; }
    @Override public Var<String> getDisplayName() { return displayName; }
    @Override public Var<Sound> getSound() { return sound; }
    /** The Animus NPC id, such as {@code guard}. */
    public String getNpc() { return npc; }

    /** Animus NPCs have no server entity, so speech sounds come from the listener. */
    @Override public SoundEmitter getEmitter(Player player) { return new SoundEmitter(player.getEntityId()); }
}
