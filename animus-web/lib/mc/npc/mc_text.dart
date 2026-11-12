/// Styled text runs and the client's legacy colours. Text is parsed into
/// runs by `logic/gui/gui_text.dart`, the editor's one formatter.
library;

/// `ChatFormatting` colours by code.
const Map<String, int> mcLegacyColors = <String, int>{
  '0': 0x000000, '1': 0x0000AA, '2': 0x00AA00, '3': 0x00AAAA,
  '4': 0xAA0000, '5': 0xAA00AA, '6': 0xFFAA00, '7': 0xAAAAAA,
  '8': 0x555555, '9': 0x5555FF, 'a': 0x55FF55, 'b': 0x55FFFF,
  'c': 0xFF5555, 'd': 0xFF55FF, 'e': 0xFFFF55, 'f': 0xFFFFFF,
};

const int mcDefaultTextColor = 0xFFFFFF;

final class McTextRun {
  const McTextRun(
    this.text, {
    this.color = mcDefaultTextColor,
    this.bold = false,
    this.italic = false,
    this.underlined = false,
    this.strikethrough = false,
    this.obfuscated = false,
  });

  final String text;
  final int color;
  final bool bold;
  final bool italic;
  final bool underlined;
  final bool strikethrough;
  final bool obfuscated;

  String get cssColor => '#${color.toRadixString(16).padLeft(6, '0')}';

  @override
  bool operator ==(Object other) =>
      other is McTextRun &&
      other.text == text &&
      other.color == color &&
      other.bold == bold &&
      other.italic == italic &&
      other.underlined == underlined &&
      other.strikethrough == strikethrough &&
      other.obfuscated == obfuscated;

  @override
  int get hashCode => Object.hash(text, color, bold, italic, underlined, strikethrough, obfuscated);

  @override
  String toString() => 'McTextRun($text, #${color.toRadixString(16)}${bold ? ' bold' : ''}${italic ? ' italic' : ''})';
}

const String _obfuscatedGlyphs = 'abcdefghkmnopqrsuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ0123456789#%&?@';

/// `§k` text as the client shows one frame of it: every glyph but spaces
/// swapped for another. The client redraws it every frame; the preview keeps
/// one fixed frame so nothing on the stage moves on its own.
String mcObfuscate(String text) {
  final StringBuffer out = StringBuffer();
  for (int i = 0; i < text.length; i++) {
    final int unit = text.codeUnitAt(i);
    out.write(text[i] == ' ' ? ' ' : _obfuscatedGlyphs[(unit * 31 + i * 17) % _obfuscatedGlyphs.length]);
  }
  return out.toString();
}
