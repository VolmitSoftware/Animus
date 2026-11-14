/// The Visual view of `server-behaviors.json`: the server behaviors that
/// run, in order, one row each with a "..." menu to open, move or remove
/// it, and one picker that adds another. Ids the workspace does not hold,
/// or that are not server behaviors, are flagged but kept.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/diagnostic_text.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/behavior_words.dart';
import '../../logic/inspect/server_behaviors.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../ui/action_row.dart';
import '../../ui/info_tip.dart';
import '../../ui/menu.dart';
import '../gui/gui_fields.dart';
import '../select/animus_select.dart';
import '../../ui/field.dart';
import '../../ui/stack.dart';
import '../../ui/problem_text.dart';

class ServerBehaviorsEditor extends StatelessWidget {
  const ServerBehaviorsEditor({required this.document, required this.onJson, required this.workspaceDocuments, this.onOpen, this.diagnostics = const <Diagnostic>[], super.key});

  final AnimusDocument document;
  final JsonEdit onJson;
  final List<AnimusDocument> workspaceDocuments;
  final void Function(DocumentKind kind, String id)? onOpen;
  final List<Diagnostic> diagnostics;

  @override
  Widget build(BuildContext context) {
    final JsonMap json = asMap(document.json);
    final List<String> listed = stringList(json['behaviors']);
    final Map<String, bool> behaviors = workspaceBehaviors(workspaceDocuments);
    final List<String> addable = <String>[for (final MapEntry<String, bool> entry in behaviors.entries) if (entry.value && !listed.contains(entry.key)) entry.key];
    void put(List<String> next) => onJson(writeAt(json, const <JsonStep>[], <String, Object?>{...json, 'schemaVersion': 1, 'behaviors': next}), coalesce: 'server-behaviors');
    List<String> moved(int from, int to) => <String>[for (final Object? id in moveItem(listed, from, to)) id! as String];
    return dom.section(classes: 'animus-server-behaviors', attributes: const <String, String>{'aria-label': 'Server behaviors list'}, <Widget>[
      stack(<Widget>[
        const dom.h2(<Widget>[
          TipLabel(
            label: Component.text('Server behaviors'),
            tip: 'Only listed behaviors run, in this order. At most $serverBehaviorsMaximum.',
            docs: Docs.serverBehaviors,
          ),
        ]),
        // A listed behavior that is not a server behavior says so on its row.
        for (final Diagnostic problem in diagnostics)
          if (problem.code != 'not-server-behavior') problemLine(problem.severity, diagnosticSentence(problem)),
        if (listed.isEmpty) noteLine(addable.isEmpty ? 'Nothing listed. Turn on Server behavior in a behavior first.' : 'Nothing listed yet.'),
        if (listed.isNotEmpty)
          dom.div(attributes: const <String, String>{'role': 'list', 'aria-label': 'Listed server behaviors'}, <Widget>[
            stack(spacing: 4, <Widget>[
              for (int index = 0; index < listed.length; index++) ...<Widget>[
                if (index > 0) const ArcaneSeparator(),
                dom.div(key: ValueKey<String>('listed-$index'), attributes: <String, String>{'role': 'listitem', 'data-behavior': listed[index]}, <Widget>[
                  ActionRow(
                    dense: true,
                    label: behaviorTitle(listed[index]),
                    leading: <Widget>[_name(listed[index], serverBehaviorNote(listed[index], behaviors))],
                    more: <AppMenuEntry>[
                      if (onOpen != null && behaviors.containsKey(listed[index])) AppMenuEntry('Open', () => onOpen!(DocumentKind.behavior, listed[index])),
                      AppMenuEntry('Move up', index == 0 ? null : () => put(moved(index, index - 1))),
                      AppMenuEntry('Move down', index == listed.length - 1 ? null : () => put(moved(index, index + 1))),
                      const AppMenuEntry.separator(),
                      AppMenuEntry('Remove from list', () => put(<String>[...listed]..removeAt(index))),
                    ],
                  ),
                ]),
              ],
            ]),
          ]),
        if (listed.length >= serverBehaviorsMaximum)
          problemLine(DiagnosticSeverity.warning, 'List full. Remove one to add another.')
        else if (addable.isNotEmpty)
          Field(
            label: 'Add behavior',
            child: AnimusSelect.values(
              references: DocumentKind.behavior,
              value: null,
              values: addable,
              labels: <String, String>{for (final String id in addable) id: behaviorTitle(id)},
              details: <String, String>{for (final String id in addable) id: id},
              noneLabel: 'Pick a server behavior',
              hiddenLabel: 'Add a server behavior',
              onChanged: (String? id) {
                if (id != null) put(<String>[...listed, id]);
              },
            ),
          )
        else if (listed.isNotEmpty)
          noteLine('All server behaviors are listed.'),
      ]),
    ]);
  }

  /// A row's name with its id under it, and what is wrong with it, if
  /// anything; the lines wrap instead of running under the row's menu.
  static Widget _name(String id, String? problem) => dom.div(styles: _wrap, <Widget>[
        stack(spacing: 2, <Widget>[
          dom.span(<Widget>[Component.text(behaviorTitle(id))]),
          Text(id, size: FontSize.sm, color: TextColor.muted, family: FontFamily.mono, element: 'code'),
          if (problem != null) noteLine(problem),
        ]),
      ]);

  static const dom.Styles _wrap = dom.Styles(raw: <String, String>{'min-width': '0', 'overflow-wrap': 'anywhere'});
}
