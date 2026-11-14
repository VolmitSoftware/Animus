/// A button that opens a short list of actions, on shadcn's dropdown menu.
/// The arcane runtime opens it, moves focus with the arrow keys and types
/// ahead, closes it on Escape, Tab or a pick, and returns focus to the
/// button. Destructive entries are red and confirm in a dialog.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:web/web.dart' as web;

import 'density.dart';
import 'icon_button.dart';

/// One menu row. A null [onSelect] shows it disabled.
class AppMenuEntry {
  const AppMenuEntry(this.label, this.onSelect, {this.hint, this.description, this.destructive = false, this.icon}) : separator = false;

  /// A line between groups of entries.
  const AppMenuEntry.separator()
      : label = '',
        onSelect = null,
        hint = null,
        description = null,
        destructive = false,
        icon = null,
        separator = true;

  final String label;
  final void Function()? onSelect;

  /// A short note at the end of the row, such as a shortcut.
  final String? hint;

  /// A muted line under the label: what this entry makes or does.
  final String? description;
  final bool destructive;
  final ArcaneGlyph? icon;
  final bool separator;

  /// Whether a menu showing [other] in this row's place looks the same.
  bool looksLike(AppMenuEntry other) =>
      label == other.label &&
      hint == other.hint &&
      description == other.description &&
      destructive == other.destructive &&
      separator == other.separator &&
      (onSelect == null) == (other.onSelect == null);
}

int _nextMenuId = 0;

class AppMenu extends StatefulWidget {
  const AppMenu({
    required this.entries,
    this.label,
    this.icon,
    this.iconLabel = 'More actions',
    this.id,
    this.alignEnd = false,
    this.outline = false,
    this.primary = false,
    this.disabled = false,
    this.attributes = const <String, String>{},
    super.key,
  }) : assert(label != null || icon != null, 'A menu button needs a label or an icon.');

  /// The button's text. Without it the button is the [icon] alone and
  /// [iconLabel] names it. The trigger button's id is `<id>-button`.
  final String? label;
  final ArcaneGlyph? icon;

  /// The accessible name and tooltip of an icon-only button: what it acts
  /// on ("Actions for offer 2") where a page has many.
  final String iconLabel;
  final List<AppMenuEntry> entries;
  final String? id;

  /// Opens to the left of the button's right edge (menus at the end of a row).
  final bool alignEnd;

  /// A bordered button instead of a ghost one.
  final bool outline;

  /// The row's primary action as a menu ("Add component").
  final bool primary;
  final bool disabled;

  /// Extra attributes on the trigger button.
  final Map<String, String> attributes;

  @override
  State<AppMenu> createState() => _AppMenuState();
}

class _AppMenuState extends State<AppMenu> {
  /// Stable across rebuilds: the runtime keeps the open state by this id.
  late final String _id = component.id ?? 'ui-menu-${_nextMenuId++}';

  @override
  void initState() {
    super.initState();
    _catchEscape();
  }

  /// Whether the runtime has this menu open now.
  bool get _open => web.document.getElementById(_id)?.getAttribute('data-arcane-state') == 'open';

  /// A rebuild redraws the menu closed (the open state is the runtime's), so
  /// while it is open a rebuild that changes nothing it shows is skipped.
  /// The picks read the newest entries when they run.
  @override
  bool shouldRebuild(AppMenu newComponent) {
    if (!_open) return true;
    final AppMenu old = component;
    if (newComponent.label != old.label ||
        newComponent.iconLabel != old.iconLabel ||
        newComponent.disabled != old.disabled ||
        newComponent.outline != old.outline ||
        newComponent.primary != old.primary ||
        newComponent.alignEnd != old.alignEnd ||
        newComponent.entries.length != old.entries.length) {
      return true;
    }
    for (int index = 0; index < old.entries.length; index++) {
      if (!old.entries[index].looksLike(newComponent.entries[index])) return true;
    }
    return false;
  }

  /// Runs entry [index] with focus back on the trigger, so a confirmation it
  /// opens returns focus there instead of to the closed menu.
  void _select(int index) {
    final List<AppMenuEntry> entries = component.entries;
    if (index >= entries.length) return;
    final void Function()? run = entries[index].onSelect;
    (web.document.getElementById('$_id-button') as web.HTMLElement?)?.focus();
    run?.call();
  }

  @override
  Widget build(BuildContext context) {
    final AppMenu menu = component;
    final String trigger = '$_id-button';
    final Map<String, String> attributes = <String, String>{...menu.attributes, 'aria-haspopup': 'menu'};
    // Phones have no keyboard to show shortcuts for.
    final bool phone = web.window.matchMedia('(max-width: 760px)').matches;
    return dom.span(classes: 'ui-menu', attributes: <String, String>{'data-ui-menu': _id}, <Widget>[
      ArcaneDropdownMenu(
        id: _id,
        alignment: menu.alignEnd ? DropdownAlignment.right : DropdownAlignment.left,
        // Wide enough that the longest label and its shortcut stay on one line.
        width: _menuWidth(menu.entries),
        trigger: menu.label == null
            ? AppIconButton(id: trigger, icon: menu.icon!, label: menu.iconLabel, disabled: menu.disabled, attributes: attributes, tipAtEnd: menu.alignEnd, onPressed: () {})
            : Button(
                id: trigger,
                label: menu.label,
                icon: menu.icon?.sized(controlIconSize),
                size: buttonSize(DensityScope.of(context)),
                variant: menu.primary
                    ? ButtonVariant.primary
                    : menu.outline
                        ? ButtonVariant.outline
                        : ButtonVariant.ghost,
                disabled: menu.disabled,
                attributes: attributes,
                onPressed: () {},
              ),
        items: <ArcaneMenuItem>[
          for (final (int index, AppMenuEntry entry) in menu.entries.indexed)
            if (entry.separator)
              const MenuItemSeparator()
            else
              MenuItemAction(
                label: entry.label,
                description: entry.description,
                icon: entry.icon?.sized(controlIconSize),
                shortcut: phone && _isKeys(entry.hint) ? null : entry.hint,
                destructive: entry.destructive,
                disabled: entry.onSelect == null,
                onSelect: entry.onSelect == null ? null : () => _select(index),
              ),
        ],
      ),
    ]);
  }
}

/// A width that keeps every label on one line ("Import Gloss bundle  ?"),
/// or null for short menus and ones with description lines, which size
/// themselves.
double? _menuWidth(List<AppMenuEntry> entries) {
  if (entries.any((AppMenuEntry entry) => entry.description != null)) return null;
  final int longest = entries.fold(0, (int most, AppMenuEntry entry) => entry.label.length > most ? entry.label.length : most);
  if (longest <= 16) return null;
  final bool hints = entries.any((AppMenuEntry entry) => entry.hint != null);
  final bool icons = entries.any((AppMenuEntry entry) => entry.icon != null);
  return (longest * 8 + (hints ? 72 : 0) + (icons ? 28 : 0) + 40).clamp(200, 360).toDouble();
}

/// A hint that names keys ("⌘Z", "Ctrl+Shift+Z", "?") rather than a state.
bool _isKeys(String? hint) =>
    hint != null && !hint.contains(' ') && (hint.length == 1 || hint.contains('+') || RegExp('^[\u2318\u2325\u21e7\u2303]').hasMatch(hint));

bool _escapeCaught = false;

/// Escape on an open menu's button closes the menu. The button's tooltip
/// would otherwise take the key (it swallows Escape to close itself), which
/// left a menu opened by a click open until focus moved into it.
void _catchEscape() {
  if (_escapeCaught) return;
  _escapeCaught = true;
  web.window.addEventListener(
    'keydown',
    ((web.KeyboardEvent event) {
      if (event.key != 'Escape') return;
      final JSAny? target = event.target;
      if (target == null || !target.isA<web.Element>()) return;
      final web.Element element = target as web.Element;
      final web.Element? holder = element.closest('[data-ui-menu]');
      final String? id = holder?.getAttribute('data-ui-menu');
      if (id == null) return;
      final web.Element? surface = web.document.getElementById(id);
      if (surface == null || surface.getAttribute('data-arcane-state') != 'open' || surface.contains(element)) return;
      event.preventDefault();
      event.stopPropagation();
      (web.document.getElementById('$id-button') as web.HTMLElement?)?.click();
    }).toJS,
    web.AddEventListenerOptions(capture: true),
  );
}
