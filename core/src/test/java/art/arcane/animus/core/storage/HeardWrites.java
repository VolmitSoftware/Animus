package art.arcane.animus.core.storage;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The write notices Animus announces while this is open, kept per file as {@code written} or {@code touched}. */
public final class HeardWrites implements WriteNotices.Listener, AutoCloseable {
    private record Notice(Path path, String kind) {}

    private final List<Notice> heard = new CopyOnWriteArrayList<>();
    private final AutoCloseable registration;

    public HeardWrites() { registration = WriteNotices.listen(this); }

    @Override public void written(Path path, String text) { heard.add(new Notice(path, "written")); }
    @Override public void touched(Path path) { heard.add(new Notice(path, "touched")); }

    /** The notices for one file, in the order they came. */
    public List<String> of(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        return heard.stream().filter((Notice notice) -> notice.path().equals(absolute)).map(Notice::kind).toList();
    }

    @Override public void close() throws Exception { registration.close(); }
}
