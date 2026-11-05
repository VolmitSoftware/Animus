package art.arcane.animus.core.hotload;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides when a watched file has really changed. A change is reported only once its content reads the same on two
 * polls in a row, so a half-saved file is never applied. Animus's own writes are noted before they land and are never
 * reported. A refused change is remembered and not reported again until the file changes once more; the last applied
 * content stays the base of the next diff. Thread-safe; file reads happen outside the lock.
 */
public final class HotloadEngine {
    /** A file the source will never read as it is, such as one over the size budget. Reported as a refused change. */
    public static final class Refused extends IOException {
        public Refused(String message) { super(message); }
    }

    /** The watched files. Any {@link IOException} other than {@link Refused} is treated as transient and read again next poll. */
    public interface Source {
        /** Every watched path with a cheap signature such as size and modification time. */
        Map<String, String> signatures() throws IOException;
        /** The file's comparable content, or null when it is gone. */
        String read(String path) throws IOException;
    }

    /** One stable change. {@code content} is null when the file is gone; {@code failure} is set when it cannot be read. */
    public record Snapshot(String path, String content, String failure, long revision) {
        public Snapshot { Objects.requireNonNull(path, "path"); }
        public boolean deleted() { return content == null && failure == null; }
    }

    /** What an applied snapshot replaced. A null side is a missing file. */
    public record Delta(String path, String before, String after) {}

    public enum Outcome { APPLIED, REJECTED, RETRY }

    private record Seen(String content, String failure) {
        static final Seen MISSING = new Seen(null, null);
    }

    private record Read(String path, String signature, long revision, Seen seen) {}

    private final Source source;
    private final Map<String, String> signatures = new HashMap<>();
    private final Map<String, Seen> known = new HashMap<>();
    private final Map<String, Seen> pending = new HashMap<>();
    private final Map<String, Seen> rejected = new HashMap<>();
    private final Map<String, Long> revisions = new HashMap<>();

    public HotloadEngine(Source source) { this.source = Objects.requireNonNull(source, "source"); }

    /** Reads every watched file once as the starting point, so files already on disk are not reported. */
    public void prime() throws IOException {
        Map<String, String> now = source.signatures();
        List<Read> reads = new ArrayList<>(now.size());
        for (Map.Entry<String, String> entry : now.entrySet()) {
            long revision;
            synchronized (this) { revision = revision(entry.getKey()); }
            Seen seen = readQuietly(entry.getKey());
            if (seen != null) reads.add(new Read(entry.getKey(), entry.getValue(), revision, seen));
        }
        synchronized (this) {
            for (Read read : reads) {
                if (read.revision() != revision(read.path())) continue;
                signatures.put(read.path(), read.signature());
                known.put(read.path(), read.seen());
            }
        }
    }

    /** Changes that have read the same on two polls, reading at most {@code reads} files this time. */
    public List<Snapshot> poll(int reads) throws IOException {
        Map<String, String> now = source.signatures();
        List<String> candidates = new ArrayList<>();
        Map<String, Long> revisionAtRead = new HashMap<>();
        synchronized (this) {
            Set<String> ordered = new LinkedHashSet<>(new TreeSet<>(pending.keySet()));
            Set<String> paths = new TreeSet<>(now.keySet());
            paths.addAll(signatures.keySet());
            for (String path : paths) if (!Objects.equals(now.get(path), signatures.get(path))) ordered.add(path);
            for (String path : ordered) {
                if (candidates.size() >= Math.max(1, reads)) break;
                candidates.add(path);
                revisionAtRead.put(path, revision(path));
            }
        }
        List<Read> results = new ArrayList<>(candidates.size());
        for (String path : candidates) {
            Seen seen = readQuietly(path);
            if (seen != null) results.add(new Read(path, now.get(path), revisionAtRead.get(path), seen));
        }
        List<Snapshot> stable = new ArrayList<>();
        synchronized (this) {
            for (Read read : results) {
                String path = read.path();
                if (read.revision() != revision(path)) continue;
                if (read.signature() == null) signatures.remove(path);
                else signatures.put(path, read.signature());
                Seen seen = read.seen();
                if (seen.equals(known.getOrDefault(path, Seen.MISSING))) {
                    pending.remove(path);
                    rejected.remove(path);
                    continue;
                }
                if (seen.equals(rejected.get(path))) {
                    pending.remove(path);
                    continue;
                }
                if (seen.equals(pending.get(path))) {
                    pending.remove(path);
                    stable.add(new Snapshot(path, seen.content(), seen.failure(), read.revision()));
                } else pending.put(path, seen);
            }
        }
        return stable;
    }

    /** False once an Animus write to the same file has superseded the snapshot. */
    public synchronized boolean current(Snapshot snapshot) { return snapshot.revision() == revision(snapshot.path()); }

    /**
     * Records the outcome of applying a snapshot. An applied one becomes the new base and returns what it replaced; a
     * refused one is not reported again until the file changes; a retried one is reported again on the next poll.
     */
    public synchronized Optional<Delta> settle(Snapshot snapshot, Outcome outcome) {
        String path = snapshot.path();
        if (!current(snapshot)) return Optional.empty();
        Seen seen = new Seen(snapshot.content(), snapshot.failure());
        return switch (outcome) {
            case APPLIED -> {
                Seen before = known.getOrDefault(path, Seen.MISSING);
                if (seen.equals(Seen.MISSING)) known.remove(path);
                else known.put(path, seen);
                rejected.remove(path);
                yield Optional.of(new Delta(path, before.content(), seen.content()));
            }
            case REJECTED -> {
                rejected.put(path, seen);
                yield Optional.empty();
            }
            case RETRY -> {
                pending.put(path, seen);
                yield Optional.empty();
            }
        };
    }

    /** Animus is writing {@code content} to the file, or deleting it when null. Call before and after the write. */
    public synchronized void noteSelfWrite(String path, String content) {
        Seen seen = content == null ? Seen.MISSING : new Seen(content, null);
        if (seen.equals(Seen.MISSING)) known.remove(path);
        else known.put(path, seen);
        pending.remove(path);
        rejected.remove(path);
        signatures.remove(path);
        revisions.merge(path, 1L, Long::sum);
    }

    /** Animus changed the file in a way only its bytes describe; learn its content from disk. */
    public void noteTouched(String path) {
        long before;
        synchronized (this) { revisions.merge(path, 1L, Long::sum); before = revision(path); }
        Seen seen = readQuietly(path);
        synchronized (this) {
            if (before != revision(path)) return;
            if (seen == null || seen.equals(Seen.MISSING)) known.remove(path);
            else known.put(path, seen);
            pending.remove(path);
            rejected.remove(path);
            signatures.remove(path);
        }
    }

    /** The last applied content of a file, or null when none is known. */
    public synchronized String known(String path) { return known.getOrDefault(path, Seen.MISSING).content(); }

    public synchronized void clear() {
        signatures.clear(); known.clear(); pending.clear(); rejected.clear(); revisions.clear();
    }

    private long revision(String path) { return revisions.getOrDefault(path, 0L); }

    /** Null on a transient failure. */
    private Seen readQuietly(String path) {
        try {
            String content = source.read(path);
            return content == null ? Seen.MISSING : new Seen(content, null);
        } catch (Refused refused) {
            return new Seen(null, refused.getMessage());
        } catch (IOException | RuntimeException transientFailure) {
            return null;
        }
    }
}
