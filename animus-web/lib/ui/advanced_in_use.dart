/// Simple's one line for a tab whose hidden settings change what the NPC or
/// document does: "3 advanced settings" (", 1 with a problem") and Show,
/// which opens them on this page without changing the mode. Nothing when
/// [count] is 0. Callers count only values that differ from the defaults
/// and change behaviour; import bookkeeping never counts.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../logic/plural.dart';
import 'action_row.dart';
import 'stack.dart';

class AdvancedInUse extends StatelessWidget {
  const AdvancedInUse({required this.count, required this.onShow, this.problems = 0, this.expanded, super.key});

  final int count;

  /// How many of them have a problem.
  final int problems;
  final void Function() onShow;

  /// Whether Show already opened them (the button then reads Hide).
  final bool? expanded;

  @override
  Widget build(BuildContext context) {
    if (count <= 0) return const Component.fragment(<Widget>[]);
    final String trouble = problems == 0 ? '' : ', ${problems == 1 ? '1 with a problem' : '$problems with problems'}';
    return dom.div(attributes: const <String, String>{'data-advanced-in-use': ''}, <Widget>[
      ActionRow(
        dense: true,
        label: 'Advanced settings',
        leading: <Widget>[mutedText('${countOf(count, 'advanced setting')}$trouble')],
        secondary: <RowAction>[RowAction(expanded == true ? 'Hide' : 'Show', onShow, expanded: expanded)],
      ),
    ]);
  }
}
