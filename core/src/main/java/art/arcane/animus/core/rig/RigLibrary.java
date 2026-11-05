package art.arcane.animus.core.rig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** The published rig catalog of one server. Reloads run on the file lane; readers on any thread see an immutable catalog. */
public final class RigLibrary {
    private final Path data;
    private final RigCheckpoints checkpoints;
    private volatile RigCatalog catalog = RigCatalog.EMPTY;

    public RigLibrary(Path data, RigCheckpoints checkpoints) {
        this.data = Objects.requireNonNull(data);
        this.checkpoints = Objects.requireNonNull(checkpoints);
    }

    public RigCatalog catalog() { return catalog; }
    public Path data() { return data; }

    /** File lane only. A failure keeps every accepted document and reports why. */
    public synchronized RigCatalog reload() {
        RigCatalog before = catalog;
        RigCatalog next;
        try { next = RigCatalog.load(data, before, checkpoints); }
        catch (IOException | RuntimeException failure) {
            next = new RigCatalog(before.generation() + 1, before.rigs(), before.animations(), List.of("rigs: catalog could not be read: " + failure.getMessage()));
        }
        catalog = next;
        return next;
    }

    public String status() {
        RigCatalog current = catalog;
        return "Rigs | rigs=" + current.rigs().size() + " | animations=" + current.animations().size() + " | problems=" + current.problems().size()
                + " | generation=" + current.generation();
    }
}
