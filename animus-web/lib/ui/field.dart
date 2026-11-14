/// One form row on the library's shadcn Field: the label sits beside the
/// control from 28rem and above it when narrower. Problems wait while the
/// control is being typed in ([TypingPause]).
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../components/typing_pause.dart';
import '../config/docs_links.dart';
import '../logic/diagnostic_text.dart';
import '../logic/help_text.dart';
import '../model/animus_document.dart';
import 'info_tip.dart';
import 'mode.dart';
import 'problem_text.dart';

/// The label column beside the control, so a form's controls line up.
const String fieldLabelWidth = '11.25rem';

class Field extends StatefulWidget {
  const Field({
    required this.label,
    required this.child,
    this.hint,
    this.help,
    this.tip,
    this.docs,
    this.problems = const <Diagnostic>[],
    this.error,
    this.forId,
    this.required = false,
    super.key,
  });

  /// One to three words, sentence case.
  final String label;
  final Widget child;

  /// A few words under the control. Anything longer belongs in [tip].
  final String? hint;

  /// Help of any length (schema descriptions): up to six words stay under
  /// the control, longer help goes behind the (i). With [tip] it stays under.
  final String? help;

  /// One or two sentences behind the (i) button.
  final String? tip;
  final DocLink? docs;

  /// The document's diagnostics at this field.
  final List<Diagnostic> problems;

  /// A problem the form found itself (a taken id), shown like an error.
  final String? error;

  /// The control's element id; the label becomes `<label for>`. Without it
  /// the label is a title, for groups and controls that name themselves.
  final String? forId;

  /// Marks the label as required.
  final bool required;

  @override
  State<Field> createState() => _FieldState();
}

class _FieldState extends State<Field> with TypingPause<Field> {
  @override
  Widget build(BuildContext context) {
    final List<Diagnostic> problems = typing ? const <Diagnostic>[] : withoutRepeats(component.problems);
    final String? error = typing ? null : component.error;
    final bool advanced = ModeScope.advanced(context);
    final List<String> errors = <String>[?error, for (final Diagnostic d in problems) if (d.severity == DiagnosticSeverity.error) diagnosticSentenceIn(d, advanced: advanced)];
    final ({String? hint, String? tip}) help = component.tip == null ? splitHelp(component.help) : (hint: component.help, tip: null);
    final String? hint = component.hint ?? help.hint, tip = component.tip ?? help.tip;
    final List<Widget> trailing = <Widget>[if (tip != null && tip.trim().isNotEmpty) InfoButton(tip: tip, docs: component.docs)];
    // The asterisk is for the eye; the control carries "required" itself, so
    // the label still names it "Name", not "Name *".
    final Widget label = component.forId != null
        ? ArcaneFieldLabel(component.label, forId: component.forId, required: component.required, trailing: trailing)
        : ArcaneFieldTitle.child(dom.span(<Widget>[Component.text(component.label)]), required: component.required, trailing: trailing);
    return dom.div(events: typingEvents, <Widget>[
      ArcaneFieldGroup(styles: _row, children: <Widget>[
        ArcaneFormField(
          orientation: FieldOrientation.responsive,
          labelWidth: fieldLabelWidth,
          controlId: component.forId,
          invalid: errors.isNotEmpty,
          children: <Widget>[
            label,
            ArcaneFieldContent(children: <Widget>[
              // A switch or checkbox is shorter than a text box: it sits
              // centred on the label's line instead of at its top.
              if (component.child is ArcaneToggleSwitch || component.child is ArcaneCheckbox) dom.div(styles: _controlLine, <Widget>[component.child]) else component.child,
              if (hint != null && hint.trim().isNotEmpty) ArcaneFieldDescription(hint),
              if (errors.isNotEmpty) ArcaneFieldError.list(errors),
              for (final Diagnostic problem in problems)
                if (problem.severity != DiagnosticSeverity.error) ArcaneFieldDescription.child(problemText(diagnosticSentenceIn(problem, advanced: advanced), severity: problem.severity)),
            ]),
          ],
        ),
      ]),
    ]);
  }
}

/// The room above and below a row, so rows in a stack keep apart.
const ArcaneStyleData _row = ArcaneStyleData(paddingStringCustom: '0.375rem 0');

const dom.Styles _controlLine = dom.Styles(raw: <String, String>{'display': 'flex', 'align-items': 'center', 'min-height': 'var(--animus-control-h)'});
