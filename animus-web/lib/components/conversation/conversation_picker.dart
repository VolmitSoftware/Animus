/// The conversation editor's pickers: [ConversationPicker] picks one value
/// with the editor's dropdown, [ConversationMenu] runs one action from a
/// menu that opens under its button, moves with the arrow keys, Home and
/// End, and closes with Escape or Tab, giving focus back to the button. It
/// also closes once its button scrolls out of view.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/select/select_model.dart';
import '../select/animus_select.dart';
import '../shortcut_sheet.dart' show platformKeys;

int _nextPickerId = 0;

class PickOption {
  const PickOption(this.value, this.label, {this.hint});

  final String value;
  final String label;

  /// A short note after the label.
  final String? hint;
}

class MenuAction {
  const MenuAction(this.label, this.onSelect, {this.hint});

  final String label;

  /// Null shows the item disabled.
  final void Function()? onSelect;

  /// A shortcut written as in the shortcut sheet (`Alt+Up`, `Mod+D`),
  /// shown in the reader's platform names, or other short text.
  final String? hint;
}

/// The open and close plumbing both dropdowns share.
abstract class _PopState<T extends StatefulWidget> extends State<T> {
  final String id = 'animus-pick-${_nextPickerId++}';
  bool open = false;
  JSFunction? _outside;
  JSFunction? _scrolled;

  String get popId => '$id-pop';

  /// The selector of the items arrow keys move between.
  String get itemSelector;

  @override
  void dispose() {
    _stopOutside();
    super.dispose();
  }

  void _stopOutside() {
    if (_outside != null) web.document.removeEventListener('pointerdown', _outside);
    if (_scrolled != null) web.window.removeEventListener('scroll', _scrolled, true.toJS);
    _outside = null;
    _scrolled = null;
  }

  /// Whether the menu's button is on screen below the sticky top bar. A
  /// menu whose button has scrolled away would float detached over the
  /// page and the top bar, so it closes instead.
  bool _anchored() {
    final web.Element? button = web.document.getElementById(id);
    if (button == null) return false;
    final web.DOMRect box = button.getBoundingClientRect();
    final double top = web.document.querySelector('.animus-topbar')?.getBoundingClientRect().bottom ?? 0;
    return box.top >= top && box.bottom <= web.window.innerHeight;
  }

  void show({int focus = 0}) {
    setState(() => open = true);
    _outside = ((web.Event event) {
      final web.Element? root = web.document.getElementById('$id-wrap');
      final web.EventTarget? target = event.target;
      if (root != null && target != null && target.isA<web.Node>() && root.contains(target as web.Node)) return;
      close(refocus: false);
    }).toJS;
    web.document.addEventListener('pointerdown', _outside);
    if (!_anchored()) (web.document.getElementById(id) as web.HTMLElement?)?.scrollIntoView(web.ScrollIntoViewOptions(block: 'center'));
    _scrolled = ((web.Event _) {
      if (!_anchored()) close(refocus: false);
    }).toJS;
    web.window.addEventListener('scroll', _scrolled, true.toJS);
    web.window.setTimeout((() {
      _place();
      focusItem(focus);
    }).toJS, 0.toJS);
  }

  void close({required bool refocus}) {
    _stopOutside();
    if (mounted && open) setState(() => open = false);
    if (refocus) (web.document.getElementById(id) as web.HTMLElement?)?.focus();
  }

  /// Keeps the list inside the window: it shifts left when it would pass
  /// the right edge.
  void _place() {
    final web.HTMLElement? pop = web.document.getElementById(popId) as web.HTMLElement?;
    if (pop == null) return;
    pop.style.setProperty('--animus-pick-shift', '0px');
    final web.DOMRect box = pop.getBoundingClientRect();
    final double room = web.document.documentElement!.clientWidth - 8 - box.right;
    if (room < 0) pop.style.setProperty('--animus-pick-shift', '${room < -box.left + 8 ? -box.left + 8 : room}px');
  }

  List<web.HTMLElement> items() {
    final web.NodeList? nodes = web.document.getElementById(popId)?.querySelectorAll(itemSelector);
    if (nodes == null) return const <web.HTMLElement>[];
    return <web.HTMLElement>[for (int index = 0; index < nodes.length; index++) nodes.item(index)! as web.HTMLElement];
  }

  void focusItem(int index) {
    final List<web.HTMLElement> found = items();
    if (found.isEmpty) return;
    found[index.clamp(0, found.length - 1)].focus();
  }

  void keys(web.Event raw) {
    final web.KeyboardEvent event = raw as web.KeyboardEvent;
    if (!open) {
      if (event.key == 'ArrowDown' || event.key == 'ArrowUp') {
        event.preventDefault();
        show(focus: openFocus);
      }
      return;
    }
    final List<web.HTMLElement> found = items();
    final int at = found.indexWhere((web.HTMLElement item) => identical(item, web.document.activeElement));
    switch (event.key) {
      case 'Escape':
        event.preventDefault();
        event.stopPropagation();
        close(refocus: true);
      case 'Tab':
        close(refocus: false);
      case 'ArrowDown':
        event.preventDefault();
        focusItem(at < 0 || at + 1 >= found.length ? 0 : at + 1);
      case 'ArrowUp':
        event.preventDefault();
        focusItem(at <= 0 ? found.length - 1 : at - 1);
      case 'Home':
        event.preventDefault();
        focusItem(0);
      case 'End':
        event.preventDefault();
        focusItem(found.length - 1);
    }
  }

  /// The item focused when the list opens.
  int get openFocus => 0;
}

/// A dropdown that picks one of [options], drawn by the editor's one
/// dropdown ([AnimusSelect]). It shows the picked label, or [placeholder]
/// when [value] is not among them.
class ConversationPicker extends StatelessWidget {
  const ConversationPicker({required this.label, required this.options, required this.onPick, this.value, this.placeholder = 'Pick one', this.id, super.key});

  /// The accessible name, such as "Start".
  final String label;
  final List<PickOption> options;
  final String? value;
  final String placeholder;
  final void Function(String value) onPick;

  /// The trigger's element id, for a `<label for>`.
  final String? id;

  @override
  Widget build(BuildContext context) => AnimusSelect(
        id: id,
        hiddenLabel: label,
        value: value,
        placeholder: placeholder,
        options: <SelectOption>[
          for (final PickOption option in options) SelectOption(value: option.value, label: option.label, description: option.hint),
        ],
        onChanged: onPick,
      );
}

/// A button that opens a menu of [actions]. With [icon] and no visible
/// text, [label] is its accessible name.
class ConversationMenu extends StatefulWidget {
  const ConversationMenu({required this.label, required this.actions, this.icon, this.text, super.key});

  final String label;
  final List<MenuAction> actions;
  final ArcaneGlyph? icon;

  /// Visible text; null shows the icon alone.
  final String? text;

  @override
  State<ConversationMenu> createState() => _ConversationMenuState();
}

class _ConversationMenuState extends _PopState<ConversationMenu> {
  @override
  String get itemSelector => '[role="menuitem"]:not([disabled])';

  @override
  Widget build(BuildContext context) {
    final Map<String, String> trigger = <String, String>{
      'aria-haspopup': 'menu',
      'aria-expanded': '$open',
      'aria-controls': popId,
      'aria-label': component.label,
      'title': component.label,
    };
    return dom.span(
      id: '$id-wrap',
      classes: 'animus-menu-wrap animus-pick',
      events: <String, void Function(web.Event)>{'keydown': keys},
      <Widget>[
        Button.ghost(
          id: id,
          label: component.text,
          icon: component.icon,
          size: ButtonSize.small,
          attributes: trigger,
          onPressed: () => open ? close(refocus: false) : show(),
        ),
        if (open)
          dom.div(
            id: popId,
            classes: 'animus-menu animus-pick-list',
            attributes: <String, String>{'role': 'menu', 'aria-labelledby': id},
            <Widget>[
              for (final MenuAction action in component.actions)
                dom.button(
                  classes: 'animus-menu-item',
                  attributes: <String, String>{
                    'type': 'button',
                    'role': 'menuitem',
                    'tabindex': '-1',
                    if (action.onSelect == null) 'disabled': '',
                  },
                  events: <String, void Function(web.Event)>{
                    'click': (web.Event _) {
                      final void Function()? select = action.onSelect;
                      close(refocus: true);
                      select?.call();
                    },
                  },
                  <Widget>[
                    dom.span(<Widget>[Component.text(action.label)]),
                    if (action.hint != null) dom.span(classes: 'animus-menu-hint', <Widget>[Component.text(platformKeys(action.hint!))]),
                  ],
                ),
            ],
          ),
      ],
    );
  }
}
