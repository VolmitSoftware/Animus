package art.arcane.animus.core.onboarding;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.migration.CitizensDropIn;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.ChatLine;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * First-run guidance shared by every platform: the state {@code /animus setup} shows, the one-line hint an admin gets on
 * join, and the startup migration notice. The hint goes to each admin once per server run while the server has no NPCs
 * or unfinished migration data, until someone dismisses it or runs {@code /animus setup}; that choice is stored per
 * server in {@link #PATH}. Call the file methods from the serial file lane.
 */
public final class Onboarding {
    public static final String PATH = "state/onboarding.json";
    /** How long after a join the hint waits, so the join messages do not bury it. */
    public static final long JOIN_DELAY_TICKS = 40;
    public static final String DISMISS = SetupGuide.SETUP + " dismiss=true";
    private final DocumentStore store;
    private final NpcRepository npcs;
    private final CitizensDropIn.Host host;
    private final Set<UUID> hinted = ConcurrentHashMap.newKeySet();

    public Onboarding(DocumentStore store, NpcRepository npcs, CitizensDropIn.Host host) {
        this.store = Objects.requireNonNull(store);
        this.npcs = Objects.requireNonNull(npcs);
        this.host = Objects.requireNonNull(host);
    }

    public static Onboarding of(AnimusRuntime runtime) { return new Onboarding(runtime.documents(), runtime.npcs(), runtime.importHost()); }

    public SetupState state(boolean editorEnabled, int editorTokens) throws IOException {
        return new SetupState(npcs.all().size(), migration().progress(), editorEnabled, editorTokens);
    }

    public boolean dismissed() throws IOException {
        Path file = store.resolve(PATH);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
        JsonObject flag = JsonDocuments.object(JsonDocuments.read(file));
        return flag.has("joinHintDismissed") && flag.get("joinHintDismissed").getAsBoolean();
    }

    public void dismiss() throws IOException {
        if (dismissed()) return;
        JsonObject flag = new JsonObject();
        flag.addProperty("schemaVersion", 1);
        flag.addProperty("joinHintDismissed", true);
        DocumentStore.atomic(store.resolve(PATH), JsonDocuments.write(flag));
    }

    /** The hint for an admin who just joined, or empty when it was dismissed, already shown this run, or nothing needs doing. */
    public Optional<ChatLine> joinHint(UUID player) throws IOException {
        if (dismissed() || hinted.contains(player)) return Optional.empty();
        CitizensDropIn.Progress migration = migration().progress();
        String found = switch (migration.phase()) {
            case FOUND -> "Found " + migration.source().orElse("Citizens data") + "; nothing imported yet";
            case STAGED -> "Citizens import staged, not installed yet";
            case APPLIED -> "Citizens import installed; " + migration.waiting() + " imported settings still off";
            case NONE, DONE -> npcs.all().isEmpty() ? "Animus is running with no NPCs yet" : null;
        };
        if (found == null || !hinted.add(player)) return Optional.empty();
        return Optional.of(ChatLine.reply(found).run("Setup", SetupGuide.SETUP, "Show the first steps").run("Dismiss", DISMISS, "Stop this hint for every admin"));
    }

    /** The startup migration line for the log, or empty. */
    public Optional<String> startupNotice() throws IOException { return migration().startupNotice(); }

    /** The startup notice as online admins see it. */
    public static ChatLine adminNotice(String notice) { return ChatLine.reply(notice).run("Setup", SetupGuide.SETUP, "Show the first steps"); }

    private CitizensDropIn migration() { return new CitizensDropIn(store, npcs, host); }
}
