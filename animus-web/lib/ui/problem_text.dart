/// Problems and notes as text in their severity's colour, under a control,
/// in a row's form or in a list; never in a box. `data-problem` names the
/// severity of a problem.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../model/animus_document.dart';

TextColor severityColor(DiagnosticSeverity severity) => switch (severity) {
      DiagnosticSeverity.error => TextColor.error,
      DiagnosticSeverity.warning => TextColor.warning,
      DiagnosticSeverity.info => TextColor.muted,
    };

/// A problem inside a line of text.
Widget problemText(String text, {DiagnosticSeverity severity = DiagnosticSeverity.error}) => dom.span(
      attributes: <String, String>{'data-problem': severity.name},
      <Widget>[Text(text, size: FontSize.sm, color: severityColor(severity))],
    );

/// A problem on a line of its own; `role: 'alert'` announces it.
Widget problemLine(DiagnosticSeverity severity, String text, {String? classes, String? id, String role = 'note', Map<String, String> attributes = const <String, String>{}}) => dom.div(
      id: id,
      classes: classes,
      attributes: <String, String>{...attributes, 'role': role, 'data-problem': severity.name},
      <Widget>[Text(text, size: FontSize.sm, color: severityColor(severity))],
    );

/// A muted line: what is missing, what was kept, where a step goes.
Widget noteLine(String text, {String? classes, Map<String, String> attributes = const <String, String>{}}) => dom.div(
      classes: classes,
      attributes: attributes,
      <Widget>[Text(text, size: FontSize.sm, color: TextColor.muted)],
    );
