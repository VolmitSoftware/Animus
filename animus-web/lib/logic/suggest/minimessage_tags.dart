/// The MiniMessage tags the server's text formatter (`RichText`) applies:
/// the sixteen named colours, `#RRGGBB`, `color:` and `colour:`, the five
/// decorations with their short aliases, and `reset`. Any other tag is
/// shown as written.
library;

import 'completion_item.dart';

/// The sixteen named colours and their RGB values.
const Map<String, String> miniMessageColors = <String, String>{
  'black': '#000000',
  'dark_blue': '#0000AA',
  'dark_green': '#00AA00',
  'dark_aqua': '#00AAAA',
  'dark_red': '#AA0000',
  'dark_purple': '#AA00AA',
  'gold': '#FFAA00',
  'gray': '#AAAAAA',
  'dark_gray': '#555555',
  'blue': '#5555FF',
  'green': '#55FF55',
  'aqua': '#55FFFF',
  'red': '#FF5555',
  'light_purple': '#FF55FF',
  'yellow': '#FFFF55',
  'white': '#FFFFFF',
};

const Map<String, String> _decorations = <String, String>{
  'bold': 'bold text',
  'italic': 'italic text',
  'underlined': 'underlined text',
  'strikethrough': 'struck-through text',
  'obfuscated': 'scrambled text',
};

const Set<String> _aliases = <String>{'grey', 'dark_grey', 'b', 'st', 'u', 'i', 'em'};

/// Tags offered after `<`: colours as `<red>`, decorations wrapped around
/// the caret, `reset`, `color:` and a hex colour.
List<CompletionItem> miniMessageTagItems() => <CompletionItem>[
      for (final MapEntry<String, String> color in miniMessageColors.entries)
        CompletionItem(label: color.key, insert: '${color.key}>', kind: CompletionKind.color, type: color.value, detail: 'Named color', swatch: color.value),
      for (final MapEntry<String, String> decoration in _decorations.entries)
        CompletionItem(label: decoration.key, insert: '${decoration.key}>\$0</${decoration.key}>', kind: CompletionKind.tag, detail: decoration.value),
      const CompletionItem(label: 'reset', insert: 'reset>', kind: CompletionKind.tag, detail: 'Clears color and style'),
      const CompletionItem(label: 'color', insert: r'color:${1:red}>$0</color>', kind: CompletionKind.tag, detail: 'Color by name or #hex'),
      const CompletionItem(label: '#RRGGBB', insert: r'#${1:FF5FA2}>', kind: CompletionKind.color, detail: 'Hex color', swatch: '#FF5FA2'),
    ];

/// Colour values after `<color:` or `<colour:`.
List<CompletionItem> miniMessageColorItems() => <CompletionItem>[
      for (final MapEntry<String, String> color in miniMessageColors.entries)
        CompletionItem(label: color.key, insert: '${color.key}>', kind: CompletionKind.color, type: color.value, detail: 'Named color', swatch: color.value),
    ];

/// Whether the formatter applies `<tag>` (the text between `<` and `>`).
bool miniMessageKnows(String tag) {
  String name = tag.toLowerCase();
  if (name.startsWith('/')) name = name.substring(1);
  if (name.startsWith('color:')) name = name.substring(6);
  if (name.startsWith('colour:')) name = name.substring(7);
  if (RegExp(r'^#[0-9a-f]{6}$').hasMatch(name)) return true;
  return miniMessageColors.containsKey(name) || _decorations.containsKey(name) || _aliases.contains(name) || name == 'reset';
}

/// The swatch colour of a colour tag or value, or null.
String? miniMessageSwatch(String name) {
  final String lower = name.toLowerCase();
  if (RegExp(r'^#[0-9a-f]{6}$').hasMatch(lower)) return lower;
  return miniMessageColors[lower] ?? switch (lower) {
    'grey' => miniMessageColors['gray'],
    'dark_grey' => miniMessageColors['dark_gray'],
    _ => null,
  };
}
