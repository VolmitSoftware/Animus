/// A chest or shop window painted like the in-game screen: the title in the
/// Minecraft font, slots with item icons from the stage's item meshers,
/// stack counts, and the name and lore tooltip on hover or focus.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_layout.dart';
import '../../logic/gui/gui_text.dart';
import '../mc/mc_item_icons.dart';
import 'gui_fields.dart';

/// The default inventory title colour (`0x404040`).
const int mcInventoryTitleColor = 0x404040;

/// Lore's default colour, dark purple italic in the client; shown plain here.
const int mcLoreColor = 0xAA00AA;

class ChestWindow extends StatefulWidget {
  const ChestWindow({
    required this.layout,
    this.selected,
    this.onSlot,
    this.showMask = false,
    this.label = 'Chest window',
    super.key,
  });

  final ChestLayout layout;
  final int? selected;

  /// Called with the slot a click picks; null makes the window read-only.
  final void Function(int slot)? onSlot;

  /// Marks each slot with its mask character, for editing.
  final bool showMask;
  final String label;

  @override
  State<ChestWindow> createState() => _ChestWindowState();
}

class _ChestWindowState extends State<ChestWindow> {
  @override
  void initState() {
    super.initState();
    McItemIcons.addListener(_refresh);
  }

  @override
  void dispose() {
    McItemIcons.removeListener(_refresh);
    super.dispose();
  }

  void _refresh() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final ChestLayout layout = component.layout;
    return dom.div(classes: 'animus-chest-screen', attributes: <String, String>{'role': 'group', 'aria-label': component.label}, <Widget>[
      dom.div(classes: 'animus-chest-title', <Widget>[GuiStyledText(layout.title, defaultColor: mcInventoryTitleColor)]),
      dom.div(
        classes: 'animus-chest',
        styles: dom.Styles(raw: <String, String>{'grid-template-columns': 'repeat(${layout.width}, var(--animus-slot))'}),
        <Widget>[for (final ChestCell cell in layout.cells) _slot(cell)],
      ),
    ]);
  }

  Widget _slot(ChestCell cell) {
    final String? item = cell.itemId;
    final String? key = McItemIcons.keyOf(item);
    final String? url = key == null ? null : McItemIcons.url(key);
    final String name = asString(cell.icon['name']) ?? (item ?? '');
    final List<String> lore = stringList(cell.icon['lore']);
    final int count = asInt(cell.icon['count']) ?? 1;
    final bool selected = component.selected == cell.slot;
    final String plain = guiPlainText(name).trim();
    final String described = cell.source == ChestCellSource.empty
        ? 'Slot ${cell.slot}, empty'
        : 'Slot ${cell.slot}, ${plain.isEmpty ? (item ?? 'item') : plain}';
    final List<String> classes = <String>[
      'animus-chest-slot',
      'is-${cell.source.name}',
      if (selected) 'is-selected',
      if (cell.hidden) 'is-hidden',
    ];
    final Widget face = dom.span(classes: 'animus-chest-face', <Widget>[
      if (url != null)
        dom.img(src: url, alt: '', attributes: const <String, String>{'draggable': 'false'})
      else if (item != null && item.isNotEmpty)
        dom.span(classes: 'animus-chest-missing', <Widget>[Component.text(_short(item))]),
      if (item != null && count > 1) dom.span(classes: 'animus-chest-count', <Widget>[Component.text('$count')]),
      if (component.showMask && cell.maskChar != null) dom.span(classes: 'animus-chest-key', <Widget>[Component.text(cell.maskChar!)]),
      if (cell.label != null && cell.source == ChestCellSource.list) dom.span(classes: 'animus-chest-key is-list', <Widget>[Component.text(cell.label!)]),
      if (cell.source != ChestCellSource.empty && (plain.isNotEmpty || lore.isNotEmpty))
        dom.span(classes: 'animus-chest-tooltip', attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[
          dom.span(classes: 'animus-chest-tooltip-name', <Widget>[GuiStyledText(name)]),
          for (final String line in lore) dom.span(classes: 'animus-chest-tooltip-lore', <Widget>[GuiStyledText(line, defaultColor: mcLoreColor)]),
        ]),
    ]);
    if (component.onSlot == null) {
      return dom.span(classes: classes.join(' '), attributes: <String, String>{'title': described}, <Widget>[face]);
    }
    return dom.button(
      classes: classes.join(' '),
      attributes: <String, String>{'type': 'button', 'aria-label': described, 'aria-pressed': '$selected', 'data-slot': '${cell.slot}'},
      onClick: () => component.onSlot!(cell.slot),
      <Widget>[face],
    );
  }

  static String _short(String item) {
    final String bare = item.substring(item.indexOf(':') + 1).replaceAll('_', ' ');
    return bare.length > 14 ? '${bare.substring(0, 13)}.' : bare;
  }
}
