package art.arcane.animus.core.editor;

import java.util.List;
import java.util.Optional;

/** Editor token scopes, in the order of schema/editor-v1.schema.json's scope enum. */
public enum EditorScope {
    READ("read"),
    VALIDATE("validate"),
    PREVIEW("preview"),
    APPLY_NPCS("apply:npcs"),
    APPLY_BEHAVIORS("apply:behaviors"),
    APPLY_GUI("apply:gui"),
    APPLY_RIGS("apply:rigs"),
    IMPORT("import"),
    SPAWN("spawn"),
    TERRAIN("terrain"),
    ADMIN("admin");

    /** What /animus web pair grants unless scopes= says otherwise. */
    public static final List<EditorScope> DEFAULT_PAIRING = List.of(READ, VALIDATE, PREVIEW, APPLY_NPCS, APPLY_BEHAVIORS, APPLY_GUI, APPLY_RIGS, TERRAIN);

    private final String wireName;

    EditorScope(String wireName) { this.wireName = wireName; }

    public String wireName() { return wireName; }

    public static Optional<EditorScope> parse(String wireName) {
        for (EditorScope scope : values()) if (scope.wireName.equals(wireName)) return Optional.of(scope);
        return Optional.empty();
    }
}
