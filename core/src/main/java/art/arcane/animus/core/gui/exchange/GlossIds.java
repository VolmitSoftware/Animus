package art.arcane.animus.core.gui.exchange;

import art.arcane.animus.core.gui.GuiKind;
import art.arcane.animus.core.shop.ShopDocument;
import java.util.Locale;
import java.util.Optional;

/**
 * Maps ids between Gloss and Animus. Gloss ids are case-preserving segments of {@code [A-Za-z0-9][A-Za-z0-9._-]*};
 * menus may nest with {@code /}, the other adopted kinds are flat. Animus ids are {@code namespace:path} in lowercase.
 */
public final class GlossIds {
    public static final String DEFAULT_NAMESPACE = "gloss";
    private static final String SEGMENT = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";

    private GlossIds() {}

    /** Gloss id to Animus id: lowercased, with every character outside {@code [a-z0-9_/-]} as {@code -}. */
    public static Optional<String> toAnimus(String glossId, String namespace) {
        String path = glossId.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_/-]", "-");
        String id = namespace + ":" + path;
        return !path.isEmpty() && id.matches(ShopDocument.ID) ? Optional.of(id) : Optional.empty();
    }

    /** Animus id to Gloss id: {@code path} in the gloss namespace, otherwise {@code namespace.path}; flat kinds turn {@code /} into {@code .}. */
    public static Optional<String> toGloss(GuiKind kind, String animusId) {
        int colon = animusId.indexOf(':');
        if (colon < 1) return Optional.empty();
        String namespace = animusId.substring(0, colon), path = animusId.substring(colon + 1);
        String id = namespace.equals(DEFAULT_NAMESPACE) ? path : namespace + "." + path;
        if (kind != GuiKind.MENU) id = id.replace('/', '.');
        return valid(kind, id) ? Optional.of(id) : Optional.empty();
    }

    /** Whether Gloss accepts this id for the kind. */
    public static boolean valid(GuiKind kind, String glossId) {
        if (glossId.isEmpty() || glossId.length() > 255) return false;
        if (kind != GuiKind.MENU) return glossId.matches(SEGMENT);
        for (String segment : glossId.split("/", -1)) if (!segment.matches(SEGMENT)) return false;
        return true;
    }
}
