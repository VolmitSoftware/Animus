/// A behavior in Simple mode: read only, in plain sentences. Each part says
/// what starts it and lists its steps as an indented outline; one alert
/// counts the problems, and "Edit in Advanced" (an outline button: the
/// page's primary stays the save bar's) switches the editor's mode to
/// change it. Nothing here writes.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/behavior_outline.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/editor_mode.dart';
import '../../ui/info_tip.dart';
import '../../ui/mode.dart';
import '../../ui/stack.dart';
import '../../ui/problem_text.dart';
import '../select/picker_scope.dart';

/// The alert line for [diagnostics] in Simple mode, or null when there is
/// nothing to say. Never a path or an engine word.
(String, AlertTone)? simpleProblemLine(List<Diagnostic> diagnostics) {
  final int errors = diagnostics.where((Diagnostic d) => d.severity == DiagnosticSeverity.error).length;
  final int warnings = diagnostics.where((Diagnostic d) => d.severity == DiagnosticSeverity.warning).length;
  if (errors > 0) return ('$errors ${errors == 1 ? 'problem' : 'problems'}. Edit in Advanced to fix ${errors == 1 ? 'it' : 'them'}.', AlertTone.error);
  if (warnings > 0) return ('$warnings ${warnings == 1 ? 'warning' : 'warnings'}. Edit in Advanced to see ${warnings == 1 ? 'it' : 'them'}.', AlertTone.warning);
  return null;
}

class BehaviorSentences extends StatelessWidget {
  const BehaviorSentences({required this.behavior, this.diagnostics = const <Diagnostic>[], super.key});

  final JsonMap behavior;
  final List<Diagnostic> diagnostics;

  @override
  Widget build(BuildContext context) {
    final List<OutlinePart> parts = behaviorOutline(behavior, names: (DocumentKind kind, String id) => PickerScope.nameIn(context, kind, id));
    final (String, AlertTone)? problems = simpleProblemLine(diagnostics);
    return dom.section(attributes: const <String, String>{'aria-label': 'What this behavior does', 'data-testid': 'behavior-read'}, <Widget>[
      stack(spacing: 24, <Widget>[
        if (problems != null) AppAlert(message: problems.$1, tone: problems.$2),
        if (behavior['server'] == true) noteLine('Runs on the server, not on an NPC.'),
        for (int index = 0; index < parts.length; index++) _part(parts[index], first: index == 0),
        FormFooter(
          secondary: <RowAction>[
            RowAction('Edit in Advanced', () => ModeScope.maybeOf(context)?.onChanged(EditorMode.advanced), attributes: const <String, String>{'data-testid': 'edit-in-advanced'}),
          ],
        ),
      ]),
    ]);
  }

  /// A part: its name, what runs it, then its steps indented by depth.
  static Widget _part(OutlinePart part, {required bool first}) => stack(spacing: 4, <Widget>[
        dom.h3(<Widget>[
          if (first)
            TipLabel(
              label: Component.text(part.title),
              tip: 'Read only in Simple. Advanced shows every step, ready to change.',
              docs: Docs.behaviors,
            )
          else
            Component.text(part.title),
        ]),
        if (part.runs case final String runs) noteLine(runs),
        if (part.lines.isEmpty)
          noteLine('No steps yet.')
        else
          dom.div(attributes: const <String, String>{'role': 'list'}, <Widget>[
            for (final OutlineLine line in part.lines)
              dom.div(
                attributes: const <String, String>{'role': 'listitem'},
                styles: dom.Styles(raw: <String, String>{'padding-inline-start': '${line.depth * 1.25}rem'}),
                <Widget>[Component.text(line.text)],
              ),
          ]),
      ]);
}
