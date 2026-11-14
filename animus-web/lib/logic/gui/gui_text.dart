/// Player-facing text as styled runs, read exactly as the server's `RichText`
/// reads it (wiki animus/11-guis-holograms-menus): `&` and section-sign codes,
/// `&#RRGGBB`, `&x&R&R&G&G&B&B`, Gloss's `[RRGGBB]`, backslash-u escapes, the
/// common MiniMessage colour and decoration tags and `<gradient>`. Tags become
/// codes, so a colour tag clears the decorations before it and a closing tag
/// resets, then reopens the tags still open, as in game. GUI holes are filled
/// with sample values first. Unknown tags and codes stay as text. GUIs, NPC
/// nameplates, holograms, speech and text displays on the stage all read text
/// here, and lists show [guiPlainText]. Text displays draw their rows from
/// [guiDisplayRows].
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
List<McTextRun> guiTextRuns(String text, {int defaultColor = mcDefaultTextColor, bool sample = true, bool item = false}) =>
    guiSectionRuns(guiColorize(sample ? renderTemplate(text) : text), defaultColor: defaultColor, item: item);

/// The text without codes or tags, for labels and tooltips' accessible names.
String guiPlainText(String text) => guiTextRuns(text).map((McTextRun run) => run.text).join();

/// True when [text] carries any colour or format code once colorized, as the
/// server's `RichText.formatted`.
bool guiFormatted(String text) {
  final String coloured = guiColorize(text);
  for (int index = 0; index + 1 < coloured.length; index++) {
    if (coloured[index] == _section && (_codes.contains(coloured[index + 1]) || coloured[index + 1] == 'x')) return true;
  }
  return false;
}

/// Each of [lines] as the rows the client draws for it, one more for every
/// newline it holds, read as vanilla reads legacy codes: a colour code
/// clears the formats, a format adds to the style, `&r` resets it. In one
/// text display the lines are joined with newlines, so a style carries on
/// into the rows below; with [separate] each line is its own display and
/// starts unstyled. Each line is colorized alone, then the joined text again,
/// as the server builds a display's text and the platform reads it.
List<List<List<McTextRun>>> guiDisplayRows(List<String> lines, {bool separate = false, bool sample = true, int defaultColor = mcDefaultTextColor}) {
  final List<String> coloured = <String>[for (final String line in lines) guiColorize(sample ? renderTemplate(line) : line)];
  if (separate) {
    return <List<List<McTextRun>>>[for (final String line in coloured) _rows(guiSectionRuns(guiColorize(line), defaultColor: defaultColor))];
  }
  final List<List<McTextRun>> rows = _rows(guiSectionRuns(guiColorize(coloured.join('\n')), defaultColor: defaultColor));
  final List<List<List<McTextRun>>> out = <List<List<McTextRun>>>[];
  int at = 0;
  for (final String line in coloured) {
    final int count = '\n'.allMatches(line).length + 1;
    out.add(rows.sublist(at, at + count));
    at += count;
  }
  return out;
}

/// [runs] split into rows at each newline, styles carried over.
List<List<McTextRun>> _rows(List<McTextRun> runs) {
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
      final List<int>? gradient = end > index + 1 ? _gradientColours(source.substring(index + 1, end).toLowerCase()) : null;
      if (gradient != null) {
        final int close = source.toLowerCase().indexOf('</gradient>', end + 1);
        out.write(_gradient(source.substring(end + 1, close < 0 ? source.length : close), gradient));
        if (close < 0) break;
        out.write(_reopen(open));
        index = close + '</gradient>'.length;
        continue;
      }
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
/// a colour code clears the styles before it, as in vanilla. An [item] name
/// or lore line is italic until its first colour or reset code, as the
/// client draws a custom name set from legacy text.
List<McTextRun> guiSectionRuns(String coloured, {int defaultColor = mcDefaultTextColor, bool item = false}) {
  final List<McTextRun> runs = <McTextRun>[];
  final StringBuffer current = StringBuffer();
  int? rgb;
  bool bold = false, italic = item, underlined = false, strikethrough = false, obfuscated = false;
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
    return _reopen(open);
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

/// A reset, then the tags in [open] again.
String _reopen(List<String> open) {
  final StringBuffer rebuilt = StringBuffer('${_section}r');
  for (final String still in open.reversed) {
    rebuilt.write(_tag(still, <String>[]) ?? '');
  }
  return rebuilt.toString();
}

/// The colours of a `gradient` tag: white to black without any, null when
/// [tag] is no gradient or names one colour or an unknown one. A phase
/// number is accepted and ignored.
List<int>? _gradientColours(String tag) {
  if (tag != 'gradient' && !tag.startsWith('gradient:')) return null;
  final List<int> colours = <int>[];
  for (final String argument in tag.split(':').skip(1)) {
    if (argument.startsWith('#') && argument.length == 7 && _hex(argument, 1, 6)) {
      colours.add(int.parse(argument.substring(1), radix: 16));
    } else if (_tags[argument] case final String code when _colours.contains(code)) {
      colours.add(mcLegacyColors[code]!);
    } else if (double.tryParse(argument) == null) {
      return null;
    }
  }
  if (colours.isEmpty) return const <int>[0xFFFFFF, 0x000000];
  return colours.length < 2 ? null : colours;
}

/// [inner] coloured letter by letter from the first of [colours] to the
/// last, as MiniMessage spreads a gradient; decorations inside stay on each
/// letter and colours inside give way to the gradient.
String _gradient(String inner, List<int> colours) {
  final String coloured = guiColorize(inner);
  final List<(String, String)> letters = <(String, String)>[];
  String decorations = '';
  int index = 0;
  while (index < coloured.length) {
    final String glyph = coloured[index];
    if (glyph == _section && index + 1 < coloured.length) {
      final String code = coloured[index + 1];
      if (code == 'x' && _repeatedHex(coloured, index, _section)) {
        decorations = '';
        index += 14;
        continue;
      }
      if (_colours.contains(code) || code == 'r') {
        decorations = '';
      } else if (_decorations.contains(code)) {
        if (!decorations.contains(code)) decorations += code;
      } else {
        letters.add((glyph, decorations));
        index++;
        continue;
      }
      index += 2;
      continue;
    }
    letters.add((glyph, decorations));
    index++;
  }
  final StringBuffer out = StringBuffer();
  for (int at = 0; at < letters.length; at++) {
    final double t = letters.length == 1 ? 0 : at / (letters.length - 1);
    out
      ..write(_hexCode(_lerpColour(colours, t).toRadixString(16).padLeft(6, '0')))
      ..write(letters[at].$2.split('').map((String code) => '$_section$code').join())
      ..write(letters[at].$1);
  }
  return out.toString();
}

int _lerpColour(List<int> colours, double t) {
  final double scaled = t * (colours.length - 1);
  final int segment = scaled.floor().clamp(0, colours.length - 2);
  final double local = scaled - segment;
  final int from = colours[segment], to = colours[segment + 1];
  int channel(int shift) {
    final int a = (from >> shift) & 0xFF, b = (to >> shift) & 0xFF;
    return (a + (b - a) * local).round() & 0xFF;
  }
  return channel(16) << 16 | channel(8) << 8 | channel(0);
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
