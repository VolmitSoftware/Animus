/// Section-sign formatting (`§a`, `§l`) as styled runs, the way the client's
/// legacy text reads it: a colour code resets every decoration, `§r` resets
/// everything, unknown codes are dropped.
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

List<McTextRun> mcLegacyRuns(String text) {
  final List<McTextRun> runs = <McTextRun>[];
  final StringBuffer buffer = StringBuffer();
  int color = mcDefaultTextColor;
  bool bold = false, italic = false, underlined = false, strikethrough = false, obfuscated = false;
  void flush() {
    if (buffer.isEmpty) return;
    runs.add(McTextRun(
      buffer.toString(),
      color: color,
      bold: bold,
      italic: italic,
      underlined: underlined,
      strikethrough: strikethrough,
      obfuscated: obfuscated,
    ));
    buffer.clear();
  }

  for (int i = 0; i < text.length; i++) {
    final String c = text[i];
    if (c != '§' || i + 1 >= text.length) {
      buffer.write(c);
      continue;
    }
    final String code = text[i + 1].toLowerCase();
    i++;
    flush();
    final int? next = mcLegacyColors[code];
    if (next != null) {
      color = next;
      bold = italic = underlined = strikethrough = obfuscated = false;
    } else {
      switch (code) {
        case 'k':
          obfuscated = true;
        case 'l':
          bold = true;
        case 'm':
          strikethrough = true;
        case 'n':
          underlined = true;
        case 'o':
          italic = true;
        case 'r':
          color = mcDefaultTextColor;
          bold = italic = underlined = strikethrough = obfuscated = false;
      }
    }
  }
  flush();
  return runs;
}
