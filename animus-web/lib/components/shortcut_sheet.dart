/// The keyboard shortcut sheet (`?` outside a text box, or "Keyboard
/// shortcuts" in the overflow menu): every page-wide and local shortcut from
/// [shortcutGroups], labelled for the reader's platform, in the kit's
/// dialog. Escape or Close shuts it and focus goes back where it was.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:web/web.dart' as web;

import '../config/docs_links.dart';
import '../ui/action_row.dart';
import '../ui/dialog.dart';
import 'shortcuts.dart';

/// Whether the reader is on a Mac (or an iPad with a keyboard), where the
/// modifier is Cmd.
bool get macPlatform {
  final String platform = web.window.navigator.platform;
  return platform.startsWith('Mac') || platform == 'iPad' || platform == 'iPhone';
}

/// [keys] in the reader's keyboard labels.
String platformKeys(String keys) => keyLabel(keys, mac: macPlatform);

/// The `aria-keyshortcuts` value for [keys] on the reader's platform.
String platformAriaKeys(List<String> keys) => <String>[for (final String key in keys) ariaKeys(key, mac: macPlatform)].join(' ');

class ShortcutSheet extends StatelessWidget {
  const ShortcutSheet({required this.open, required this.onClose, super.key});

  final bool open;
  final void Function() onClose;

  @override
  Widget build(BuildContext context) => AppDialog(
        id: 'ui-shortcuts',
        open: open,
        title: 'Keyboard shortcuts',
        wide: true,
        onClose: onClose,
        footer: DialogFooter(cancel: RowAction('Close', onClose)),
        children: <Widget>[
          Column(crossAxisAlignment: CrossAxisAlignment.stretch, spacing: 20, children: <Widget>[
            for (final ShortcutGroup group in shortcutGroups)
              dom.section(<Widget>[
                Column(crossAxisAlignment: CrossAxisAlignment.stretch, spacing: 8, children: <Widget>[
                  Column(crossAxisAlignment: CrossAxisAlignment.start, spacing: 2, children: <Widget>[
                    Text(group.title, size: FontSize.sm, weight: FontWeight.w600, element: 'h3'),
                    Text.caption(group.where),
                  ]),
                  for (final Shortcut shortcut in group.shortcuts)
                    Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, spacing: 16, children: <Widget>[
                      Text(shortcut.label, size: FontSize.sm),
                      Row(mainAxisSize: MainAxisSize.min, spacing: 4, children: <Widget>[
                        for (int index = 0; index < shortcut.keys.length; index++) ...<Widget>[
                          if (index > 0) const Text.caption('or'),
                          ArcaneKbd(platformKeys(shortcut.keys[index])),
                        ],
                      ]),
                    ]),
                ]),
              ]),
            Row(children: <Widget>[
              Button.link(
                label: 'All shortcuts in the docs',
                href: Docs.editor.url,
                size: ButtonSize.sm,
                attributes: const <String, String>{'target': '_blank', 'rel': 'noopener'},
              ),
            ]),
          ]),
        ],
      );
}
