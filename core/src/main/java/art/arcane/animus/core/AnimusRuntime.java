package art.arcane.animus.core;

import art.arcane.animus.core.config.PluginSettings;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.npc.NpcRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

public final class AnimusRuntime implements AutoCloseable {
    private final RuntimeEnvironment environment;
    private volatile PluginSettings settings;
    private DocumentStore documents;
    private NpcRepository npcs;

    public AnimusRuntime(RuntimeEnvironment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    public synchronized void start() throws IOException {
        if (settings != null) {
            throw new IllegalStateException(BuildIdentity.NAME + " is already running");
        }
        if (!BuildIdentity.MINECRAFT_VERSIONS.contains(environment.minecraftVersion())) {
            throw new IOException("Unsupported Minecraft version: " + environment.minecraftVersion());
        }

        DocumentStore opened = new DocumentStore(environment.dataDirectory());
        try {
            PluginSettings loaded = PluginSettings.load(opened);
            NpcRepository repository = new NpcRepository(opened);
            var errors = repository.reload();
            if (!errors.isEmpty()) throw new IOException(String.join("; ", errors));
            documents = opened;
            npcs = repository;
            settings = loaded;
        } catch (IOException | RuntimeException e) { opened.close(); throw e; }
    }

    @Override
    public synchronized void close() {
        settings = null;
        npcs = null;
        DocumentStore closing = documents;
        documents = null;
        if (closing != null) try { closing.close(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public String status() {
        PluginSettings activeSettings = settings;
        if (activeSettings == null) {
            throw new IllegalStateException(BuildIdentity.NAME + " is not running");
        }
        return BuildIdentity.NAME + " " + BuildIdentity.VERSION + " | " + environment.platform()
                + " | Minecraft " + environment.minecraftVersion() + " | " + activeSettings.statusMessage();
    }

    public synchronized NpcRepository npcs() {
        if (!isRunning()) throw new IllegalStateException("Animus is not running");
        return npcs;
    }

    public synchronized DocumentStore documents() {
        if (!isRunning()) throw new IllegalStateException("Animus is not running");
        return documents;
    }

    public boolean isRunning() {
        return settings != null;
    }

}
