/// Every keyboard shortcut the editor has, for the page-wide handler and
/// the shortcut sheet. Keys are written with `Mod` for Cmd on a Mac and Ctrl
/// elsewhere; [keyLabel] turns them into what the reader's keyboard says.
/// Pure Dart, so the registry and the matching are unit tested.
library;

/// What a page-wide shortcut does.
enum ShortcutAction { palette, sheet, undo, redo, save, newDocument, browseNpcs }

/// One shortcut: the keys (several mean any of them) and what it does.
class Shortcut {
  const Shortcut(this.keys, this.label, {this.action});

  /// Each entry is one way to press it, such as `Mod+Shift+Z`.
  final List<String> keys;
  final String label;

  /// Set for the page-wide shortcuts the shell handles.
  final ShortcutAction? action;
}

/// A titled group of shortcuts on the sheet.
class ShortcutGroup {
  const ShortcutGroup(this.title, this.where, this.shortcuts);

  final String title;

  /// Where the keys work, in a few words.
  final String where;
  final List<Shortcut> shortcuts;
}

const List<ShortcutGroup> shortcutGroups = <ShortcutGroup>[
  ShortcutGroup('Anywhere', 'Outside text boxes', <Shortcut>[
    Shortcut(<String>['Mod+K', '/'], 'Search', action: ShortcutAction.palette),
    Shortcut(<String>['?'], 'Show these shortcuts', action: ShortcutAction.sheet),
    Shortcut(<String>['Mod+Z'], 'Undo', action: ShortcutAction.undo),
    Shortcut(<String>['Mod+Shift+Z', 'Mod+Y'], 'Redo', action: ShortcutAction.redo),
    Shortcut(<String>['N'], 'New document', action: ShortcutAction.newDocument),
    Shortcut(<String>['B'], 'NPCs', action: ShortcutAction.browseNpcs),
  ]),
  ShortcutGroup('Saving', 'Also while typing', <Shortcut>[
    Shortcut(<String>['Mod+S'], 'Apply to the server, or save in this browser', action: ShortcutAction.save),
  ]),
  ShortcutGroup('Search and lists', 'In search, lists, dropdowns and suggestions', <Shortcut>[
    Shortcut(<String>['Up', 'Down'], 'Move through the list'),
    Shortcut(<String>['Enter'], 'Run or pick'),
    Shortcut(<String>['Escape'], 'Close'),
    Shortcut(<String>['Ctrl+Space'], 'Show suggestions in a text box or the code view'),
  ]),
  ShortcutGroup('Tabs, sections and trees', 'On a focused header, tab or row', <Shortcut>[
    Shortcut(<String>['Up', 'Down'], 'Move between rows'),
    Shortcut(<String>['Left', 'Right'], 'Close or open a section, or move between tabs'),
    Shortcut(<String>['Home', 'End'], 'First or last'),
    Shortcut(<String>['Enter', 'Space'], 'Open or pick'),
  ]),
  ShortcutGroup('Conversation', 'In a conversation line or answer', <Shortcut>[
    Shortcut(<String>['Enter'], 'Add a line, or an answer'),
    Shortcut(<String>['Backspace'], 'Remove an empty line or answer'),
    Shortcut(<String>['Alt+Up', 'Alt+Down'], 'Move the step'),
    Shortcut(<String>['Mod+D'], 'Duplicate'),
    Shortcut(<String>['Mod+Enter'], 'Add what follows an answer'),
  ]),
  ShortcutGroup('Behavior steps', 'On a focused step', <Shortcut>[
    Shortcut(<String>['Up', 'Down'], 'Move between steps'),
    Shortcut(<String>['Right', 'Left'], 'Open or close the step'),
    Shortcut(<String>['Delete'], 'Remove the step, after a question'),
  ]),
  ShortcutGroup('Lists of points, offers and keyframes', 'On a focused route point, offer or keyframe', <Shortcut>[
    Shortcut(<String>['Up', 'Down'], 'Move between offers'),
    Shortcut(<String>['Left', 'Right'], 'Move a keyframe'),
    Shortcut(<String>['Delete', 'Backspace'], 'Remove it'),
  ]),
  ShortcutGroup('3D preview', 'On the focused stage', <Shortcut>[
    Shortcut(<String>['Left', 'Right', 'Up', 'Down'], 'Orbit'),
    Shortcut(<String>['Shift+arrows'], 'Pan'),
    Shortcut(<String>['+', '-'], 'Zoom'),
    Shortcut(<String>['0'], 'Reset the camera'),
    Shortcut(<String>['Mod+wheel'], 'Zoom with the mouse'),
  ]),
];

/// The groups whose shortcuts the shell handles page-wide.
List<ShortcutGroup> get pageGroups => shortcutGroups.take(2).toList();

/// Whether [action] runs while focus is in a text box. Only saving does:
/// Mod+S never reaches the browser's own Save page.
bool firesWhileTyping(ShortcutAction action) => action == ShortcutAction.save;

/// [keys] as the reader's keyboard labels them: `Mod` is Cmd on a Mac and
/// Ctrl elsewhere, `Alt` is Option on a Mac.
String keyLabel(String keys, {required bool mac}) => keys
    .split('+')
    .map((String part) => switch (part) {
          'Mod' => mac ? 'Cmd' : 'Ctrl',
          'Alt' => mac ? 'Option' : 'Alt',
          _ => part,
        })
    .join('+');

/// What the browser's `aria-keyshortcuts` expects for [keys].
String ariaKeys(String keys, {required bool mac}) => keys
    .split('+')
    .map((String part) => switch (part) {
          'Mod' => mac ? 'Meta' : 'Control',
          _ => part,
        })
    .join('+');

/// The page-wide action a key press asks for, or null. [mod] is Ctrl or
/// Cmd held; Alt never starts a page shortcut.
ShortcutAction? matchShortcut({required String key, required bool mod, required bool shift, required bool alt}) {
  if (alt) return null;
  final String lower = key.toLowerCase();
  if (mod) {
    return switch (lower) {
      'k' => ShortcutAction.palette,
      'z' => shift ? ShortcutAction.redo : ShortcutAction.undo,
      'y' => ShortcutAction.redo,
      's' => ShortcutAction.save,
      _ => null,
    };
  }
  return switch (key) {
    '?' => ShortcutAction.sheet,
    '/' => ShortcutAction.palette,
    'n' || 'N' when !shift => ShortcutAction.newDocument,
    'b' || 'B' when !shift => ShortcutAction.browseNpcs,
    _ => null,
  };
}
