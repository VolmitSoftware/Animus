/// Every document of one kind, where a dropdown's "Manage" row leads: each
/// one with its id and problems, and New for another.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_text.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../services/browser.dart' show mainHeadingId;
import '../../state/workspace_repository.dart';
import '../../state/workspace_route.dart';
import '../select/picker_scope.dart';
import '../shell_sidebar.dart' show errorCount;

class KindListPage extends StatelessWidget {
  const KindListPage({required this.workspace, required this.kind, super.key});

  final WorkspaceSnapshot workspace;
  final DocumentKind kind;

  @override
  Widget build(BuildContext context) {
    final List<AnimusDocument> documents = <AnimusDocument>[
      for (final AnimusDocument document in workspace.documents)
        if (document.kind == kind) document,
    ]..sort((AnimusDocument a, AnimusDocument b) => a.id.compareTo(b.id));
    return dom.section(classes: 'animus-panel animus-doc-list', attributes: <String, String>{'aria-labelledby': mainHeadingId, 'data-kind': kind.wireName}, <Widget>[
      dom.div(classes: 'animus-doc-list-head', <Widget>[
        dom.h1(id: mainHeadingId, attributes: const <String, String>{'tabindex': '-1'}, <Widget>[
          Component.text(kind.label),
          dom.span(classes: 'animus-count', <Widget>[Component.text('${documents.length}')]),
        ]),
        Button.secondary(label: 'New ${kindNoun(kind)}', size: ButtonSize.small, href: NewDocumentRoute(workspace.id, kind).format()),
      ]),
      if (documents.isEmpty)
        dom.p(classes: 'animus-muted', <Widget>[Component.text('No ${kindPlural(kind)} in this workspace yet.')])
      else
        dom.ul(classes: 'animus-doc-list-items', attributes: const <String, String>{'role': 'list'}, <Widget>[
          for (final AnimusDocument document in documents)
            dom.li(attributes: <String, String>{'data-id': document.id}, <Widget>[
              dom.a(href: DocumentRoute(workspace.id, kind, document.id).format(), <Widget>[Component.text(guiPlainText(document.title))]),
              if (document.title != document.id) dom.code(classes: 'animus-muted', <Widget>[Component.text(document.id)]),
              if (!document.valid)
                dom.span(classes: 'animus-badge is-error', <Widget>[
                  Component.text('${errorCount(document)} problem${errorCount(document) == 1 ? '' : 's'}'),
                ]),
            ]),
        ]),
    ]);
  }
}
