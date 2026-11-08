/// The keyboard shortcut sheet (`?` outside a text box, or "Keyboard
/// shortcuts" in Commands): every page-wide and local shortcut from
/// [shortcutGroups], labelled for the reader's platform. Escape or Close
/// shuts it and focus goes back where it was.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../config/docs_links.dart';
import '../services/browser.dart' show focusWhenShown, onKeyDown;
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

class ShortcutSheet extends StatefulWidget {
  const ShortcutSheet({required this.onClose, super.key});

  final void Function() onClose;

  @override
  State<ShortcutSheet> createState() => _ShortcutSheetState();
}

class _ShortcutSheetState extends State<ShortcutSheet> {
  static const String _panelId = 'animus-shortcuts';
  static const String _closeId = 'animus-shortcuts-close';
  static const String _bodyId = 'animus-shortcuts-body';
  web.HTMLElement? _returnTo;
  void Function()? _stopKeys;

  /// The list runs past the bottom of the sheet: the foot says so.
  bool _more = false;

  void _measure() {
    final web.Element? body = web.document.getElementById(_bodyId);
    if (body == null || !mounted) return;
    final bool more = body.scrollTop + body.clientHeight < body.scrollHeight - 2;
    if (more != _more) setState(() => _more = more);
  }

  @override
  void initState() {
    super.initState();
    final web.Element? active = web.document.activeElement;
    if (active != null && active.isA<web.HTMLElement>()) _returnTo = active as web.HTMLElement;
    // Escape closes the sheet even before Close has taken focus.
    _stopKeys = onKeyDown((web.KeyboardEvent event) {
      if (event.key != 'Escape') return;
      event.preventDefault();
      _close();
    });
    focusWhenShown(_closeId);
    web.window.setTimeout(_measure.toJS, 0.toJS);
  }

  @override
  void dispose() {
    _stopKeys?.call();
    super.dispose();
  }

  void _close() {
    final web.HTMLElement? back = _returnTo;
    component.onClose();
    if (back != null && back.isConnected) back.focus();
  }

  void _keys(web.Event raw) {
    final web.KeyboardEvent event = raw as web.KeyboardEvent;
    if (event.key == 'Tab') {
      // Focus stays inside: Close, the body (it scrolls) and the Docs link.
      final web.NodeList stops = web.document.querySelectorAll('#$_panelId [data-stop]');
      if (stops.length == 0) return;
      final web.Element first = stops.item(0)! as web.Element;
      final web.Element last = stops.item(stops.length - 1)! as web.Element;
      final web.Element? active = web.document.activeElement;
      if (event.shiftKey && identical(active, first)) {
        event.preventDefault();
        (last as web.HTMLElement).focus();
      } else if (!event.shiftKey && identical(active, last)) {
        event.preventDefault();
        (first as web.HTMLElement).focus();
      }
    }
  }

  @override
  Widget build(BuildContext context) => dom.div(classes: 'animus-palette-layer', <Widget>[
        dom.div(
          classes: 'animus-palette-scrim',
          attributes: const <String, String>{'aria-hidden': 'true'},
          events: <String, void Function(web.Event)>{'click': (web.Event _) => _close()},
          const <Widget>[],
        ),
        dom.div(
          id: _panelId,
          classes: 'animus-palette animus-shortcuts',
          attributes: const <String, String>{'role': 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'animus-shortcuts-title'},
          events: <String, void Function(web.Event)>{'keydown': _keys},
          <Widget>[
            dom.div(classes: 'animus-shortcuts-head', <Widget>[
              const dom.h2(id: 'animus-shortcuts-title', <Widget>[Component.text('Keyboard shortcuts')]),
              Button.ghost(
                id: _closeId,
                label: 'Close',
                size: ButtonSize.small,
                attributes: const <String, String>{'data-stop': ''},
                onPressed: _close,
              ),
            ]),
            dom.div(
              id: _bodyId,
              classes: 'animus-shortcuts-body',
              attributes: const <String, String>{'tabindex': '0', 'data-stop': '', 'aria-label': 'Shortcut list'},
              events: <String, void Function(web.Event)>{'scroll': (web.Event _) => _measure()},
              <Widget>[
                for (final ShortcutGroup group in shortcutGroups)
                  dom.section(classes: 'animus-shortcuts-group', <Widget>[
                    dom.h3(<Widget>[Component.text(group.title)]),
                    dom.p(classes: 'animus-muted', <Widget>[Component.text(group.where)]),
                    dom.dl(<Widget>[
                      for (final Shortcut shortcut in group.shortcuts)
                        dom.div(classes: 'animus-shortcuts-row', <Widget>[
                          dom.dt(<Widget>[
                            for (int index = 0; index < shortcut.keys.length; index++) ...<Widget>[
                              if (index > 0) const dom.span(classes: 'animus-muted', <Widget>[Component.text(' or ')]),
                              Component.element(tag: 'kbd', children: <Widget>[Component.text(platformKeys(shortcut.keys[index]))]),
                            ],
                          ]),
                          dom.dd(<Widget>[Component.text(shortcut.label)]),
                        ]),
                    ]),
                  ]),
              ],
            ),
            dom.p(classes: 'animus-shortcuts-foot', <Widget>[
              if (_more) const dom.span(classes: 'animus-shortcuts-more', attributes: <String, String>{'data-testid': 'shortcuts-more'}, <Widget>[Component.text('More below. Scroll the list.')]),
              dom.a(
                href: Docs.editorKeyboard.url,
                attributes: const <String, String>{'target': '_blank', 'rel': 'noopener', 'data-stop': ''},
                const <Widget>[Component.text('Keyboard and commands in the docs')],
              ),
            ]),
          ],
        ),
      ]);
}
