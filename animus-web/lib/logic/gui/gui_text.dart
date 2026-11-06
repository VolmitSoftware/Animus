/// GUI text as styled runs, read exactly as the server's `RichText` reads it
/// (wiki animus/11-guis-holograms-menus): `&` and section-sign codes,
/// `&#RRGGBB`, `&x&R&R&G&G&B&B`, Gloss's `[RRGGBB]`, backslash-u escapes and
/// the common MiniMessage colour and decoration tags. Tags become codes, so a
/// colour tag clears the decorations before it and a closing tag resets, then
/// reopens the tags still open, as in game. Holes are filled with sample
/// values first. Unknown tags and codes stay as text.
library;

import '../../mc/npc/mc_text.dart';
import 'gui_expression.dart';

const String _section = '§';
const String _codes = '0123456789abcdefklmnorABCDEFKLMNOR';
const String _colours = '0123456789abcdef';
const String _decorations = 'klmnor';

const Map<String, String> _tags = <String, String>{
  'black': '0', 'dark_blue': '1', 'dark_green': '2', 'dark_aqua': '3', 'dark_red': '4', 'dark_purple': '5', 'gold': '6',
  'gray': '7', 'grey': '7', 'dark_gray': '8', 'dark_grey': '8', 'blue': '9', 'green': 'a', 'aqua': 'b', 'red': 'c',
  'light_purple': 'd', 'yellow': 'e', 'white': 'f', 'obfuscated': 'k', 'bold': 'l', 'b': 'l', 'strikethrough': 'm',
  'st': 'm', 'underlined': 'n', 'u': 'n', 'italic': 'o', 'i': 'o', 'em': 'o', 'reset': 'r',
};

/// Runs for GUI text, holes rendered with sample values.
List<McTextRun> guiTextRuns(String text, {int defaultColor = mcDefaultTextColor, bool sample = true}) =>
    guiSectionRuns(guiColorize(sample ? renderTemplate(text) : text), defaultColor: defaultColor);

/// The text without codes or tags, for labels and tooltips' accessible names.
String guiPlainText(String text) => guiTextRuns(text).map((McTextRun run) => run.text).join();

/// [runs] split into lines at each newline, styles carried over.
List<List<McTextRun>> guiTextLines(List<McTextRun> runs) {
  final List<List<McTextRun>> lines = <List<McTextRun>>[<McTextRun>[]];
  for (final McTextRun run in runs) {
    final List<String> parts = run.text.split('\n');
    for (int index = 0; index < parts.length; index++) {
      if (index > 0) lines.add(<McTextRun>[]);
      if (parts[index].isEmpty) continue;
      lines.last.add(McTextRun(
        parts[index],
        color: run.color,
        bold: run.bold,
        italic: run.italic,
        underlined: run.underlined,
        strikethrough: run.strikethrough,
        obfuscated: run.obfuscated,
      ));
    }
  }
  return lines;
}

/// Source text as section-coded text, as the server's `RichText.colorize`.
String guiColorize(String text) {
  if (text.isEmpty) return '';
  final String source = _unescape(text);
  final StringBuffer out = StringBuffer();
  final List<String> open = <String>[];
  int index = 0;
  while (index < source.length) {
    final String glyph = source[index];
    if (glyph == '&' && index + 1 < source.length && source[index + 1] == '#' && _hex(source, index + 2, 6)) {
      out.write(_hexCode(source.substring(index + 2, index + 8)));
      index += 8;
      continue;
    }
    if (glyph == '[' && index + 7 < source.length && source[index + 7] == ']' && _hex(source, index + 1, 6)) {
      out.write(_hexCode(source.substring(index + 1, index + 7)));
      index += 8;
      continue;
    }
    if ((glyph == '&' || glyph == _section) && _repeatedHex(source, index, glyph)) {
      out.write(_hexCode(<String>[for (int digit = 0; digit < 6; digit++) source[index + 3 + digit * 2]].join()));
      index += 14;
      continue;
    }
    if (glyph == '&' && index + 1 < source.length && _codes.contains(source[index + 1])) {
      out.write('$_section${source[index + 1].toLowerCase()}');
      index += 2;
      continue;
    }
    if (glyph == '<') {
      final int end = source.indexOf('>', index);
      if (end > index + 1) {
        final String? code = _tag(source.substring(index + 1, end).toLowerCase(), open);
        if (code != null) {
          out.write(code);
          index = end + 1;
          continue;
        }
      }
    }
    out.write(glyph);
    index++;
  }
  return out.toString();
}

/// Section-coded text as styled runs, as the server's `RichText.sectionRuns`:
/// a colour code clears the styles before it, as in vanilla.
List<McTextRun> guiSectionRuns(String coloured, {int defaultColor = mcDefaultTextColor}) {
  final List<McTextRun> runs = <McTextRun>[];
  final StringBuffer current = StringBuffer();
  int? rgb;
  bool bold = false, italic = false, underlined = false, strikethrough = false, obfuscated = false;
  int index = 0;
  while (index < coloured.length) {
    final String glyph = coloured[index];
    if (glyph != _section || index + 1 >= coloured.length) {
      current.write(glyph);
      index++;
      continue;
    }
    final String code = coloured[index + 1].toLowerCase();
    int consumed = 2;
    int? colour;
    if (code == 'x' && _repeatedHex(coloured, index, _section)) {
      colour = int.parse(<String>[for (int digit = 0; digit < 6; digit++) coloured[index + 3 + digit * 2]].join(), radix: 16);
      consumed = 14;
    } else if (_colours.contains(code)) {
      colour = mcLegacyColors[code];
    } else if (!_decorations.contains(code)) {
      current.write(glyph);
      index++;
      continue;
    }
    if (current.isNotEmpty) {
      runs.add(McTextRun(
        current.toString(),
        color: rgb ?? defaultColor,
        bold: bold,
        italic: italic,
        underlined: underlined,
        strikethrough: strikethrough,
        obfuscated: obfuscated,
      ));
      current.clear();
    }
    if (colour != null || code == 'r') {
      rgb = colour;
      bold = italic = underlined = strikethrough = obfuscated = false;
    }
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
    }
    index += consumed;
  }
  if (current.isNotEmpty) {
    runs.add(McTextRun(
      current.toString(),
      color: rgb ?? defaultColor,
      bold: bold,
      italic: italic,
      underlined: underlined,
      strikethrough: strikethrough,
      obfuscated: obfuscated,
    ));
  }
  return runs;
}

/// A tag as codes; [open] holds the open tags, most recent first.
String? _tag(String tag, List<String> open) {
  if (tag.startsWith('/')) {
    final String name = tag.substring(1);
    if (!_tags.containsKey(name) && !name.startsWith('#') && !name.startsWith('color') && !name.startsWith('colour')) return null;
    open.remove(name);
    final StringBuffer rebuilt = StringBuffer('${_section}r');
    for (final String still in open.reversed) {
      rebuilt.write(_tag(still, <String>[]) ?? '');
    }
    return rebuilt.toString();
  }
  if (tag.startsWith('#') && tag.length == 7 && _hex(tag, 1, 6)) {
    open.insert(0, tag);
    return _hexCode(tag.substring(1));
  }
  if (tag.startsWith('color:')) return _tag(tag.substring(6), open);
  if (tag.startsWith('colour:')) return _tag(tag.substring(7), open);
  final String? code = _tags[tag];
  if (code == null) return null;
  if (code == 'r') {
    open.clear();
  } else {
    open.insert(0, tag);
  }
  return '$_section$code';
}

String _unescape(String text) {
  if (!text.contains(r'\')) return text;
  final StringBuffer out = StringBuffer();
  int index = 0;
  while (index < text.length) {
    if (text[index] == r'\' && index + 1 < text.length && text[index + 1].toLowerCase() == 'u' && _hex(text, index + 2, 4)) {
      out.writeCharCode(int.parse(text.substring(index + 2, index + 6), radix: 16));
      index += 6;
      continue;
    }
    out.write(text[index]);
    index++;
  }
  return out.toString();
}

bool _repeatedHex(String text, int from, String marker) {
  if (from + 14 > text.length || text[from + 1].toLowerCase() != 'x') return false;
  for (int digit = 0; digit < 6; digit++) {
    final int at = from + 2 + digit * 2;
    if (text[at] != marker || !_hex(text, at + 1, 1)) return false;
  }
  return true;
}

final RegExp _hexDigit = RegExp('[0-9A-Fa-f]');

bool _hex(String text, int from, int count) {
  if (from + count > text.length) return false;
  for (int index = from; index < from + count; index++) {
    if (!_hexDigit.hasMatch(text[index])) return false;
  }
  return true;
}

String _hexCode(String digits) => '${_section}x${digits.toLowerCase().split('').map((String digit) => '$_section$digit').join()}';
