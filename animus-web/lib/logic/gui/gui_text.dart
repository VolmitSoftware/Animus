/// GUI text as styled runs: `&` and section-sign codes, `&#RRGGBB`, and the
/// common MiniMessage colour and style tags (docs/gui.md). Holes are filled
/// with sample values first. Unknown tags stay as text, as MiniMessage
/// leaves them.
library;

import '../../mc/npc/mc_text.dart';
import 'gui_expression.dart';

const Map<String, int> _namedColors = <String, int>{
  'black': 0x000000, 'dark_blue': 0x0000AA, 'dark_green': 0x00AA00, 'dark_aqua': 0x00AAAA,
  'dark_red': 0xAA0000, 'dark_purple': 0xAA00AA, 'gold': 0xFFAA00, 'gray': 0xAAAAAA, 'grey': 0xAAAAAA,
  'dark_gray': 0x555555, 'dark_grey': 0x555555, 'blue': 0x5555FF, 'green': 0x55FF55, 'aqua': 0x55FFFF,
  'red': 0xFF5555, 'light_purple': 0xFF55FF, 'yellow': 0xFFFF55, 'white': 0xFFFFFF,
};

final RegExp _token = RegExp(r'[&§]#([0-9A-Fa-f]{6})|[&§]([0-9A-Fa-fK-Ok-oRr])|<(/?)([#A-Za-z_][A-Za-z0-9_#]*)>');

/// Runs for one line of GUI text, holes rendered with sample values.
List<McTextRun> guiTextRuns(String text, {int defaultColor = mcDefaultTextColor, bool sample = true}) {
  final String source = sample ? renderTemplate(text) : text;
  final List<McTextRun> runs = <McTextRun>[];
  final List<int> colors = <int>[defaultColor];
  bool bold = false, italic = false, underlined = false, strikethrough = false, obfuscated = false;
  void emit(String part) {
    if (part.isEmpty) return;
    runs.add(McTextRun(
      part,
      color: colors.last,
      bold: bold,
      italic: italic,
      underlined: underlined,
      strikethrough: strikethrough,
      obfuscated: obfuscated,
    ));
  }

  void resetDecorations() => bold = italic = underlined = strikethrough = obfuscated = false;

  int at = 0;
  for (final RegExpMatch match in _token.allMatches(source)) {
    final String? hex = match.group(1);
    final String? code = match.group(2)?.toLowerCase();
    final bool closing = match.group(3) == '/';
    final String? tag = match.group(4)?.toLowerCase();
    if (tag != null && !_knownTag(tag)) continue;
    emit(source.substring(at, match.start));
    at = match.end;
    if (hex != null) {
      colors
        ..clear()
        ..add(int.parse(hex, radix: 16));
      resetDecorations();
    } else if (code != null) {
      final int? legacy = mcLegacyColors[code];
      if (legacy != null) {
        colors
          ..clear()
          ..add(legacy);
        resetDecorations();
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
            colors
              ..clear()
              ..add(defaultColor);
            resetDecorations();
        }
      }
    } else if (tag != null) {
      final int? color = tag.startsWith('#') ? int.tryParse(tag.substring(1), radix: 16) : _namedColors[tag];
      if (color != null) {
        if (closing) {
          if (colors.length > 1) colors.removeLast();
        } else {
          colors.add(color);
        }
        continue;
      }
      final bool on = !closing;
      switch (tag) {
        case 'bold' || 'b':
          bold = on;
        case 'italic' || 'i' || 'em':
          italic = on;
        case 'underlined' || 'u':
          underlined = on;
        case 'strikethrough' || 'st':
          strikethrough = on;
        case 'obfuscated' || 'obf':
          obfuscated = on;
        case 'reset':
          colors
            ..clear()
            ..add(defaultColor);
          resetDecorations();
      }
    }
  }
  emit(source.substring(at));
  return runs;
}

bool _knownTag(String tag) {
  if (tag.startsWith('#')) return RegExp(r'^#[0-9a-f]{6}$').hasMatch(tag);
  return _namedColors.containsKey(tag) ||
      const <String>{'bold', 'b', 'italic', 'i', 'em', 'underlined', 'u', 'strikethrough', 'st', 'obfuscated', 'obf', 'reset'}.contains(tag);
}

/// The text without codes or tags, for labels and tooltips' accessible names.
String guiPlainText(String text) => guiTextRuns(text).map((McTextRun run) => run.text).join();
