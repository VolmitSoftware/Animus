package art.arcane.animus.core.building.schematic;

/** A schematic or NBT file that cannot be read, with a plain reason. */
public final class SchematicException extends Exception {
    public SchematicException(String message) {
        super(message);
    }

    public SchematicException(String message, Throwable cause) {
        super(message, cause);
    }
}
