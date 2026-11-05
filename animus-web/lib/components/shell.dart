import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../services/browser.dart';
import '../state/editor_store.dart';
import '../state/workspace_repository.dart';
import '../state/workspace_route.dart';
import 'document_view.dart';

/// Kinds listed in the sidebar, in this order.
const List<DocumentKind> listedKinds = <DocumentKind>[
  DocumentKind.npc,
  DocumentKind.behavior,
  DocumentKind.pack,
  DocumentKind.shop,
  DocumentKind.inventory,
  DocumentKind.menu,
  DocumentKind.hologram,
  DocumentKind.rig,
  DocumentKind.animation,
  DocumentKind.route,
  DocumentKind.skin,
  DocumentKind.textAnimation,
  DocumentKind.presets,
];

class EditorShell extends StatefulWidget {
  const EditorShell({
    required this.store,
    required this.route,
    required this.brightness,
    required this.onToggleTheme,
    required this.onNavigate,
    super.key,
  });

  final EditorStore store;
  final EditorRoute route;
  final Brightness brightness;
  final void Function() onToggleTheme;
  final void Function(EditorRoute route) onNavigate;

  @override
  State<EditorShell> createState() => _EditorShellState();
}

class _EditorShellState extends State<EditorShell> {
  bool _busy = false;
  String _filter = '';

  Future<void> _open(Future<PickedFiles?> Function() pick) async {
    setState(() => _busy = true);
    try {
      final PickedFiles? picked = await pick();
      if (picked == null) return;
      final WorkspaceSnapshot snapshot = await component.store.openFiles(picked.name, picked.files);
      final int invalid = snapshot.documents.where((AnimusDocument document) => !document.valid).length;
      if (snapshot.documents.isEmpty) {
        ArcaneSonner.warning('No Animus documents found. Pick the plugins/Animus folder or a zip of it.');
      } else if (invalid > 0) {
        ArcaneSonner.warning('Opened ${snapshot.documents.length} documents; $invalid need repair.');
      } else {
        ArcaneSonner.success('Opened ${snapshot.documents.length} documents.');
      }
      component.onNavigate(WorkspaceRoute(snapshot.id));
    } on Object catch (failure) {
      ArcaneSonner.error('Could not open the files: $failure');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final EditorStore store = component.store;
    final WorkspaceSnapshot? workspace = store.workspace;
    return dom.div(classes: 'animus-shell', <Widget>[
      _topbar(workspace),
      if (store.problem != null)
        dom.div(classes: 'animus-banner', attributes: const <String, String>{'role': 'alert'}, <Widget>[
          Component.text(store.problem!),
        ]),
      if (workspace == null) _home(store) else _workspace(workspace),
    ]);
  }

  Widget _topbar(WorkspaceSnapshot? workspace) {
    final bool dark = component.brightness == Brightness.dark;
    return dom.header(classes: 'animus-topbar', <Widget>[
      const dom.a(href: '#/', classes: 'animus-brand', <Widget>[
        dom.span(classes: 'animus-brand-mark', <Widget>[Component.text('A')]),
        dom.span(<Widget>[Component.text('Animus Editor')]),
      ]),
      if (workspace != null)
        dom.span(classes: 'animus-topbar-workspace', <Widget>[Component.text(workspace.name)]),
      dom.div(classes: 'animus-topbar-actions', <Widget>[
        Button.secondary(
          label: 'Open folder',
          icon: ArcaneIcon.folderOpen(),
          size: ButtonSize.small,
          disabled: _busy,
          onPressed: () => _open(pickDirectory),
        ),
        Button.secondary(
          label: 'Open zip',
          icon: ArcaneIcon.fileArchive(),
          size: ButtonSize.small,
          disabled: _busy,
          onPressed: () => _open(pickZip),
        ),
        if (workspace != null)
          Button.ghost(
            label: 'Undo',
            size: ButtonSize.small,
            disabled: !component.store.canUndo,
            onPressed: component.store.undo,
          ),
        Button.ghost(
          icon: dark ? ArcaneIcon.sun() : ArcaneIcon.moon(),
          size: ButtonSize.small,
          attributes: <String, String>{'aria-label': dark ? 'Use light theme' : 'Use dark theme'},
          onPressed: component.onToggleTheme,
        ),
      ]),
    ]);
  }

  Widget _home(EditorStore store) {
    return dom.main_(classes: 'animus-home', <Widget>[
      dom.section(classes: 'animus-panel animus-home-intro', <Widget>[
        const dom.h1(<Widget>[Component.text('Open an Animus data folder')]),
        const dom.p(<Widget>[
          Component.text('Pick plugins/Animus (config/animus on mod servers) or a zip of it. '
              'NPCs, behaviors, shops and GUIs are checked against the shared schemas in this browser. '
              'Nothing is uploaded.'),
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          Button.primary(label: 'Open folder', icon: ArcaneIcon.folderOpen(), disabled: _busy, onPressed: () => _open(pickDirectory)),
          Button.secondary(label: 'Open zip', icon: ArcaneIcon.fileArchive(), disabled: _busy, onPressed: () => _open(pickZip)),
        ]),
      ]),
      dom.section(classes: 'animus-panel', <Widget>[
        const dom.h2(<Widget>[Component.text('Workspaces in this browser')]),
        if (store.workspaces.isEmpty)
          const dom.p(classes: 'animus-muted', <Widget>[Component.text('None yet. Opened folders are kept here as local workspaces.')])
        else
          dom.ul(classes: 'animus-workspace-list', <Widget>[
            for (final WorkspaceSummary summary in store.workspaces)
              dom.li(<Widget>[
                dom.a(href: WorkspaceRoute(summary.id).format(), <Widget>[Component.text(summary.name)]),
                dom.span(classes: 'animus-muted', <Widget>[Component.text('${summary.documentCount} documents')]),
                Button.ghost(label: 'Remove', size: ButtonSize.small, onPressed: () => store.deleteWorkspace(summary.id)),
              ]),
          ]),
      ]),
    ]);
  }

  Widget _workspace(WorkspaceSnapshot workspace) {
    final EditorRoute route = component.route;
    final DocumentRoute? selected = route is DocumentRoute ? route : null;
    final AnimusDocument? document = selected == null ? null : component.store.document(selected.kind, selected.documentId);
    return dom.div(classes: 'animus-workspace', <Widget>[
      _sidebar(workspace, selected),
      dom.main_(classes: 'animus-main', <Widget>[
        if (selected != null && document != null)
          DocumentPane(
            key: ValueKey<String>('${selected.kind.wireName}:${selected.documentId}'),
            document: document,
            view: selected.view,
            onView: (ViewMode view) => component.onNavigate(selected.withView(view)),
            onSource: (String source) => component.store.editSource(document.kind, document.id, source),
            skinDocument: _skinOf(document),
          )
        else if (selected != null)
          dom.section(classes: 'animus-panel', <Widget>[
            Component.text('${selected.kind.label}: ${selected.documentId} is not in this workspace.'),
          ])
        else
          _overview(workspace),
      ]),
    ]);
  }

  Widget _sidebar(WorkspaceSnapshot workspace, DocumentRoute? selected) {
    final String filter = _filter.trim().toLowerCase();
    bool matches(AnimusDocument document) =>
        filter.isEmpty || document.id.toLowerCase().contains(filter) || document.title.toLowerCase().contains(filter);
    return dom.aside(classes: 'animus-sidebar', <Widget>[
      TextInput(
        placeholder: 'Search documents',
        value: _filter,
        onChanged: (String value) => setState(() => _filter = value),
      ),
      for (final DocumentKind kind in listedKinds)
        if (workspace.documents.any((AnimusDocument document) => document.kind == kind))
          dom.section(classes: 'animus-tree-group', <Widget>[
            dom.h3(<Widget>[
              Component.text(kind.label),
              dom.span(classes: 'animus-count', <Widget>[
                Component.text('${workspace.documents.where((AnimusDocument document) => document.kind == kind).length}'),
              ]),
            ]),
            dom.ul(attributes: const <String, String>{'role': 'list'}, <Widget>[
              for (final AnimusDocument document in workspace.documents)
                if (document.kind == kind && matches(document))
                  dom.li(<Widget>[
                    dom.a(
                      href: DocumentRoute(workspace.id, kind, document.id, selected?.view ?? ViewMode.visual).format(),
                      classes: 'animus-tree-item${selected?.kind == kind && selected?.documentId == document.id ? ' is-selected' : ''}',
                      <Widget>[
                        dom.span(classes: 'animus-tree-title', <Widget>[Component.text(document.title)]),
                        if (document.title != document.id)
                          dom.span(classes: 'animus-tree-id', <Widget>[Component.text(document.id)]),
                        if (!document.valid)
                          dom.span(classes: 'animus-badge is-error', <Widget>[Component.text('${errorCount(document)}')]),
                      ],
                    ),
                  ]),
            ]),
          ]),
    ]);
  }

  Widget _overview(WorkspaceSnapshot workspace) {
    final List<AnimusDocument> invalid = workspace.documents.where((AnimusDocument document) => !document.valid).toList();
    return dom.div(classes: 'animus-overview', <Widget>[
      dom.section(classes: 'animus-panel', <Widget>[
        dom.h2(<Widget>[Component.text(workspace.name)]),
        dom.div(classes: 'animus-stats', <Widget>[
          for (final DocumentKind kind in listedKinds)
            if (workspace.documents.any((AnimusDocument document) => document.kind == kind))
              dom.div(classes: 'animus-stat', <Widget>[
                dom.strong(<Widget>[Component.text('${workspace.documents.where((AnimusDocument document) => document.kind == kind).length}')]),
                dom.span(<Widget>[Component.text(kind.label)]),
              ]),
        ]),
        if (workspace.documents.isEmpty)
          const dom.p(classes: 'animus-muted', <Widget>[Component.text('This workspace has no documents.')]),
      ]),
      dom.section(classes: 'animus-panel', <Widget>[
        dom.h2(<Widget>[Component.text(invalid.isEmpty ? 'Every document passes the shared schemas' : '${invalid.length} documents need repair')]),
        if (invalid.isNotEmpty)
          dom.ul(classes: 'animus-problem-list', <Widget>[
            for (final AnimusDocument document in invalid)
              dom.li(<Widget>[
                dom.a(href: DocumentRoute(workspace.id, document.kind, document.id).format(), <Widget>[
                  Component.text('${document.kind.label}: ${document.id}'),
                ]),
                dom.span(classes: 'animus-muted', <Widget>[Component.text(firstError(document))]),
              ]),
          ]),
      ]),
      if (component.store.ignored.isNotEmpty)
        dom.section(classes: 'animus-panel', <Widget>[
          dom.details(<Widget>[
            dom.summary(<Widget>[Component.text('${component.store.ignored.length} files are not editor documents')]),
            dom.ul(classes: 'animus-muted', <Widget>[
              for (final String path in component.store.ignored.take(200)) dom.li(<Widget>[dom.code(<Widget>[Component.text(path)])]),
            ]),
          ]),
        ]),
    ]);
  }

  AnimusDocument? _skinOf(AnimusDocument document) {
    final Object? json = document.json;
    if (document.kind != DocumentKind.npc || json is! Map<String, Object?> || json['skin'] is! String) return null;
    return component.store.document(DocumentKind.skin, json['skin']! as String);
  }
}

int errorCount(AnimusDocument document) =>
    document.diagnostics.where((Diagnostic diagnostic) => diagnostic.severity == DiagnosticSeverity.error).length;

String firstError(AnimusDocument document) {
  for (final Diagnostic diagnostic in document.diagnostics) {
    if (diagnostic.severity == DiagnosticSeverity.error) return '${diagnostic.path}: ${diagnostic.message}';
  }
  return '';
}
