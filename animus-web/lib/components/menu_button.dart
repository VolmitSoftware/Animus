/// A button that opens a short menu of actions, on the kit's [AppMenu].
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../ui/menu.dart';

/// One menu item. A null [onSelect] shows the item disabled.
class MenuEntry {
  const MenuEntry(this.label, this.onSelect, {this.hint});

  final String label;
  final void Function()? onSelect;

  /// A short note after the label, such as a shortcut.
  final String? hint;
}

class MenuButton extends StatelessWidget {
  const MenuButton({
    required this.id,
    required this.label,
    required this.entries,
    this.icon,
    this.emphasis = false,
    this.disabled = false,
    super.key,
  });

  /// Page-unique id; the menu is `<id>` and its button `<id>-button`.
  final String id;
  final String label;
  final List<MenuEntry> entries;
  final ArcaneGlyph? icon;

  /// A bordered button instead of a ghost one.
  final bool emphasis;
  final bool disabled;

  @override
  Widget build(BuildContext context) => AppMenu(
        id: id,
        label: label,
        icon: icon,
        outline: emphasis,
        disabled: disabled,
        entries: <AppMenuEntry>[for (final MenuEntry entry in entries) AppMenuEntry(entry.label, entry.onSelect, hint: entry.hint)],
      );
}
