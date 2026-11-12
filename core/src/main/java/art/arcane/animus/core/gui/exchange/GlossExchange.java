package art.arcane.animus.core.gui.exchange;

import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.gui.GlossBridge;
import art.arcane.animus.core.gui.GuiKind;
import art.arcane.animus.core.gui.GuiValidator;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.storage.JsonDocumentTree;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Copies Gloss documents of the adopted kinds into Animus's {@code gui/} folders and back out. Every document is
 * validated before anything is written, a different existing file is kept unless {@code replace=true}, documents
 * with Animus-only actions never go to Gloss, and every skipped or refused document is listed with its reason.
 */
public final class GlossExchange {
    private static final Set<String> REFERENCE_FIELDS = Set.of("id", "target", "gui", "shop");
    private static final Pattern ANIMATION = Pattern.compile("\\|animation\\.([^|]*)\\|");
    private static final String NAME = "[A-Za-z0-9_-]{1,64}";
    private static final int DETAILS = 5;
    private final Path data;
    private final GuiValidator validator;
    private final Supplier<? extends GlossBridge<?>> bridge;
    private final Executor files;
    private final Consumer<String> log;

    public GlossExchange(Path data, GuiValidator validator) { this(data, validator, () -> GlossBridge.absent("Gloss is not installed"), Runnable::run, (String line) -> {}); }

    /**
     * @param bridge the current Gloss bridge; only its folder is used here
     * @param files runs file work off the server thread
     */
    public GlossExchange(Path data, GuiValidator validator, Supplier<? extends GlossBridge<?>> bridge, Executor files, Consumer<String> log) {
        this.data = Objects.requireNonNull(data);
        this.validator = Objects.requireNonNull(validator);
        this.bridge = Objects.requireNonNull(bridge);
        this.files = Objects.requireNonNull(files);
        this.log = Objects.requireNonNull(log);
    }

    /** One Animus document ready for Gloss. {@code json} is normalized JSON text. */
    public record Exported(GuiKind kind, String animusId, String glossId, String json, long updatedAt) {}

    /** What an import or export did. Lines read {@code <kind> <id>[: reason]}. */
    public record Report(List<String> written, List<String> unchanged, List<String> refused, List<String> skipped, List<String> notes) {
        public Report { written = List.copyOf(written); unchanged = List.copyOf(unchanged); refused = List.copyOf(refused); skipped = List.copyOf(skipped); notes = List.copyOf(notes); }

        public Report plus(Report other) {
            Builder builder = new Builder();
            for (Report part : List.of(this, other)) {
                part.written.forEach(builder::written); part.unchanged.forEach(builder::unchanged); part.refused.forEach(builder::refused);
                part.skipped.forEach(builder::skipped); part.notes.forEach(builder::note);
            }
            return builder.build();
        }

        public String summary() {
            return written.size() + " written, " + unchanged.size() + " unchanged, " + refused.size() + " refused, " + skipped.size() + " skipped";
        }

        public static final class Builder {
            private final List<String> written = new ArrayList<>(), unchanged = new ArrayList<>(), refused = new ArrayList<>(), skipped = new ArrayList<>(), notes = new ArrayList<>();
            public Builder written(String line) { written.add(line); return this; }
            public Builder unchanged(String line) { unchanged.add(line); return this; }
            public Builder refused(String line) { refused.add(line); return this; }
            public Builder skipped(String line) { skipped.add(line); return this; }
            public Builder note(String line) { notes.add(line); return this; }
            public Report build() { return new Report(written, unchanged, refused, skipped, notes); }
        }
    }

    public Report importDocuments(GlossSources.Batch batch, String namespace, boolean replace) throws IOException {
        Report.Builder skipped = new Report.Builder();
        batch.skipped().forEach(skipped::skipped);
        return skipped.build().plus(importDocuments(batch.documents(), namespace, replace));
    }

    /**
     * Validates every document first, then writes the accepted ones into {@code gui/<kind>/<namespace>/}. Animations are
     * checked first, so a hologram or menu may show an animation the same import brings; a refused one does not count.
     */
    public Report importDocuments(List<GlossDocument> documents, String namespace, boolean replace) throws IOException {
        if (!namespace.matches("[a-z0-9_-]{1,64}")) throw new IOException("namespace must use lowercase letters, digits, _ and -");
        Report.Builder report = new Report.Builder();
        Map<String, String> claimed = new HashMap<>();
        Map<Path, String> accepted = new LinkedHashMap<>();
        Map<Path, String> labels = new HashMap<>();
        Map<GuiKind, Set<String>> pending = new EnumMap<>(GuiKind.class);
        List<GlossDocument> ordered = new ArrayList<>(documents);
        ordered.sort(Comparator.comparingInt((GlossDocument document) -> document.kind() == GuiKind.ANIMATION ? 0 : 1));
        for (GlossDocument document : ordered) {
            String source = document.kind().word() + " " + document.id();
            Optional<String> mapped = GlossIds.toAnimus(document.id(), namespace);
            if (mapped.isEmpty()) { report.refused(source + ": the id cannot become an Animus id"); continue; }
            String id = mapped.get(), label = document.kind().word() + " " + id;
            String key = document.kind() + " " + id, first = claimed.putIfAbsent(key, document.id());
            if (first != null) { report.refused(source + ": maps to " + id + ", which " + first + " already took"); continue; }
            if (!id.substring(id.indexOf(':') + 1).equals(document.id())) report.note(source + " is imported as " + id);
            JsonObject parsed;
            try { parsed = JsonDocuments.object(document.json()); }
            catch (IOException invalid) { report.refused(label + ": " + invalid.getMessage()); continue; }
            int placeholders = GlossPlaceholders.translate(parsed);
            String text = JsonDocuments.write(parsed);
            List<String> problems = validator.problems(document.kind(), id, text, pending);
            if (!problems.isEmpty()) { report.refused(label + ": " + String.join("; ", problems.stream().limit(8).toList())); continue; }
            if (placeholders > 0) report.note(label + ": " + placeholders + " papi call" + (placeholders == 1 ? "" : "s") + " written as %placeholder% text");
            pending.computeIfAbsent(document.kind(), (GuiKind ignored) -> new HashSet<>()).add(id);
            Path file = file(document.kind(), id);
            switch (GlossFolders.compare(file, text)) {
                case SAME -> report.unchanged(label);
                case DIFFERENT -> { if (replace) { accepted.put(file, text); labels.put(file, label); } else report.refused(label + ": a different file exists; replace=true overwrites it"); }
                case MISSING -> { accepted.put(file, text); labels.put(file, label); }
            }
        }
        for (Map.Entry<Path, String> write : accepted.entrySet()) { GlossFolders.write(write.getKey(), write.getValue()); report.written(labels.get(write.getKey())); }
        return report.build();
    }

    /** Collects Animus documents for Gloss into {@code out}, optionally one kind and one Animus id. */
    public Report collect(Optional<GuiKind> kind, Optional<String> animusId, List<Exported> out) throws IOException {
        Report.Builder report = new Report.Builder();
        for (GuiKind each : GuiKind.values()) {
            if (kind.isPresent() && kind.get() != each) continue;
            Path root = data.resolve(each.folder());
            for (Path file : JsonDocumentTree.files(root)) {
                Path relative = root.relativize(file);
                if (relative.getNameCount() < 2) continue;
                String id = relative.getName(0) + ":" + relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/').replaceFirst("\\.json$", "");
                if (animusId.isPresent() && !animusId.get().equals(id)) continue;
                String label = each.word() + " " + id;
                if (!id.matches(ShopDocument.ID)) { report.refused(label + ": not a valid Animus id"); continue; }
                JsonObject parsed;
                try { parsed = JsonDocuments.object(JsonDocuments.read(file)); }
                catch (IOException invalid) { report.refused(label + ": " + invalid.getMessage()); continue; }
                Optional<String> animusAction = animusAction(parsed);
                if (animusAction.isPresent()) { report.skipped(label + ": uses the Animus-only action " + animusAction.get()); continue; }
                Optional<String> glossId = GlossIds.toGloss(each, id);
                if (glossId.isEmpty()) { report.refused(label + ": has no valid Gloss id; Gloss ids start with a letter or digit"); continue; }
                List<String> references = new ArrayList<>();
                references(parsed, null, references);
                if (!references.isEmpty()) report.note(label + " names " + String.join(", ", references.stream().distinct().limit(8).toList()) + "; Gloss does not resolve namespaced ids");
                out.add(new Exported(each, id, glossId.get(), JsonDocuments.write(parsed), Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis()));
            }
        }
        if (animusId.isPresent() && out.isEmpty() && report.build().skipped().isEmpty() && report.build().refused().isEmpty()) report.refused(animusId.get() + ": no such document");
        return report.build();
    }

    private Path file(GuiKind kind, String id) {
        int colon = id.indexOf(':');
        return data.resolve(kind.folder()).resolve(id.substring(0, colon)).resolve(id.substring(colon + 1) + ".json");
    }

    static Optional<String> animusAction(JsonElement value) {
        if (value.isJsonArray()) for (JsonElement item : value.getAsJsonArray()) { Optional<String> found = animusAction(item); if (found.isPresent()) return found; }
        if (!value.isJsonObject()) return Optional.empty();
        JsonElement type = value.getAsJsonObject().get("type");
        if (type != null && type.isJsonPrimitive() && type.getAsJsonPrimitive().isString() && type.getAsString().startsWith("animus:")) return Optional.of(type.getAsString());
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) { Optional<String> found = animusAction(entry.getValue()); if (found.isPresent()) return found; }
        return Optional.empty();
    }

    private static void references(JsonElement value, String field, List<String> found) {
        if (value.isJsonArray()) { for (JsonElement item : value.getAsJsonArray()) references(item, null, found); return; }
        if (value.isJsonObject()) { for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) references(entry.getValue(), entry.getKey(), found); return; }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return;
        String text = value.getAsString();
        if (field != null && REFERENCE_FIELDS.contains(field) && text.contains(":")) found.add(text);
        Matcher animation = ANIMATION.matcher(text);
        while (animation.find()) if (animation.group(1).contains(":")) found.add("|animation." + animation.group(1) + "|");
    }

    /** A GUI kind word, or empty for {@code all}. */
    public static Optional<GuiKind> kind(String word) {
        if (word.equalsIgnoreCase("all")) return Optional.empty();
        return Optional.of(GuiKind.word(word.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalArgumentException("Unknown kind " + word + "; use inventory, menu, hologram, animation or all")));
    }

    /** Copies Gloss's live documents into Animus; {@code reload} runs after files were written. */
    public void importGloss(CommandActor actor, Optional<GuiKind> kind, Optional<String> id, String namespace, boolean replace, Runnable reload) {
        transfer(actor, true, true, kind, id, "", namespace, replace, "folder", reload);
    }

    /** Imports a Gloss folder or {@code .json} bundle from {@code imports/inbox/}. */
    public void importFile(CommandActor actor, String name, String namespace, boolean replace, Runnable reload) {
        if (!name.matches(NAME + "(\\.json)?")) { actor.tell(Palette.error("GUI import takes a folder or .json bundle directly under imports/inbox/")); return; }
        transfer(actor, true, false, Optional.empty(), Optional.empty(), name, namespace, replace, "folder", reload);
    }

    /** Writes Animus documents into Gloss's live folders. */
    public void exportGloss(CommandActor actor, Optional<GuiKind> kind, Optional<String> id, boolean replace) {
        transfer(actor, false, true, kind, id, "", GlossIds.DEFAULT_NAMESPACE, replace, "folder", () -> {});
    }

    /** Writes a folder or a {@code .json} bundle under {@code exports/gui/}; the format follows the name unless given. */
    public void exportFile(CommandActor actor, String name, Optional<String> format) {
        if (!name.matches(NAME + "(\\.json)?")) { actor.tell(Palette.error("GUI export takes a folder or .json bundle name for exports/gui/")); return; }
        String chosen = format.orElse(name.endsWith(".json") ? "bundle" : "folder").toLowerCase(Locale.ROOT);
        if (!Set.of("folder", "bundle").contains(chosen)) { actor.tell(Palette.error("GUI export: format is folder or bundle")); return; }
        transfer(actor, false, false, Optional.empty(), Optional.empty(), name, GlossIds.DEFAULT_NAMESPACE, false, chosen, () -> {});
    }

    private void transfer(CommandActor actor, boolean importing, boolean live, Optional<GuiKind> chosenKind, Optional<String> chosenId, String name,
                          String namespace, boolean replace, String format, Runnable reload) {
        GlossBridge<?> gloss = bridge.get();
        if (live && gloss.unavailable() != null) { actor.tell(gloss.unavailable()); return; }
        if (chosenId.isPresent() && chosenKind.isEmpty()) { actor.tell(Palette.error("id= needs a kind other than all")); return; }
        try {
            files.execute(() -> {
                try {
                    Report report;
                    if (importing) {
                        GlossSources.Batch batch = live
                                ? new GlossSources.Batch(GlossFolders.readLive(gloss.folder(), chosenKind, chosenId), List.of())
                                : GlossSources.read(data.resolve("imports/inbox").resolve(name));
                        if (batch.documents().isEmpty() && batch.skipped().isEmpty()) { answer(actor, "Nothing to import"); return; }
                        report = importDocuments(batch, namespace, replace);
                    } else {
                        List<Exported> exported = new ArrayList<>();
                        report = collect(chosenKind, chosenId, exported);
                        if (live) report = report.plus(GlossFolders.writeLive(gloss.folder(), exported, replace));
                        else if (format.equals("bundle")) {
                            GlossBundle.write(data.resolve("exports/gui").resolve(name.endsWith(".json") ? name : name + ".json"), exported);
                            Report.Builder written = new Report.Builder();
                            for (Exported document : exported) written.written(document.kind().word() + " " + document.animusId() + " as " + document.glossId());
                            report = report.plus(written.build());
                        } else report = report.plus(GlossFolders.writeExport(data.resolve("exports/gui").resolve(name.replaceFirst("\\.json$", "")), exported));
                    }
                    tell(actor, (importing ? "GUI import: " : "GUI export: ") + report.summary(), report);
                    if (importing && !report.written().isEmpty()) reload.run();
                } catch (IOException | RuntimeException failure) { answer(actor, Palette.error((importing ? "GUI import: " : "GUI export: ") + failure.getMessage())); }
            });
        } catch (RejectedExecutionException full) { actor.tell(Palette.error(CommandActor.BUSY)); }
    }

    private void tell(CommandActor actor, String summary, Report report) {
        List<String> lines = new ArrayList<>();
        lines.add(summary);
        List<String> details = new ArrayList<>();
        report.refused().forEach((String line) -> details.add("Refused " + line));
        report.skipped().forEach((String line) -> details.add("Skipped " + line));
        report.notes().forEach((String line) -> details.add("Note: " + line));
        report.written().forEach((String line) -> details.add("Wrote " + line));
        details.stream().limit(DETAILS).forEach(lines::add);
        if (details.size() > DETAILS) lines.add("+" + (details.size() - DETAILS) + " more");
        if (!actor.reply(() -> lines.forEach(actor::tell))) lines.forEach(log);
    }

    private void answer(CommandActor actor, String message) { if (!actor.reply(() -> actor.tell(message))) log.accept(message); }
}
