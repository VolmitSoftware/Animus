/// A local workspace's overview: what it holds by kind, ways to try it in
/// the browser, the documents that need repair and the files that are not
/// editor documents. A blank workspace says what to do first.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../config/docs_links.dart';
import '../logic/gui/gui_text.dart';
import '../logic/inspect/server_behaviors.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../services/browser.dart' show mainHeadingId;
import '../state/workspace_repository.dart';
import '../state/workspace_route.dart';
import 'brand_mark.dart';
import 'fold.dart';
import 'info_tip.dart';
import 'shell_sidebar.dart' show listedKinds;
import '../logic/plural.dart';

class WorkspaceOverview extends StatelessWidget {
  const WorkspaceOverview({required this.workspace, required this.ignored, required this.onNavigate, this.upgraded = const <String>[], super.key});

  final WorkspaceSnapshot workspace;

  /// Paths in the opened folder or zip that are not editor documents.
  final List<String> ignored;

  /// NPC and group files below the current schema that were upgraded as the
  /// workspace opened.
  final List<String> upgraded;
  final void Function(EditorRoute route) onNavigate;

  @override
  Widget build(BuildContext context) {
    final List<AnimusDocument> invalid = workspace.documents.where((AnimusDocument document) => !document.valid).toList();
    final bool blank = workspace.documents.isEmpty;
    return dom.div(classes: 'animus-overview', <Widget>[
      dom.section(classes: 'animus-panel', <Widget>[
        dom.h1(id: mainHeadingId, attributes: const <String, String>{'tabindex': '-1'}, <Widget>[Component.text(workspace.name)]),
        if (blank) _firstSteps() else _stats(),
      ]),
      if (!blank) _starters(),
      if (!blank)
        dom.section(classes: 'animus-panel${invalid.isEmpty ? '' : ' is-error'}', <Widget>[
          dom.h2(<Widget>[
            withTip(
              Component.text(invalid.isEmpty
                  ? 'Every document passes the shared schemas'
                  : '${invalid.length} ${invalid.length == 1 ? 'document needs' : 'documents need'} repair'),
              'The schemas the server checks every document against. A document that fails them is refused on Apply or Send.',
              docs: Docs.storage,
            ),
          ]),
          if (invalid.isNotEmpty)
            dom.ul(classes: 'animus-problem-list', <Widget>[
              for (final AnimusDocument document in invalid)
                dom.li(<Widget>[
                  dom.a(href: DocumentRoute(workspace.id, document.kind, document.id).format(), <Widget>[
                    Component.text('${document.kind.label}: ${document.id}'),
                  ]),
                  dom.span(classes: 'animus-muted', <Widget>[Component.text(_firstError(document))]),
                ]),
            ]),
        ]),
      if (upgraded.isNotEmpty)
        dom.section(classes: 'animus-panel', attributes: const <String, String>{'data-testid': 'upgraded-files'}, <Widget>[
          Fold(
            summary: <Widget>[
              withTip(
                Component.text('${upgraded.length} ${upgraded.length == 1 ? 'file was' : 'files were'} upgraded to schemaVersion 2'),
                'NPCs and trait groups from an older Animus lose the import review flag and move to the current schema as they open, as the server upgrades them. Export and Send write the upgraded files.',
                docs: Docs.schemaVersions,
              ),
            ],
            children: <Widget>[
              dom.ul(classes: 'animus-muted', <Widget>[
                for (final String path in upgraded.take(200)) dom.li(<Widget>[dom.code(<Widget>[Component.text(path)])]),
              ]),
            ],
          ),
        ]),
      if (ignored.isNotEmpty)
        dom.section(classes: 'animus-panel', <Widget>[
          Fold(
            summary: <Widget>[Component.text('${countOf(ignored.length, 'file')} ${ignored.length == 1 ? 'is' : 'are'} not ${ignored.length == 1 ? 'an editor document' : 'editor documents'}')],
            children: <Widget>[
              dom.ul(classes: 'animus-muted', <Widget>[
                for (final String path in ignored.take(200)) dom.li(<Widget>[dom.code(<Widget>[Component.text(path)])]),
              ]),
            ],
          ),
        ]),
    ]);
  }

  /// Three ways to start a blank workspace, most common first.
  Widget _firstSteps() => dom.div(classes: 'animus-empty', <Widget>[
        const BrandMark(size: 56, classes: 'animus-empty-mark'),
        dom.div(<Widget>[
          const dom.p(<Widget>[Component.text('No documents yet. Start with one of these.')]),
          dom.ol(classes: 'animus-first-steps', <Widget>[
            dom.li(<Widget>[
              Button.primary(label: 'Create an NPC', size: ButtonSize.small, onPressed: () => onNavigate(NewDocumentRoute(workspace.id, DocumentKind.npc))),
              dom.span(classes: 'animus-muted', <Widget>[
                const Component.text('Name, body and skin. '),
                dom.a(href: NewDocumentRoute(workspace.id).format(), const <Widget>[Component.text('Other kinds')]),
              ]),
            ]),
            dom.li(<Widget>[
              Button.secondary(label: 'Import', size: ButtonSize.small, onPressed: () => onNavigate(const CitizensRoute())),
              const dom.span(classes: 'animus-muted', <Widget>[Component.text('Convert a Citizens, Shopkeepers or Denizen save.')]),
            ]),
            dom.li(<Widget>[
              Button.secondary(label: 'Pair a server', size: ButtonSize.small, onPressed: () => onNavigate(SyncRoute(workspace.id))),
              const dom.span(classes: 'animus-muted', <Widget>[Component.text('Edit and send to a live server.')]),
            ]),
          ]),
        ]),
      ]);

  Widget _stats() => dom.div(classes: 'animus-stats', <Widget>[
        for (final DocumentKind kind in listedKinds)
          if (workspace.documents.any((AnimusDocument document) => document.kind == kind))
            if (kind == DocumentKind.serverBehaviors)
              dom.div(classes: 'animus-stat', <Widget>[
                dom.strong(<Widget>[Component.text('${listedServerBehaviors(workspace.documents).length}')]),
                dom.span(<Widget>[
                  withTip(const Component.text('Server behaviors listed to run'), 'Behaviors that run on the server itself, not on one NPC.', docs: Docs.serverBehaviors),
                ]),
              ])
            else
              dom.div(classes: 'animus-stat', <Widget>[
                dom.strong(<Widget>[Component.text('${workspace.documents.where((AnimusDocument document) => document.kind == kind).length}')]),
                dom.span(<Widget>[Component.text(kind.label)]),
              ]),
      ]);

  /// Ways into the playground: NPC previews and behavior dry runs.
  Widget _starters() {
    final List<AnimusDocument> npcs = <AnimusDocument>[for (final AnimusDocument document in workspace.documents) if (document.kind == DocumentKind.npc) document];
    final List<AnimusDocument> behaviors = <AnimusDocument>[for (final AnimusDocument document in workspace.documents) if (document.kind == DocumentKind.behavior) document];
    Widget links(String title, List<AnimusDocument> documents, String verb) => dom.div(classes: 'animus-try-group', <Widget>[
          dom.h3(<Widget>[Component.text(title)]),
          dom.ul(classes: 'animus-try-list', <Widget>[
            for (final AnimusDocument document in documents.take(8))
              dom.li(<Widget>[
                dom.a(href: DocumentRoute(workspace.id, document.kind, document.id, ViewMode.preview).format(), <Widget>[
                  Component.text('$verb ${document.kind == DocumentKind.npc ? guiPlainText(document.title) : document.id}'),
                ]),
              ]),
          ]),
        ]);
    return dom.section(classes: 'animus-panel animus-try', attributes: const <String, String>{'aria-labelledby': 'animus-try-title'}, <Widget>[
      dom.h2(id: 'animus-try-title', <Widget>[
        withTip(
          const Component.text('Try it in the browser'),
          'Previews draw an NPC in 3D. Dry runs step through a behavior without a server.',
          docs: Docs.dryRun,
        ),
      ]),
      dom.div(classes: 'animus-try-groups', <Widget>[
        if (npcs.isNotEmpty) links('NPC previews', npcs, 'Preview'),
        if (behaviors.isNotEmpty) links('Behavior dry runs', behaviors, 'Dry-run'),
        if (npcs.isEmpty && behaviors.isEmpty)
          dom.p(<Widget>[
            dom.a(href: NewDocumentRoute(workspace.id, DocumentKind.npc).format(), const <Widget>[Component.text('Create an NPC')]),
            const Component.text(' to preview it here.'),
          ]),
      ]),
    ]);
  }

  static String _firstError(AnimusDocument document) {
    for (final Diagnostic diagnostic in document.diagnostics) {
      if (diagnostic.severity == DiagnosticSeverity.error) return '${diagnostic.path}: ${diagnostic.message}';
    }
    return '';
  }
}
