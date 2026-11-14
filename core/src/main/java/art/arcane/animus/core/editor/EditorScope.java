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
    /**
     * Changes to documents that run commands or other privileged capabilities, which amounts to console access. Never a
     * default; pairing it needs {@code animus.editor.commands}.
     */
    APPLY_COMMANDS("apply:commands"),
    IMPORT("import"),
    SPAWN("spawn"),
    TERRAIN("terrain");

    /** What /animus editor pair grants unless scopes= narrows it: every scope but {@link #APPLY_COMMANDS}, which is always asked for by name. */
    public static final List<EditorScope> DEFAULT_PAIRING = List.of(READ, VALIDATE, PREVIEW, APPLY_NPCS, APPLY_BEHAVIORS, APPLY_GUI, APPLY_RIGS, IMPORT, SPAWN, TERRAIN);

    private final String wireName;

    EditorScope(String wireName) { this.wireName = wireName; }

    public String wireName() { return wireName; }

    public static Optional<EditorScope> parse(String wireName) {
        for (EditorScope scope : values()) if (scope.wireName.equals(wireName)) return Optional.of(scope);
        return Optional.empty();
    }
}
