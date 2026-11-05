package art.arcane.animus.core.gui;

/** Document ids are {@code namespace:path}. */
public final class GuiIds {
    private GuiIds() {}

    /** An id without a namespace names a document in the referencing document's namespace. */
    public static String relative(String from, String target) {
        if (target.contains(":")) return target;
        int colon = from.indexOf(':');
        return colon < 0 ? target : from.substring(0, colon + 1) + target;
    }

    public static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "" : id.substring(0, colon);
    }
}
