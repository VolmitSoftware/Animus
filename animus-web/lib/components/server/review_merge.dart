/// Compare in Review: the three-way merge of one document that changed both
/// here and on the server. One block per field both sides changed (its
/// name and the two values; a Mine or Server switch once there are several),
/// then Take server, and Keep mine, Keep both or Use combined; the dialog's
/// close button cancels. Simple shows plain words and values; Advanced adds
/// the value before and the raw JSON.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/json_merge.dart';
import '../../state/live_session.dart';
import '../../ui/action_row.dart';
import '../../ui/dialog.dart';
import '../../ui/mode.dart';
import '../../ui/toggle_choice.dart';
import 'review_changes.dart';

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
        ],
      ],
    );
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
