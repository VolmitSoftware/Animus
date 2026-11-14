/// The Code view (Advanced): the document's JSON in its editor frame, each
/// problem listed once by line, and a footer that puts the typed code into
/// the document or drops it. "Use this code" only changes the document
/// here; the save bar still applies it.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/inspect/completion_catalog.dart';
import '../../logic/inspect/json_completion.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../model/animus_document.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import 'json_code_editor.dart';

class CodeView extends StatelessWidget {
  const CodeView({
    required this.text,
    required this.onChanged,
    required this.onUse,
    required this.onDiscard,
    this.schema,
    this.catalog,
    this.hints,
    this.diagnostics = const <Diagnostic>[],
    this.serverDiagnostics = const <Diagnostic>[],
    this.refusal,
    super.key,
  });

  final String text;
  final void Function(String text) onChanged;

  /// Puts the typed code into the document; null while there is nothing
  /// to use or it does not parse.
  final void Function()? onUse;

  /// Drops the typed code; null when nothing was typed.
  final void Function()? onDiscard;

  final SchemaView? schema;
  final CompletionCatalog? catalog;
  final ValueHints? hints;
  final List<Diagnostic> diagnostics;
  final List<Diagnostic> serverDiagnostics;

  /// Why parsed code still cannot be used (a recording rule it breaks).
  final String? refusal;

  @override
  Widget build(BuildContext context) => dom.div(
        classes: 'animus-code',
        attributes: const <String, String>{'data-testid': 'code-view'},
        <Widget>[
          Column(crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, gapSize: Gap.md, children: <Widget>[
          JsonCodeEditor(
            text: text,
            onChanged: onChanged,
            schema: schema,
            catalog: catalog,
            hints: hints,
            diagnostics: diagnostics,
            serverDiagnostics: serverDiagnostics,
          ),
          if (refusal != null)
            dom.div(attributes: const <String, String>{'data-recording': 'code-refusal'}, <Widget>[
              AppAlert(tone: AlertTone.error, message: 'Not used: $refusal', live: true),
            ]),
          FormFooter(
            cancel: onDiscard == null ? null : RowAction('Discard', onDiscard),
            primary: RowAction('Use this code', onUse, attributes: const <String, String>{'data-testid': 'code-use'}),
          ),
          ]),
        ],
      );
}
