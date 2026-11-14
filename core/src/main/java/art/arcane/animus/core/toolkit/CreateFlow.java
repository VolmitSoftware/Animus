package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.npc.DefinitionBatch;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.storage.NpcPaths;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Home's New NPC: a name, the id made from it (Advanced may change it), a body, and for a player body a skin. Nothing is
 * written before the last step, which saves the NPC and any new skin file in one transaction, so one Undo takes it all back.
 */
final class CreateFlow {
    /** The id of an NPC whose name has no letters or digits to make one from. */
    static final String FALLBACK = "npc";
    private static final int LONGEST = 64;
    final String name, id;
    final SkinLookup lookup;
    /** Advanced can save the new NPC despawned. */
    boolean spawn = true;
    /** The step on screen, so a skin lookup that answers after the player stepped back makes nothing. */
    private ToolScreen<?, ?> showing;

    /** What the last step makes. */
    record Plan(String name, String id, String type, boolean spawn, Optional<SkinFiles.Skin> skin) {}

    /** The new NPC's file and the skin file it brings when that is new, checked and ready to commit. */
    record Draft(NpcDefinition definition, List<DocumentStore.Change> skin) {
        Draft { skin = List.copyOf(skin); }
    }

    CreateFlow(String name, String id, SkinLookup lookup) { this.name = name; this.id = id; this.lookup = lookup; }

    /** New NPC: asks for the name, in Advanced for the id, then shows the Body step. */
    static <P, S> void start(ToolScreen<P, S> from) { start(from, SkinLookup.shared()); }

    static <P, S> void start(ToolScreen<P, S> from, SkinLookup lookup) {
        if (!from.allows(PermissionNodes.NPC_CREATE)) return;
        from.prompt("Name", "", (String typed) -> {
            String name = typed.strip();
            if (name.isEmpty()) { from.refuse("A name needs at least one character"); return; }
            Set<String> taken = taken(from.toolkit.npcs());
            String id = id(name, taken);
            if (!from.advanced()) { body(from, new CreateFlow(name, id, lookup)); return; }
            from.prompt("Id", id, (String chosen) -> {
                String picked = chosen.strip();
                if (!picked.matches(NpcPaths.SEGMENT)) from.refuse("An id is 1 to 64 lowercase letters, digits, _ or -");
                else if (taken(from.toolkit.npcs()).contains(picked)) from.refuse("An NPC named " + picked + " already exists");
                else body(from, new CreateFlow(name, picked, lookup));
            });
        });
    }

    private static <P, S> void body(ToolScreen<P, S> from, CreateFlow flow) { from.push(new BodyScreen<>(from.toolkit, from.viewer, from.commands, flow)); }

    /** The Body step picked {@code type}: a player body goes on to the Skin step, any other body is made now. */
    <P, S> void body(ToolScreen<P, S> screen, String type) {
        if (type.equals(ToolkitFiles.PLAYER)) screen.push(new SkinScreen<>(screen.toolkit, screen.viewer, screen.commands, this));
        else make(screen, type, Optional.empty());
    }

    /** Makes the NPC from {@code screen}, the step the player is on. */
    <P, S> void make(ToolScreen<P, S> screen, String type, Optional<SkinFiles.Skin> skin) {
        screen.toolkit.create(screen.viewer, new Plan(name, id, type, spawn, skin));
    }

    /** A step drew itself, so it is the one on screen. */
    void showing(ToolScreen<?, ?> step) { showing = step; }

    boolean shows(ToolScreen<?, ?> step) { return showing == step; }

    /**
     * {@code Greeter Bob} reads {@code greeter-bob}: lowercase, every run of other characters one dash, trimmed to start
     * with a letter or digit, then {@code -2}, {@code -3} past the ids in {@code taken}. A name with nothing left is {@value #FALLBACK}.
     */
    static String id(String name, Set<String> taken) {
        String base = cut(name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("-{2,}", "-").replaceAll("^[^a-z0-9]+", ""), LONGEST);
        if (base.isEmpty()) base = FALLBACK;
        String id = base;
        for (int n = 2; taken.contains(id); n++) {
            String suffix = "-" + n;
            id = cut(base, LONGEST - suffix.length()) + suffix;
        }
        return id;
    }

    private static String cut(String id, int length) { return id.substring(0, Math.min(id.length(), length)).replaceAll("-+$", ""); }

    private static Set<String> taken(NpcRepository npcs) {
        Set<String> ids = new HashSet<>();
        for (NpcDefinition definition : npcs.stored()) ids.add(definition.id());
        return ids;
    }

    /**
     * The file {@code /animus create} would write, with the name, body and skin picked here, {@code owner} as its owner
     * and its place at {@code spot}. Refused when the id is taken or a saved skin is gone. Runs on the file lane.
     */
    static Draft draft(NpcRepository npcs, DocumentStore store, Plan plan, String world, Landing.Spot spot, UUID owner) throws IOException {
        if (!plan.id().matches(NpcPaths.SEGMENT)) throw new IOException("An id is 1 to 64 lowercase letters, digits, _ or -");
        if (taken(npcs).contains(plan.id())) throw new IOException("An NPC named " + plan.id() + " already exists");
        List<DocumentStore.Change> skin = new ArrayList<>();
        if (plan.skin().isPresent()) {
            SkinFiles.Skin chosen = plan.skin().get();
            chosen.file(store).ifPresent(skin::add);
            if (skin.isEmpty() && store.read("skins/" + chosen.hash() + ".json") == null) throw new IOException("That skin was deleted; pick another");
        }
        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", NpcDefinition.SCHEMA_VERSION); document.addProperty("revision", 1);
        document.addProperty("uuid", UUID.randomUUID().toString()); document.addProperty("id", plan.id());
        document.addProperty("type", plan.type()); document.addProperty("group", "");
        document.addProperty("name", plan.name());
        document.addProperty("spawned", plan.spawn());
        document.addProperty("owner", owner.toString());
        plan.skin().ifPresent((SkinFiles.Skin chosen) -> document.addProperty("skin", chosen.hash()));
        JsonObject position = new JsonObject();
        position.addProperty("world", world);
        position.addProperty("x", Math.round(spot.at().x() * 100) / 100.0); position.addProperty("y", Math.round(spot.at().y() * 100) / 100.0); position.addProperty("z", Math.round(spot.at().z() * 100) / 100.0);
        position.addProperty("yaw", Math.round(spot.yaw() * 100) / 100f); position.addProperty("pitch", 0);
        document.add("position", position);
        return new Draft(NpcDefinition.parse(JsonDocuments.write(document)), skin);
    }

    /** Saves the drafted NPC and its new skin file in one transaction; returns the transaction id. */
    static String commit(NpcRepository npcs, DocumentStore store, Draft draft) throws IOException {
        String tx = DefinitionBatch.commit(store, List.of(draft.definition()), Map.of(draft.definition().path(), "missing"), draft.skin());
        npcs.reload();
        return tx;
    }
}
