/// A button that opens a short menu of actions: the top bar's Open, Import,
/// Export and phone More menus. Arrow keys move between items, Escape and
/// Tab close it, and focus goes back to the button.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

/// One menu item. A null [onSelect] shows the item disabled.
class MenuEntry {
  const MenuEntry(this.label, this.onSelect, {this.hint});

  final String label;
  final void Function()? onSelect;

  /// A short note after the label, such as a shortcut.
  final String? hint;
}

class MenuButton extends StatefulWidget {
  const MenuButton({
    required this.id,
    required this.label,
    required this.entries,
    this.icon,
    this.emphasis = false,
    this.disabled = false,
    super.key,
  });

  /// Page-unique id; the menu is `<id>-menu`.
  final String id;
  final String label;
  final List<MenuEntry> entries;
  final ArcaneGlyph? icon;

  /// A secondary (outlined) button instead of a ghost one.
  final bool emphasis;
  final bool disabled;

  @override
  State<MenuButton> createState() => _MenuButtonState();
}

class _MenuButtonState extends State<MenuButton> {
  bool _open = false;
  JSFunction? _outside;

  String get _menuId => '${component.id}-menu';

  @override
  void dispose() {
    _stopOutside();
    super.dispose();
  }

  void _toggle() => _open ? _close(refocus: false) : _show();

  void _show() {
    setState(() => _open = true);
    _outside = ((web.Event event) {
      final web.Element? root = web.document.getElementById('${component.id}-wrap');
      final web.EventTarget? target = event.target;
      if (root != null && target != null && target.isA<web.Node>() && root.contains(target as web.Node)) return;
      _close(refocus: false);
    }).toJS;
    web.document.addEventListener('pointerdown', _outside);
    web.window.setTimeout((() => _focusItem(0)).toJS, 0.toJS);
  }

  void _close({required bool refocus}) {
    _stopOutside();
    if (mounted && _open) setState(() => _open = false);
    if (refocus) (web.document.getElementById(component.id) as web.HTMLElement?)?.focus();
  }

  void _stopOutside() {
    if (_outside != null) web.document.removeEventListener('pointerdown', _outside);
    _outside = null;
  }

  List<web.HTMLElement> _items() {
    final web.NodeList? nodes = web.document.getElementById(_menuId)?.querySelectorAll('[role="menuitem"]:not([disabled])');
    if (nodes == null) return const <web.HTMLElement>[];
    return <web.HTMLElement>[for (int index = 0; index < nodes.length; index++) nodes.item(index)! as web.HTMLElement];
  }

  void _focusItem(int index) {
    final List<web.HTMLElement> items = _items();
    if (items.isEmpty) return;
    items[index.clamp(0, items.length - 1)].focus();
  }

  void _keys(web.Event raw) {
    final web.KeyboardEvent event = raw as web.KeyboardEvent;
    final List<web.HTMLElement> items = _items();
    final int at = items.indexWhere((web.HTMLElement item) => identical(item, web.document.activeElement));
    switch (event.key) {
      case 'Escape':
        event.preventDefault();
        event.stopPropagation();
        _close(refocus: true);
      case 'Tab':
        _close(refocus: false);
      case 'ArrowDown':
        event.preventDefault();
        _focusItem(at < 0 || at + 1 >= items.length ? 0 : at + 1);
      case 'ArrowUp':
        event.preventDefault();
        _focusItem(at <= 0 ? items.length - 1 : at - 1);
      case 'Home':
        event.preventDefault();
        _focusItem(0);
      case 'End':
        event.preventDefault();
        _focusItem(items.length - 1);
    }
  }

  @override
  Widget build(BuildContext context) {
    final Map<String, String> trigger = <String, String>{
      'aria-haspopup': 'menu',
      'aria-expanded': '$_open',
      'aria-controls': _menuId,
    };
    return dom.div(
      id: '${component.id}-wrap',
      classes: 'animus-menu-wrap',
      events: <String, void Function(web.Event)>{'keydown': _keys},
      <Widget>[
        if (component.emphasis)
          Button.secondary(
            id: component.id,
            label: component.label,
            icon: component.icon,
            size: ButtonSize.small,
            disabled: component.disabled,
            attributes: trigger,
            onPressed: _toggle,
          )
        else
          Button.ghost(
            id: component.id,
            label: component.label,
            icon: component.icon,
            size: ButtonSize.small,
            disabled: component.disabled,
            attributes: trigger,
            onPressed: _toggle,
          ),
        if (_open)
          dom.div(
            id: _menuId,
            classes: 'animus-menu',
            attributes: <String, String>{'role': 'menu', 'aria-labelledby': component.id},
            <Widget>[
              for (final MenuEntry entry in component.entries)
                dom.button(
                  classes: 'animus-menu-item',
                  attributes: <String, String>{
                    'type': 'button',
                    'role': 'menuitem',
                    'tabindex': '-1',
                    if (entry.onSelect == null) 'disabled': '',
                  },
                  events: <String, void Function(web.Event)>{
                    'click': (web.Event _) {
                      final void Function()? select = entry.onSelect;
                      _close(refocus: true);
                      select?.call();
                    },
                  },
                  <Widget>[
                    dom.span(<Widget>[Component.text(entry.label)]),
                    if (entry.hint != null) dom.span(classes: 'animus-menu-hint', <Widget>[Component.text(entry.hint!)]),
                  ],
                ),
            ],
          ),
      ],
    );
  }
}
