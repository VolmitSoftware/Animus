package art.arcane.animus.core.text;

/**
 * How wide a line of plain text draws in Minecraft's default font, in GUI pixels, glyph spacing included, and the chat
 * width a client wraps at with the default Chat Width setting (100%).
 */
public final class ChatWidth {
    /** {@code chatWidth = 1.0}: 1.0 * 280 + 40 pixels. */
    public static final int DEFAULT = 320;

    private ChatWidth() {}

    public static int of(String text) {
        int width = 0;
        for (int index = 0; index < text.length(); index++) width += glyph(text.charAt(index));
        return width;
    }

    private static int glyph(char c) {
        if ("!,.:;i|'".indexOf(c) >= 0) return 2;
        if ("`l".indexOf(c) >= 0) return 3;
        if ("It[] ".indexOf(c) >= 0) return 4;
        if ("\"()*<>fk{}".indexOf(c) >= 0) return 5;
        if ("@~".indexOf(c) >= 0) return 7;
        return 6;
    }
}
