/// The one reorderable list of the GUI, shop, hologram, menu and quest
/// pages: each row is a drag handle, its line, and a "..." menu with Move
/// up, Move down, Duplicate and Delete (plus the row's own entries). Drag
/// is the pointer's way to reorder and the menu the keyboard's; there is
/// no third. Rows sit on the form's background with a separator between
/// them, never in a box; an open row shows its form under its line.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/gui/gui_expression.dart';
import '../../logic/gui/gui_json.dart';
import '../../ui/action_row.dart';
import '../../ui/confirm_dialog.dart';
import '../../ui/density.dart';
import 'gui_fields.dart';
import '../../ui/menu.dart';
import '../../ui/section.dart';
import '../suggest/autocomplete_input.dart';
import '../../ui/stack.dart';

class OrderedRow {
  const OrderedRow({required this.key, required this.name, required this.line, this.body, this.entries = const <AppMenuEntry>[], this.open = false, this.attributes = const <String, String>{}});

  /// Stable across edits (an id, or the index for a plain list).
  final String key;

  /// The row in a sentence ("offer 2", "line 1"), for its menu's name.
  final String name;

  /// The row's one line.
  final Widget line;

  /// The form shown under the line while the row is open.
  final Widget? body;

  /// Entries before Move up and Move down (Edit, Move to page).
  final List<AppMenuEntry> entries;
  final bool open;
  final Map<String, String> attributes;
}

class OrderedRows extends StatefulWidget {
  const OrderedRows({
    required this.label,
    required this.rows,
    this.onMove,
    this.onDuplicate,
    this.onDelete,
    this.deleteNoun,
    this.dense = true,
    this.classes,
    super.key,
  });

  /// Names the list ("Offers").
  final String label;
  final List<OrderedRow> rows;

  /// Moves the row at `from` to `to`; null for a list that does not reorder.
  final void Function(int from, int to)? onMove;
  final void Function(int index)? onDuplicate;
  final void Function(int index)? onDelete;

  /// With it, Delete asks first ("Delete offer?").
  final String? deleteNoun;
  final bool dense;
  final String? classes;

  @override
  State<OrderedRows> createState() => _OrderedRowsState();
}

class _OrderedRowsState extends State<OrderedRows> {
  int? _dragging;
  int? _over;

  Future<void> _delete(int index) async {
    final String? noun = component.deleteNoun;
    if (noun != null && !await askToConfirm(title: 'Delete $noun?', message: 'Undo brings it back.', confirmLabel: 'Delete', destructive: true)) return;
    component.onDelete?.call(index);
  }

  void _drop(int to) {
    final int? from = _dragging;
    setState(() {
      _dragging = null;
      _over = null;
    });
    if (from != null && from != to) component.onMove?.call(from, to);
  }

  @override
  Widget build(BuildContext context) {
    final List<OrderedRow> rows = component.rows;
    final bool moves = component.onMove != null && rows.length > 1;
    final Widget list = dom.ol(
      classes: 'animus-rows${component.classes == null ? '' : ' ${component.classes}'}',
      attributes: <String, String>{'aria-label': component.label},
      <Widget>[
        for (int index = 0; index < rows.length; index++) _row(rows[index], index, rows.length, moves),
      ],
    );
    return list;
  }

  /// The row's line, in the dense size unless the list says otherwise; the
  /// open form under it keeps the form's size. Controls line up on the first
  /// line, so a formatted sample under a field does not pull the menu down.
  Widget _line(List<Widget> children) {
    final Widget row = Row(gapSize: Gap.sm, crossAxisAlignment: CrossAxisAlignment.start, children: children);
    return component.dense ? DenseBox(classes: 'animus-rows-line', children: <Widget>[row]) : dom.div(classes: 'animus-rows-line', <Widget>[row]);
  }

  Widget _row(OrderedRow row, int index, int count, bool moves) {
    final List<AppMenuEntry> entries = <AppMenuEntry>[
      ...row.entries,
      if (moves) ...<AppMenuEntry>[
        AppMenuEntry('Move up', index == 0 ? null : () => component.onMove!(index, index - 1)),
        AppMenuEntry('Move down', index >= count - 1 ? null : () => component.onMove!(index, index + 1)),
      ],
      if (component.onDuplicate != null) AppMenuEntry('Duplicate', () => component.onDuplicate!(index)),
      if (component.onDelete != null) ...<AppMenuEntry>[
        const AppMenuEntry.separator(),
        AppMenuEntry('Delete', () => _delete(index), destructive: true),
      ],
    ];
    final List<String> classes = <String>[
      'animus-rows-item',
      if (row.open) 'is-open',
      if (_dragging == index) 'is-dragging',
      if (_over == index && _dragging != index) 'is-drop',
    ];
    return dom.li(
      key: ValueKey<String>('row:${row.key}'),
      classes: classes.join(' '),
      attributes: <String, String>{...row.attributes, 'data-row': row.key},
      events: moves
          ? <String, void Function(web.Event)>{
              'dragover': (web.Event event) {
                if (_dragging == null) return;
                event.preventDefault();
                if (_over != index) setState(() => _over = index);
              },
              'drop': (web.Event event) {
                event.preventDefault();
                _drop(index);
              },
            }
          : const <String, void Function(web.Event)>{},
      <Widget>[
        if (index > 0) ArcaneSeparator(margin: Gap.xs.value),
        _line(<Widget>[
          // A row that opens its form carries the grip and menu on its own
          // line, so all of it shares one centre line and the form below
          // takes the row's whole width.
          if (row.line case final RowOpener opener)
            Expanded(child: opener.around(leading: <Widget>[if (moves) _grip(row, index)], trailing: <Widget>[if (entries.isNotEmpty) _menu(row, entries)]))
          else ...<Widget>[
            if (moves) _grip(row, index),
            // At least as tall as the row's controls, so a one-line text or a
            // switch sits on their centre line; a taller line starts at the top.
            Expanded(child: Row(style: const ArcaneStyleData(minHeight: 'var(--animus-control-h)'), children: <Widget>[Expanded(child: row.line)])),
            if (entries.isNotEmpty) _menu(row, entries),
          ],
        ]),
        if (row.open && row.body != null) Padding(padding: EdgeInsets.only(left: Gap.lg.value, top: Gap.sm.value, bottom: Gap.md.value), child: row.body!),
      ],
    );
  }

  Widget _menu(OrderedRow row, List<AppMenuEntry> entries) =>
      AppMenu(icon: ArcaneIcon.ellipsis(), iconLabel: 'Actions for ${row.name}', alignEnd: true, entries: entries);

  Widget _grip(OrderedRow row, int index) => dom.span(
        classes: 'animus-rows-grip',
        attributes: <String, String>{'draggable': 'true', 'aria-hidden': 'true', 'title': 'Drag to reorder'},
        events: <String, void Function(web.Event)>{
          'dragstart': (web.Event event) {
            final web.DataTransfer? data = (event as web.DragEvent).dataTransfer;
            data?.setData('text/plain', row.key);
            data?.effectAllowed = 'move';
            final web.Element? item = (event.target as web.Element?)?.closest('li');
            if (item != null) data?.setDragImage(item, 16, 16);
            setState(() => _dragging = index);
          },
          'dragend': (web.Event _) => setState(() {
                _dragging = null;
                _over = null;
              }),
        },
        <Widget>[ArcaneIcon.gripVertical(size: IconSize.sm)],
      );
}

/// A row that opens its form: the line is a disclosure summary and [body]
/// opens under it. [label] names a line of pictures for screen readers.
class RowOpener extends StatelessWidget {
  const RowOpener({
    required this.open,
    required this.onToggle,
    required this.children,
    this.body,
    this.label,
    this.leading = const <Widget>[],
    this.trailing = const <Widget>[],
    super.key,
  });

  final bool open;
  final void Function() onToggle;
  final List<Widget> children;

  /// The row's form, shown while [open].
  final Widget? body;
  final String? label;
  final List<Widget> leading;

  /// Controls after the line, before the chevron (the row's menu).
  final List<Widget> trailing;

  /// This row with [leading] before its own and [trailing] after its own.
  RowOpener around({required List<Widget> leading, required List<Widget> trailing}) => RowOpener(
        open: open,
        onToggle: onToggle,
        body: body,
        label: label,
        leading: <Widget>[...leading, ...this.leading],
        trailing: <Widget>[...this.trailing, ...trailing],
        key: key,
        children: children,
      );

  @override
  Widget build(BuildContext context) => AppSection(
        open: open,
        onOpenChange: (bool _) => onToggle(),
        summary: <Widget>[
          ...leading,
          if (label == null) ...children,
          if (label != null) ...<Widget>[
            dom.span(classes: 'animus-sr-only', <Widget>[Component.text(label!)]),
            Expanded(child: dom.div(attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[Row(gapSize: Gap.sm, children: children)])),
          ],
          ...trailing,
        ],
        children: <Widget>[if (open) ?body],
      );
}

/// Lines of text (lore, hologram text, tooltip lines) as ordered rows: each
/// row is its text field and a "..." menu (Move up, Move down, Duplicate,
/// Delete), then one Add button.
class GuiLines extends StatelessWidget {
  const GuiLines({
    required this.label,
    required this.lines,
    required this.onChanged,
    this.mode = GuiTextMode.template,
    this.names = ExpressionNames.standard,
    this.addLabel = 'Add line',
    this.noun = 'line',
    this.placeholder,
    this.max,
    super.key,
  });

  /// Names the list ("Lore").
  final String label;
  final List<String> lines;
  final void Function(List<String> lines) onChanged;
  final GuiTextMode mode;
  final ExpressionNames names;
  final String addLabel;

  /// One row in a sentence ("line", "row").
  final String noun;
  final String? placeholder;
  final int? max;

  @override
  Widget build(BuildContext context) {
    List<String> moved(int from, int to) => <String>[for (final Object? line in moveItem(lines, from, to)) line! as String];
    return stack(<Widget>[
      if (lines.isNotEmpty)
        OrderedRows(
          label: label,
          dense: false,
          rows: <OrderedRow>[
            for (int index = 0; index < lines.length; index++)
              OrderedRow(
                key: '$index',
                name: '$noun ${index + 1}',
                line: AutocompleteInput(
                  value: lines[index],
                  profile: guiTextProfile(mode),
                  preview: mode == GuiTextMode.template ? templateSample : null,
                  gui: names,
                  placeholder: placeholder,
                  label: noun == 'line' ? '$label line ${index + 1}' : '${noun[0].toUpperCase()}${noun.substring(1)} ${index + 1}',
                  onChanged: (String value) => onChanged(<String>[...lines]..[index] = value),
                ),
              ),
          ],
          onMove: (int from, int to) => onChanged(moved(from, to)),
          onDuplicate: max != null && lines.length >= max! ? null : (int index) => onChanged(<String>[...lines]..insert(index + 1, lines[index])),
          onDelete: (int index) => onChanged(<String>[...lines]..removeAt(index)),
        ),
      ActionRow(
        label: label,
        secondary: <RowAction>[
          RowAction(addLabel, max != null && lines.length >= max! ? null : () => onChanged(<String>[...lines, '']), icon: ArcaneIcon.plus()),
        ],
      ),
    ]);
  }
}

