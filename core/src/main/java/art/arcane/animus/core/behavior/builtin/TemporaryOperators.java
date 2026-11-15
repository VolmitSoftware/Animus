package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.core.storage.DocumentStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Players made operator for one {@code animus:command} with {@code asOperator}, as Citizens' {@code op} commands do. Each
 * player is written to the list file before they are made an operator and crossed off once they are not, so a quit, a
 * stop or a crash in between is undone: {@link #quit} and {@link #close} take it back at once, and {@link #recover} at the
 * next start takes back everyone the file still names. A player who is already an operator is left as they are.
 */
public final class TemporaryOperators {
    /** The server's operator list. Called on the player's owner, and on the server thread at startup and shutdown. */
    public interface Operators {
        boolean operator(UUID player);
        void operator(UUID player, boolean operator);
    }

    private final Path file;
    private final Operators operators;
    private final Set<UUID> pending = new LinkedHashSet<>();

    public TemporaryOperators(Path file, Operators operators) { this.file = Objects.requireNonNull(file); this.operators = Objects.requireNonNull(operators); }

    /** At startup, before anyone joins: takes operator from everyone the file names and empties it. How many it named. */
    public synchronized int recover() throws IOException {
        Set<UUID> listed = new LinkedHashSet<>(read());
        for (UUID player : listed) operators.operator(player, false);
        pending.clear();
        write();
        return listed.size();
    }

    /** On the player's owner: runs the command with the player an operator, then takes it back whatever happens. */
    public <T> T perform(UUID player, Supplier<T> command) throws IOException {
        if (operators.operator(player)) return command.get();
        synchronized (this) {
            pending.add(player);
            try { write(); }
            catch (IOException unwritten) { pending.remove(player); write(); throw unwritten; }
            operators.operator(player, true);
        }
        try { return command.get(); }
        finally { release(player); }
    }

    /** The player left: if a command of theirs still holds operator, it goes now. */
    public void quit(UUID player) { release(player); }

    /** Animus stops: everyone still made an operator by a command stops being one. */
    public void close() { for (UUID player : List.copyOf(snapshot())) release(player); }

    private synchronized Set<UUID> snapshot() { return Set.copyOf(pending); }

    /** A player the server would not take operator from here, such as from the wrong Folia thread, stays listed for the next start. */
    private synchronized void release(UUID player) {
        if (!pending.contains(player)) return;
        try { operators.operator(player, false); }
        catch (RuntimeException refused) {
            System.getLogger("Animus").log(System.Logger.Level.WARNING, "Could not take operator back from " + player + " now; the next start will: " + refused.getMessage());
            return;
        }
        pending.remove(player);
        try { write(); }
        catch (IOException unwritten) { System.getLogger("Animus").log(System.Logger.Level.WARNING, "Could not update " + file + ": " + unwritten.getMessage()); }
    }

    private List<UUID> read() throws IOException {
        if (!Files.exists(file)) return List.of();
        JsonElement parsed;
        try { parsed = JsonParser.parseString(Files.readString(file)); }
        catch (JsonParseException unreadable) { throw new IOException(file + " is not a list of player UUIDs", unreadable); }
        if (!(parsed instanceof JsonArray players)) throw new IOException(file + " is not a list of player UUIDs");
        Set<UUID> listed = new LinkedHashSet<>();
        for (JsonElement player : players) {
            try { listed.add(UUID.fromString(player.getAsString())); }
            catch (IllegalArgumentException | UnsupportedOperationException | IllegalStateException malformed) { throw new IOException(file + " lists " + player + ", which is not a player UUID"); }
        }
        return List.copyOf(listed);
    }

    private void write() throws IOException {
        JsonArray players = new JsonArray();
        for (UUID player : pending) players.add(player.toString());
        Files.createDirectories(file.getParent());
        DocumentStore.atomic(file, players.toString());
    }
}
