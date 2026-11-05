package art.arcane.animus.core.rig;

import art.arcane.animus.core.storage.JsonDocumentTree;
import art.arcane.animus.core.storage.JsonDocuments;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Every rig under {@code rigs/} and animation under {@code animations/}. A file at {@code <namespace>/<path>.json} has the
 * id {@code namespace:path}. Reload rules follow behaviors: a broken or refused edit keeps the last valid document, a
 * changed document needs a higher {@code revision}, and after a restart the last valid source comes from its checkpoint.
 * Read on the file lane; the published catalog is immutable.
 */
public record RigCatalog(long generation, Map<String, RigDocument> rigs, Map<String, AnimationDocument> animations, List<String> problems) {
    public static final String RIG_FOLDER = "rigs", ANIMATION_FOLDER = "animations";
    public static final int MAX_DOCUMENTS = 4096;
    public static final RigCatalog EMPTY = new RigCatalog(0, Map.of(), Map.of(), List.of());

    public RigCatalog {
        rigs = Collections.unmodifiableMap(new TreeMap<>(rigs));
        animations = Collections.unmodifiableMap(new TreeMap<>(animations));
        problems = List.copyOf(problems);
    }

    public Optional<RigDocument> rig(String id) { return Optional.ofNullable(rigs.get(id)); }
    public Optional<AnimationDocument> animation(String id) { return Optional.ofNullable(animations.get(id)); }
    /** Lookup for playback. */
    public Function<String, Optional<AnimationDocument>> clips() { return this::animation; }

    private record Candidate<T>(String path, String id, Optional<T> document, List<String> problems) {}

    public static RigCatalog load(Path data, RigCatalog previous, RigCheckpoints checkpoints) throws IOException {
        List<String> problems = new ArrayList<>();
        Map<String, RigCheckpoints.Saved> saved = previous.generation() == 0 ? checkpoints.load(data) : Map.of();
        Map<String, String> sources = new HashMap<>();
        Map<String, Long> revisions = new HashMap<>();
        Set<String> present = new HashSet<>();

        Map<String, RigDocument> rigs = new TreeMap<>();
        for (Candidate<RigDocument> candidate : scan(data, RIG_FOLDER, problems, present, (String label, String id, String text) -> {
            RigDocument.Result result = RigDocument.parse(id, text);
            return new Candidate<>(label, id, result.document(), result.problems());
        })) {
            Optional<RigDocument> kept = Optional.ofNullable(previous.rigs().get(candidate.id()));
            Optional<RigDocument> chosen = choose(candidate, kept, RigDocument::revision, RigDocument::source, saved, (String text) -> RigDocument.parse(candidate.id(), text).document(), problems);
            chosen.ifPresent((RigDocument rig) -> { rigs.put(rig.id(), rig); sources.put(candidate.path(), rig.source()); revisions.put(candidate.path(), rig.revision()); });
        }

        Map<String, AnimationDocument> animations = new TreeMap<>();
        for (Candidate<AnimationDocument> candidate : scan(data, ANIMATION_FOLDER, problems, present, (String label, String id, String text) -> {
            AnimationDocument.Result result = AnimationDocument.parse(id, text);
            return new Candidate<>(label, id, result.document(), result.problems());
        })) {
            Candidate<AnimationDocument> checked = candidate.document().map((AnimationDocument animation) -> against(candidate, animation, rigs)).orElse(candidate);
            Optional<AnimationDocument> kept = Optional.ofNullable(previous.animations().get(candidate.id()))
                    .filter((AnimationDocument animation) -> rigs.containsKey(animation.rig()) && animation.problemsAgainst(rigs.get(animation.rig())).isEmpty());
            Optional<AnimationDocument> chosen = choose(checked, kept, AnimationDocument::revision, AnimationDocument::source, saved,
                    (String text) -> AnimationDocument.parse(candidate.id(), text).document()
                            .filter((AnimationDocument animation) -> rigs.containsKey(animation.rig()) && animation.problemsAgainst(rigs.get(animation.rig())).isEmpty()), problems);
            chosen.ifPresent((AnimationDocument animation) -> { animations.put(animation.id(), animation); sources.put(candidate.path(), animation.source()); revisions.put(candidate.path(), animation.revision()); });
        }
        for (RigDocument rig : rigs.values())
            for (Map.Entry<String, String> named : rig.animations().entrySet())
                if (!animations.containsKey(named.getValue())) problems.add(RIG_FOLDER + ": " + rig.id() + " names " + named.getKey() + " animation " + named.getValue() + ", which is not loaded");
        try { checkpoints.update(sources, revisions, present); }
        catch (IOException failure) { problems.add("state/rig-catalog: last-valid checkpoints were not written: " + failure.getMessage()); }
        return new RigCatalog(previous.generation() + 1, rigs, animations, problems);
    }

    private static Candidate<AnimationDocument> against(Candidate<AnimationDocument> candidate, AnimationDocument animation, Map<String, RigDocument> rigs) {
        RigDocument rig = rigs.get(animation.rig());
        List<String> problems = rig == null ? List.of("$.rig: " + animation.rig() + " is not a loaded rig") : animation.problemsAgainst(rig);
        return problems.isEmpty() ? candidate : new Candidate<>(candidate.path(), candidate.id(), Optional.empty(), problems);
    }

    @FunctionalInterface private interface Parser<T> { Candidate<T> parse(String label, String id, String source); }

    private static <T> List<Candidate<T>> scan(Path data, String folder, List<String> problems, Set<String> present, Parser<T> parser) throws IOException {
        Path root = data.resolve(folder);
        List<Candidate<T>> candidates = new ArrayList<>();
        List<Path> files;
        try { files = JsonDocumentTree.files(root, 16); }
        catch (IOException unreadable) { problems.add(folder + ": " + unreadable.getMessage()); return candidates; }
        for (Path file : files) {
            if (candidates.size() >= MAX_DOCUMENTS) { problems.add(folder + ": more than " + MAX_DOCUMENTS + " documents; the rest were skipped"); break; }
            Path relative = root.relativize(file);
            String label = folder + "/" + relative.toString().replace('\\', '/');
            if (relative.getNameCount() < 2) { problems.add(label + ": place documents in a namespace folder, such as town/" + relative); continue; }
            String id = relative.getName(0) + ":" + relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/').replaceFirst("\\.json$", "");
            if (!id.matches(RigFields.DOCUMENT_ID)) { problems.add(label + ": file names use lowercase letters, digits, _, - and /"); continue; }
            present.add(label);
            String source;
            try { source = JsonDocuments.read(file); }
            catch (IOException unreadable) { candidates.add(new Candidate<>(label, id, Optional.empty(), List.of("$: " + unreadable.getMessage()))); continue; }
            Candidate<T> candidate = parser.parse(label, id, source);
            candidates.add(candidate);
        }
        return candidates;
    }

    /** The document to publish for one file: the new one, the one already accepted, or a checkpoint after a restart. */
    private static <T> Optional<T> choose(Candidate<T> candidate, Optional<T> kept, Function<T, Long> revision, Function<T, String> source,
                                          Map<String, RigCheckpoints.Saved> saved, Function<String, Optional<T>> recover, List<String> problems) {
        if (candidate.document().isPresent()) {
            T next = candidate.document().get();
            if (kept.isEmpty() || source.apply(kept.get()).equals(source.apply(next)) || revision.apply(next) > revision.apply(kept.get())) return Optional.of(next);
            problems.add(candidate.path() + " $.revision: the document changed, so raise $.revision above " + revision.apply(kept.get()) + "; last valid revision " + revision.apply(kept.get()) + " kept");
            return kept;
        }
        candidate.problems().stream().limit(8).forEach((String problem) -> problems.add(candidate.path() + " " + problem));
        if (kept.isPresent()) {
            problems.add(candidate.path() + ": last valid revision " + revision.apply(kept.get()) + " kept");
            return kept;
        }
        RigCheckpoints.Saved checkpoint = saved.get(candidate.path());
        if (checkpoint == null) return Optional.empty();
        Optional<T> recovered = recover.apply(checkpoint.source());
        recovered.ifPresent((T document) -> problems.add(candidate.path() + ": last-valid checkpoint revision " + checkpoint.revision() + " kept"));
        return recovered;
    }
}
