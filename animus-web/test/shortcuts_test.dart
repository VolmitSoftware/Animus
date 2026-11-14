import 'package:animus_editor/components/shortcuts.dart';
import 'package:test/test.dart';

void main() {
  test('modifier labels follow the platform', () {
    expect(keyLabel('Mod+Shift+Z', mac: true), 'Cmd+Shift+Z');
    expect(keyLabel('Mod+Shift+Z', mac: false), 'Ctrl+Shift+Z');
    expect(keyLabel('Alt+Up', mac: true), 'Option+Up');
    expect(keyLabel('Alt+Up', mac: false), 'Alt+Up');
    expect(keyLabel('?', mac: true), '?');
    expect(ariaKeys('Mod+K', mac: true), 'Meta+K');
    expect(ariaKeys('Mod+K', mac: false), 'Control+K');
  });

  test('every page-wide shortcut on the sheet is matched by its keys', () {
    final List<Shortcut> global = <Shortcut>[for (final ShortcutGroup group in pageGroups) ...group.shortcuts];
    expect(global.every((Shortcut shortcut) => shortcut.action != null), isTrue);
    expect(<ShortcutAction>{for (final Shortcut shortcut in global) shortcut.action!}, ShortcutAction.values.toSet());
    for (final Shortcut shortcut in global) {
      for (final String keys in shortcut.keys) {
        final List<String> parts = keys.split('+');
        final String key = parts.last == '?' || parts.last == '/' ? parts.last : parts.last.toLowerCase();
        expect(
          matchShortcut(key: key, mod: parts.contains('Mod'), shift: parts.contains('Shift'), alt: parts.contains('Alt')),
          shortcut.action,
          reason: keys,
        );
      }
    }
  });

  test('no two page-wide shortcuts share keys', () {
    final List<String> keys = <String>[for (final ShortcutGroup group in pageGroups) for (final Shortcut shortcut in group.shortcuts) ...shortcut.keys];
    expect(keys.toSet().length, keys.length);
  });

  test('keys that are not shortcuts do nothing', () {
    expect(matchShortcut(key: 'n', mod: false, shift: false, alt: true), isNull);
    expect(matchShortcut(key: 'q', mod: true, shift: false, alt: false), isNull);
    expect(matchShortcut(key: 'x', mod: false, shift: false, alt: false), isNull);
    expect(matchShortcut(key: 'N', mod: false, shift: true, alt: false), isNull);
    expect(matchShortcut(key: 'Z', mod: true, shift: true, alt: false), ShortcutAction.redo);
  });

  test('only saving runs while typing', () {
    expect(<ShortcutAction>[for (final ShortcutAction action in ShortcutAction.values) if (firesWhileTyping(action)) action], <ShortcutAction>[ShortcutAction.save]);
    expect(matchShortcut(key: 's', mod: true, shift: false, alt: false), ShortcutAction.save);
  });

  test('every group says where its keys work and lists some', () {
    for (final ShortcutGroup group in shortcutGroups) {
      expect(group.where, isNotEmpty);
      expect(group.shortcuts, isNotEmpty);
      expect(group.shortcuts.every((Shortcut shortcut) => shortcut.keys.isNotEmpty && shortcut.label.isNotEmpty), isTrue);
    }
  });
}
