package art.arcane.animus.core.editor;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Viewer-private previews. A preview shows candidate NPCs to the token's admin player only, never writes, and is refused
 * when a candidate would run commands, combat, item transfers, economy or trades. It ends after {@code previewMinutes},
 * when the token's event streams close, when the player leaves, or on request.
 */
public final class EditorPreviews {
    /** Behavior capabilities and GUI action types a preview never runs (editor-v1.endpoints.json). */
    public static final Set<String> REFUSED_CAPABILITIES = Set.of("animus:command", "animus:combat-control");
    public static final Set<String> REFUSED_GUI_ACTIONS = Set.of("command", "give", "take", "economy", "animus:trade");

    public record Preview(String id, String tokenId, UUID viewer, Instant expires, List<String> npcs) {
        public Preview { npcs = List.copyOf(npcs); }
    }

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Preview> running = new LinkedHashMap<>();
    private final BiConsumer<String, String> ended;

    /** {@code ended} receives the preview id and its end state for the event stream. */
    public EditorPreviews(Clock clock, BiConsumer<String, String> ended) {
        this.clock = Objects.requireNonNull(clock);
        this.ended = Objects.requireNonNull(ended);
    }

    /** Field diagnostics for everything in a candidate that a preview refuses. */
    public static JsonArray refusals(DocumentKind kind, JsonObject source) {
        JsonArray diagnostics = new JsonArray();
        switch (kind) {
            case BEHAVIOR -> scanCapabilities(source, "$", diagnostics);
            case INVENTORY, MENU, HOLOGRAM -> scanActions(source, "$", false, diagnostics);
            case SHOP -> diagnostics.add(EditorDocuments.diagnostic("error", "$", "shops move items and money, so a preview cannot run them; apply to try a shop", "preview-refused"));
            default -> { }
        }
        return diagnostics;
    }

    private static void scanCapabilities(JsonElement value, String at, JsonArray diagnostics) {
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            JsonElement capability = object.get("capability");
            if (capability != null && capability.isJsonPrimitive() && REFUSED_CAPABILITIES.contains(capability.getAsString()))
                diagnostics.add(EditorDocuments.diagnostic("error", at + ".capability", capability.getAsString() + " does not run in a preview", "preview-refused"));
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) scanCapabilities(entry.getValue(), at + "." + entry.getKey(), diagnostics);
        } else if (value.isJsonArray()) {
            int index = 0;
            for (JsonElement element : value.getAsJsonArray()) scanCapabilities(element, at + "[" + index++ + "]", diagnostics);
        }
    }

    private static void scanActions(JsonElement value, String at, boolean inActions, JsonArray diagnostics) {
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            JsonElement type = object.get("type");
            if (inActions && type != null && type.isJsonPrimitive() && REFUSED_GUI_ACTIONS.contains(type.getAsString()))
                diagnostics.add(EditorDocuments.diagnostic("error", at + ".type", "action '" + type.getAsString() + "' does not run in a preview", "preview-refused"));
            for (Map.Entry<String, JsonElement> entry : object.entrySet())
                scanActions(entry.getValue(), at + "." + entry.getKey(), inActions || entry.getKey().equals("actions") || entry.getKey().endsWith("Actions"), diagnostics);
        } else if (value.isJsonArray()) {
            int index = 0;
            for (JsonElement element : value.getAsJsonArray()) scanActions(element, at + "[" + index++ + "]", inActions, diagnostics);
        }
    }

    /** NPC definitions the preview presents. Refuses an invalid or refused candidate with the field diagnostics. */
    public static List<NpcDefinition> candidates(List<EditorMessages.Change> changes, int maximumNpcs, int recordingBytes) throws EditorError {
        JsonArray documents = new JsonArray();
        List<NpcDefinition> npcs = new ArrayList<>();
        boolean refused = false;
        for (EditorMessages.Change change : changes) {
            if (change.delete()) continue;
            JsonObject source = change.source().orElseThrow().deepCopy();
            if (EditorDocuments.REVISIONED.contains(change.kind()) && !source.has("revision")) source.addProperty("revision", 1);
            JsonArray diagnostics = EditorDocuments.validate(change.kind(), change.id(), source, recordingBytes);
            JsonArray refusals = refusals(change.kind(), source);
            refused |= !refusals.isEmpty();
            refusals.forEach(diagnostics::add);
            if (EditorDocuments.hasErrors(diagnostics)) documents.add(EditorChanges.documentDiagnostics(change.kind(), change.id(), diagnostics));
            else if (change.kind() == DocumentKind.NPC) {
                try { npcs.add(NpcDefinition.parse(JsonDocuments.write(source))); }
                catch (IOException unreachable) { throw new EditorError(EditorError.Code.INTERNAL, "A validated NPC no longer parses."); }
            }
        }
        if (!documents.isEmpty()) {
            JsonArray flat = new JsonArray();
            for (JsonElement entry : documents)
                for (JsonElement diagnostic : entry.getAsJsonObject().getAsJsonArray("diagnostics")) flat.add(diagnostic);
            throw new EditorError(refused ? EditorError.Code.PREVIEW_REFUSED : EditorError.Code.INVALID_REQUEST,
                    refused ? "The preview would run something previews refuse: " + names(documents) : "Some documents are invalid: " + names(documents), -1, flat);
        }
        if (npcs.size() > maximumNpcs) throw new EditorError(EditorError.Code.TOO_LARGE, "A preview shows at most " + maximumNpcs + " NPCs.");
        return npcs;
    }

    private static String names(JsonArray documents) {
        List<String> names = new ArrayList<>();
        for (JsonElement entry : documents) names.add(entry.getAsJsonObject().get("kind").getAsString() + " " + entry.getAsJsonObject().get("id").getAsString());
        return String.join(", ", names);
    }

    /** Reserves a preview slot, or replaces the token's own preview. Returns the preview to present. */
    public synchronized Preview start(Optional<String> replace, String tokenId, UUID viewer, List<String> npcs, int maximum, Duration lifetime) throws EditorError {
        expire();
        if (replace.isPresent()) {
            Preview previous = running.get(replace.get());
            if (previous == null || !previous.tokenId().equals(tokenId)) throw new EditorError(EditorError.Code.NOT_FOUND, "No running preview " + replace.get() + " for this token.");
            Preview next = new Preview(previous.id(), tokenId, viewer, clock.instant().plus(lifetime), npcs);
            running.put(next.id(), next);
            return next;
        }
        if (running.size() >= maximum) throw new EditorError(EditorError.Code.BUSY, "This server runs at most " + maximum + " previews at once.", 30_000, null);
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        Preview preview = new Preview("prev_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), tokenId, viewer, clock.instant().plus(lifetime), npcs);
        running.put(preview.id(), preview);
        return preview;
    }

    public synchronized Optional<Preview> find(String id) { return Optional.ofNullable(running.get(id)); }

    /** Ends one preview with a state of the preview event: ended, expired or player-left. */
    public void end(String id, String state) {
        Preview removed;
        synchronized (this) { removed = running.remove(id); }
        if (removed != null) ended.accept(id, state);
    }

    public void endToken(String tokenId) {
        List<String> ids = new ArrayList<>();
        synchronized (this) { for (Preview preview : running.values()) if (preview.tokenId().equals(tokenId)) ids.add(preview.id()); }
        ids.forEach((String id) -> end(id, "ended"));
    }

    public void endAll() {
        List<String> ids;
        synchronized (this) { ids = new ArrayList<>(running.keySet()); }
        ids.forEach((String id) -> end(id, "ended"));
    }

    /** Ends previews past their lifetime. */
    public void expire() {
        List<String> ids = new ArrayList<>();
        synchronized (this) { for (Preview preview : running.values()) if (!clock.instant().isBefore(preview.expires())) ids.add(preview.id()); }
        ids.forEach((String id) -> end(id, "expired"));
    }

    public synchronized int count() { return running.size(); }
}
