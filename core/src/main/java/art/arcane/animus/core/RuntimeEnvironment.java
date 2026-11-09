package art.arcane.animus.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The platform Animus runs on; {@code server} is the name status replies show, such as {@code Paper} on a Bukkit platform. */
public record RuntimeEnvironment(Platform platform, String minecraftVersion, Path dataDirectory, String server) {
    private static final Pattern BUKKIT_VERSION = Pattern.compile("^[0-9]+(?:\\.[0-9]+)*");

    public RuntimeEnvironment {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(server, "server");
        if (minecraftVersion.isBlank()) {
            throw new IllegalArgumentException("Minecraft version cannot be blank");
        }
    }

    public RuntimeEnvironment(Platform platform, String minecraftVersion, Path dataDirectory) {
        this(platform, minecraftVersion, dataDirectory, switch (platform) {
            case BUKKIT -> "Bukkit";
            case FABRIC -> "Fabric";
            case FORGE -> "Forge";
            case NEOFORGE -> "NeoForge";
        });
    }

    /**
     * The data folder as its real path, created when missing. A data folder that is a symbolic link, as some hosts mount
     * {@code plugins/Animus} from another volume, is resolved once here; the readers below it still refuse links inside it.
     */
    public static Path resolveDataDirectory(Path folder) throws IOException {
        try {
            Files.createDirectories(folder);
            return folder.toRealPath();
        } catch (IOException failure) {
            throw new IOException("Cannot open the data folder " + folder + ": " + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()), failure);
        }
    }

    public static RuntimeEnvironment bukkit(String bukkitVersion, Path dataDirectory) {
        return bukkit(bukkitVersion, dataDirectory, "Bukkit");
    }

    /** {@code server} names the Bukkit server, such as {@code Paper}, {@code Folia} or {@code Spigot}. */
    public static RuntimeEnvironment bukkit(String bukkitVersion, Path dataDirectory, String server) {
        Matcher matcher = BUKKIT_VERSION.matcher(Objects.requireNonNull(bukkitVersion, "bukkitVersion"));
        if (!matcher.find()) {
            throw new IllegalArgumentException("Bukkit version has no Minecraft release: " + bukkitVersion);
        }
        return new RuntimeEnvironment(Platform.BUKKIT, matcher.group(), dataDirectory, server);
    }
}
