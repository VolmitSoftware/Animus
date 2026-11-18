/// Compare in Review: the three-way merge of one file that changed both
/// here and on the server. One block per field both sides changed (its
/// name and the two values; a Mine or Server switch once there are several),
/// then the fields the merge takes by itself, from the server and from you,
/// then Take server, and Keep mine, Keep both or Use combined; the dialog's
/// close button cancels. Simple shows plain words and values; Advanced adds
/// the value before and the raw JSON. [fieldList] is also Review's list of a
/// changed file's fields.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/json_merge.dart';
import '../../model/server_binding.dart' show ServerBase;
import '../../state/live_session.dart';
import '../../ui/action_row.dart';
import '../../ui/dialog.dart';
import '../../ui/mode.dart';
import '../../ui/toggle_choice.dart';
import '../offline/page_parts.dart';
import 'review_changes.dart';

/// Field lines old to new, at most [reviewFieldLimit] and then "and n more",
/// each cut to one row with the whole line as its tooltip; null when none.
Widget? fieldList(List<FieldChange> changes, {required bool advanced, required String label, String? testId}) {
  final ({List<String> rows, int more}) shown = fieldRows(changes, advanced: advanced);
  if (shown.rows.isEmpty) return null;
  return rowList(
    label: label,
    spacing: 2,
    attributes: <String, String>{'data-testid': ?testId},
    <Widget>[
      for (final String row in shown.rows) listItem(_fieldLine(row, mono: advanced)),
      if (shown.more > 0) listItem(_fieldLine(moreFieldsLine(shown.more), mono: false)),
    ],
  );
}

Widget _fieldLine(String text, {required bool mono}) => dom.div(attributes: <String, String>{'title': text}, <Widget>[
      Text(text, size: FontSize.sm, color: TextColor.muted, family: mono ? FontFamily.mono : null, overflow: TextOverflow.ellipsis, maxLines: 1),
    ]);

class MergeDialog extends StatelessWidget {
  const MergeDialog({required this.session, required this.view, required this.name, required this.onClose, required this.onTakeServer, super.key});

  final LiveSession session;
  final ConflictView? view;
  final String name;
  final void Function() onClose;

  /// Asks before the server's copy replaces this one.
  final void Function(ConflictView view) onTakeServer;

  @override
  Widget build(BuildContext context) {
    final ConflictView? view = this.view;
    final bool advanced = ModeScope.advanced(context);
    final List<MergeConflict> both = view?.merge.conflicts ?? const <MergeConflict>[];
    final ({String line, String primary, bool pick}) choice = compareChoice(
      serverDeleted: view != null && view.server == null,
      conflicts: both.length,
      field: both.length == 1 ? fieldLabel(both.single.segments) : null,
    );
    return AppDialog(
      id: 'review-compare',
      open: view != null,
      wide: true,
      title: 'Compare $name',
      onClose: onClose,
      footer: view == null
          ? null
          : DialogFooter(
              secondary: <RowAction>[RowAction('Take server', () => onTakeServer(view))],
              primary: RowAction(choice.primary, () {
                onClose();
                session.resolve(view.key);
              }),
            ),
      children: <Widget>[
        if (view != null) ...<Widget>[
          dom.p(<Widget>[Component.text(choice.line)]),
          for (final MergeConflict conflict in both) _block(view, conflict, advanced, pick: choice.pick),
          ..._taken(view, advanced),
        ],
      ],
    );
  }

  /// The fields the merge takes without asking: those only the server
  /// changed, then those only you changed. Nothing without the server copy
  /// this file started from.
  List<Widget> _taken(ConflictView view, bool advanced) {
    final ServerBase? base = view.base;
    if (base == null) return const <Widget>[];
    final ({List<FieldChange> server, List<FieldChange> mine}) fields = mergedFields(base.source, view.mine, view.server?.source, view.merge.conflicts);
    Widget part(String title, List<FieldChange> changes, String testId) => dom.section(attributes: <String, String>{'aria-label': title, 'data-testid': testId}, <Widget>[
          Column(spacing: 6, crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
            Text(title, weight: FontWeight.w600),
            ?fieldList(changes, advanced: advanced, label: title),
          ]),
        ]);
    return <Widget>[
      if (fields.server.isNotEmpty) part('Changed on the server', fields.server, 'merge-server'),
      if (fields.mine.isNotEmpty) part('Changed by you', fields.mine, 'merge-mine'),
    ];
  }

  /// One field both sides changed: its name, the values, and with [pick]
  /// which to keep.
  Widget _block(ConflictView view, MergeConflict conflict, bool advanced, {required bool pick}) {
    Widget value(String side, Object? raw) => Text(
          '$side: ${advanced ? shortJson(raw) : plainValue(raw)}',
          size: FontSize.sm,
          color: TextColor.muted,
          family: advanced ? FontFamily.mono : null,
        );
    return dom.section(attributes: <String, String>{'aria-label': fieldLabel(conflict.segments), 'data-merge-field': conflict.path}, <Widget>[
      Column(spacing: 6, crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
        Text(advanced ? conflict.path : fieldLabel(conflict.segments), weight: FontWeight.w600, family: advanced ? FontFamily.mono : null),
        if (advanced) value('Before', conflict.base),
        value('Mine', conflict.mine),
        value('Server', conflict.server),
        if (pick)
          ToggleChoice<MergeSide>(
            label: 'Keep for ${fieldPath(conflict.segments)}',
            value: view.choices[conflict.path] ?? MergeSide.mine,
            options: const <ChoiceOption<MergeSide>>[ChoiceOption<MergeSide>(MergeSide.mine, 'Mine'), ChoiceOption<MergeSide>(MergeSide.server, 'Server')],
            onChanged: (MergeSide side) => session.choose(view.key, conflict.path, side),
          ),
      ]),
    ]);
  }
}
