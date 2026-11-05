import 'dart:convert';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../logic/data_folder.dart';
import '../logic/data_folder_export.dart';
import '../logic/gui/gloss_bundle.dart';
import '../logic/gui/gui_catalog.dart';
import '../logic/gui/gui_json.dart';
import '../logic/gui/gui_templates.dart';
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

  String _fileStem(WorkspaceSnapshot workspace) {
    final String stem = workspace.name.toLowerCase().replaceAll(RegExp(r'[^a-z0-9_-]+'), '-').replaceAll(RegExp(r'^-+|-+$'), '');
    return stem.isEmpty ? 'animus' : stem;
  }

  void _exportZip(WorkspaceSnapshot workspace) {
    downloadBytes('${_fileStem(workspace)}.zip', exportDataFolderZip(workspace.documents), 'application/zip');
    final int invalid = workspace.documents.where((AnimusDocument document) => !document.valid).length;
    if (invalid > 0) {
      ArcaneSonner.warning('Exported ${workspace.documents.length} documents; $invalid still need repair and the server will refuse them.');
    } else {
      ArcaneSonner.success('Exported ${workspace.documents.length} documents as a data folder zip.');
    }
  }

  void _exportGloss(WorkspaceSnapshot workspace) {
    final GlossExport exported = writeGlossBundle(workspace.documents);
    if (exported.exported == 0) {
      ArcaneSonner.warning('No chest GUIs, menus, holograms or presets Gloss can run.${exported.skipped.isEmpty ? '' : ' Skipped: ${exported.skipped.join('; ')}'}');
      return;
    }
    downloadBytes('${_fileStem(workspace)}.gloss-workspace.json', utf8.encode(exported.bundle), 'application/json');
    ArcaneSonner.success('Exported ${exported.exported} documents as a Gloss workspace bundle.'
        '${exported.skipped.isEmpty ? '' : ' Skipped ${exported.skipped.length}: ${exported.skipped.join('; ')}'}');
  }

  Future<void> _importGloss() async {
    final (String, String)? picked = await pickTextFile();
    if (picked == null) return;
    final GlossImport imported = readGlossBundle(picked.$2);
    if (imported.error != null) {
      ArcaneSonner.error(imported.error!);
      return;
    }
    await component.store.putDocuments(<(DocumentLocation, String, String)>[
      for (final GlossImportedDocument document in imported.documents) (document.location, document.path, document.source),
    ]);
    final int invalid = imported.documents
        .where((GlossImportedDocument d) => !(component.store.document(d.location.kind, d.location.id)?.valid ?? false))
        .length;
    final String skipped = imported.skipped.isEmpty ? '' : ' Skipped: ${imported.skipped.join('; ')}.';
    if (invalid > 0) {
      ArcaneSonner.warning('Imported ${imported.documents.length} documents from ${picked.$1}; $invalid use features Animus refuses.$skipped');
    } else {
      ArcaneSonner.success('Imported ${imported.documents.length} documents from ${picked.$1}.$skipped');
    }
  }

  Future<void> _create(WorkspaceSnapshot workspace, DocumentKind kind) async {
    final Set<String> taken = <String>{for (final AnimusDocument document in workspace.documents) if (document.kind == kind) document.id};
    final String id = newDocumentId(kind, taken);
    await component.store.putDocuments(<(DocumentLocation, String, String)>[
      (DocumentLocation(kind, id), documentPath(kind, id)!, prettyJson(newDocument(kind, id))),
    ]);
    component.onNavigate(DocumentRoute(workspace.id, kind, id));
  }

  Future<String?> _rename(WorkspaceSnapshot workspace, AnimusDocument document, String id) async {
    if (id == document.id) return null;
    final String? path = documentPath(document.kind, id);
    if (path == null) {
      return document.kind == DocumentKind.inventory || document.kind == DocumentKind.shop
          ? 'Use namespace:path with a-z, 0-9, _ and -, such as town:menu.'
          : 'Use a path with a-z, 0-9, _, - and /, such as town/welcome.';
    }
    final Object? json = document.json;
    final String source = document.kind == DocumentKind.shop && json is Map<String, Object?> && json['id'] is String
        ? prettyJson(<String, Object?>{...json, 'id': id})
        : document.source;
    final bool moved = await component.store.renameDocument(document.kind, document.id, id, path, source);
    if (!moved) return '${document.kind.label}: $id already exists.';
    component.onNavigate(DocumentRoute(workspace.id, document.kind, id));
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final EditorStore store = component.store;
    final WorkspaceSnapshot? workspace = store.workspace;
    return dom.div(classes: 'animus-shell', <Widget>[
      _topbar(workspace),
      if (store.problem != null)
        dom.div(key: const ValueKey<String>('banner'), classes: 'animus-banner', attributes: const <String, String>{'role': 'alert'}, <Widget>[
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
        if (workspace != null) ...<Widget>[
          Button.secondary(
            label: 'Export zip',
            icon: ArcaneIcon.download(),
            size: ButtonSize.small,
            onPressed: () => _exportZip(workspace),
          ),
          Button.ghost(
            label: 'Import Gloss bundle',
            icon: ArcaneIcon.upload(),
            size: ButtonSize.small,
            onPressed: _importGloss,
          ),
          Button.ghost(
            label: 'Export Gloss bundle',
            size: ButtonSize.small,
            onPressed: () => _exportGloss(workspace),
          ),
        ],
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
    return dom.div(key: ValueKey<String>('workspace:${workspace.id}'), classes: 'animus-workspace', <Widget>[
      _sidebar(workspace, selected),
      dom.main_(classes: 'animus-main', <Widget>[
        if (selected != null && document != null)
          DocumentPane(
            key: ValueKey<String>('${selected.kind.wireName}:${selected.documentId}'),
            document: document,
            view: selected.view,
            onView: (ViewMode view) => component.onNavigate(selected.withView(view)),
            onSource: (String source) => component.store.editSource(document.kind, document.id, source),
            onJson: guiKinds.contains(document.kind)
                ? (Object? json, {String? coalesce}) => component.store.editJson(document.kind, document.id, json, coalesce: coalesce)
                : null,
            onRename: guiKinds.contains(document.kind) ? (String id) => _rename(workspace, document, id) : null,
            onDelete: guiKinds.contains(document.kind)
                ? () async {
                    await component.store.removeDocument(document.kind, document.id);
                    component.onNavigate(WorkspaceRoute(workspace.id));
                    ArcaneSonner.info('Deleted ${document.id}. Undo brings it back.');
                  }
                : null,
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
      dom.div(classes: 'animus-new', attributes: const <String, String>{'role': 'group', 'aria-label': 'New document'}, <Widget>[
        for (final (DocumentKind kind, String label) in const <(DocumentKind, String)>[
          (DocumentKind.inventory, 'New chest GUI'),
          (DocumentKind.shop, 'New shop'),
          (DocumentKind.menu, 'New menu'),
          (DocumentKind.hologram, 'New hologram'),
        ])
          Button.ghost(label: label, icon: ArcaneIcon.plus(size: IconSize.sm), size: ButtonSize.small, onPressed: () => _create(workspace, kind)),
      ]),
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
