package art.arcane.animus.core.migration;

import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.UUID;

/**
 * The transaction record of a drop-in import. Every transaction id is written to the state file before it commits, so
 * whatever stops the flow after a commit, the id is listed: rollback finds it and backup rotation keeps its receipt.
 * An undo records its inverse id the same way, so an undo cut off after its commit is not run twice.
 */
final class ImportJournal {
    static final String ID = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    private static final String TRANSACTIONS = "transactions", UNDOING = "undoing";

    /** One commit under the given transaction id. It returns that id, the id an earlier run of the same step committed, or null when it had nothing to commit. */
    @FunctionalInterface interface Step { String commit(String transaction) throws IOException; }

    private final DocumentStore store;
    private final String path;

    ImportJournal(DocumentStore store, String path) { this.store = store; this.path = path; }

    /** Lists a new id, writes the state, then runs the step under it. An id whose commit never landed is taken out again; null when the step committed nothing. */
    String commit(JsonObject state, Step step) throws IOException {
        String reserved = UUID.randomUUID().toString();
        JsonArray transactions = transactions(state);
        transactions.add(reserved);
        write(state);
        String result;
        try { result = step.commit(reserved); }
        catch (IOException | RuntimeException failure) {
            if (!committed(reserved)) release(state, reserved, failure);
            throw failure;
        }
        if (result == null) {
            if (committed(reserved)) return reserved;
            remove(transactions, reserved);
            write(state);
            return null;
        }
        if (result.equals(reserved)) return result;
        if (!committed(reserved)) remove(transactions, reserved);
        if (!result.matches(ID) || !committed(result)) {
            write(state);
            throw new IOException("The step returned " + result + ", not a committed transaction; run preview again");
        }
        if (!listed(transactions, result)) transactions.add(result);
        write(state);
        return result;
    }

    /**
     * Undoes one listed transaction and takes it off the list. The inverse id is recorded first; when it already
     * committed, only the list changes. A conflict leaves the transaction listed.
     */
    String undo(JsonObject state, String transaction) throws IOException {
        JsonObject undoing = state.has(UNDOING) && state.get(UNDOING).isJsonObject() ? state.getAsJsonObject(UNDOING) : new JsonObject();
        String inverse = undoing.has(transaction) ? undoing.get(transaction).getAsString() : null;
        if (inverse == null || !committed(inverse)) {
            if (inverse == null) {
                inverse = UUID.randomUUID().toString();
                undoing.addProperty(transaction, inverse);
                state.add(UNDOING, undoing);
                write(state);
            }
            try { store.commit(inverse, store.inverse(transaction)); }
            catch (IOException | RuntimeException failure) {
                if (!committed(inverse)) {
                    undoing.remove(transaction);
                    if (undoing.isEmpty()) state.remove(UNDOING);
                    release(state, null, failure);
                }
                throw failure;
            }
        }
        remove(transactions(state), transaction);
        undoing.remove(transaction);
        if (undoing.isEmpty()) state.remove(UNDOING);
        write(state);
        return inverse;
    }

    /** True when the transaction committed: its rollback receipt exists. A listed id without one never landed. */
    boolean committed(String transaction) throws IOException {
        return transaction.matches(ID) && Files.isRegularFile(store.resolve("backups/" + transaction + ".json"), LinkOption.NOFOLLOW_LINKS);
    }

    void write(JsonObject state) throws IOException { DocumentStore.atomic(store.resolve(path), JsonDocuments.write(state)); }

    static JsonArray transactions(JsonObject state) {
        if (!state.has(TRANSACTIONS) || !state.get(TRANSACTIONS).isJsonArray()) state.add(TRANSACTIONS, new JsonArray());
        return state.getAsJsonArray(TRANSACTIONS);
    }

    private void release(JsonObject state, String reserved, Throwable failure) {
        if (reserved != null) remove(transactions(state), reserved);
        try { write(state); }
        catch (IOException | RuntimeException unwritten) { failure.addSuppressed(unwritten); }
    }

    private static boolean listed(JsonArray transactions, String transaction) {
        for (JsonElement value : transactions) if (value.getAsString().equals(transaction)) return true;
        return false;
    }

    private static void remove(JsonArray transactions, String transaction) {
        for (int index = transactions.size() - 1; index >= 0; index--) if (transactions.get(index).getAsString().equals(transaction)) transactions.remove(index);
    }
}
