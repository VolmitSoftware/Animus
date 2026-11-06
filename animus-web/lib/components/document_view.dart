import 'dart:convert';
import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../logic/gui/gui_json.dart';
import '../logic/gui/gui_text.dart';
import '../logic/gui/gui_presets.dart';
import '../logic/gui/text_animation.dart';
import '../logic/inspect/inspect_schemas.dart';
import '../logic/inspect/json_completion.dart';
import '../logic/inspect/server_behaviors.dart';
import '../logic/npc/route_model.dart';
import '../logic/offline/document_fixes.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../services/browser.dart';
import '../state/workspace_route.dart';
import 'gui/gui_fields.dart';
import 'tab_strip.dart';
import 'offline/dry_run_panel.dart';
import 'offline/offline_fields.dart';
import 'gui/gui_data_editors.dart';
import 'gui/hologram_editor.dart';
import 'gui/inventory_editor.dart';
import 'gui/menu_editor.dart';
import 'gui/shop_editor.dart';
import 'inspect/behavior_editor.dart';
import 'inspect/json_code_editor.dart';
import 'inspect/npc_inspector.dart';
import 'inspect/server_behaviors_editor.dart';
import 'npc/pack_editor.dart';
import 'npc/route_editor.dart';
import 'npc/skin_summary.dart';
import 'npc_preview.dart';

/// Kinds with a visual editor and a painted or 3D preview.
const Set<DocumentKind> guiEditorKinds = <DocumentKind>{DocumentKind.inventory, DocumentKind.shop, DocumentKind.menu, DocumentKind.hologram};

/// One document with its Visual, Preview, Code and Split views.
class DocumentPane extends StatefulWidget {
  const DocumentPane({
    required this.document,
    required this.view,
    required this.onView,
    required this.onSource,
    this.onJson,
    this.onRename,
    this.onDelete,
    this.onDuplicate,
    this.skinDocument,
    this.resources = const <String, Uint8List>{},
    this.editor,
    this.serverDiagnostics = const <Diagnostic>[],
    this.liveSchemas,
    this.workspaceDocuments = const <AnimusDocument>[],
    this.onOpen,
    super.key,
  });

  /// Opens another document of the workspace (a behavior, route or shop an
  /// NPC names).
  final void Function(DocumentKind kind, String id)? onOpen;

  /// Every document of the workspace, for completing references (an NPC's
  /// behaviors and skin).
  final List<AnimusDocument> workspaceDocuments;

  /// Diagnostics the connected server returned for this document's draft
  /// (validate, preview or apply), shown beside the browser's own.
  final List<Diagnostic> serverDiagnostics;

  /// The connected server's `GET /v1/schemas` map (schema name to schema),
  /// which includes loaded modules; null offline, where the embedded
  /// generated schemas apply.
  final Map<String, Object?>? liveSchemas;

  /// Visual editor edits; null keeps the document read-only in Visual.
  final JsonEdit? onJson;

  /// Moves the document to a new id; returns a problem to show, or null.
  final Future<String?> Function(String id)? onRename;

  /// Deletes the document after the user confirmed it.
  final Future<void> Function()? onDelete;

  /// Copies the document to a new id; returns a problem to show, or null.
  final Future<String?> Function(String id)? onDuplicate;

  /// Workspace resources: rig textures and the PNGs of local skins.
  final Map<String, Uint8List> resources;

  /// A structured editor (the rig editor) shown as Visual and Preview, and
  /// beside the code in Split.
  final Widget? editor;

  final AnimusDocument document;

  /// For an NPC: the skin document its `skin` names, when present.
  final AnimusDocument? skinDocument;
  final ViewMode view;
  final void Function(ViewMode view) onView;
  final Future<void> Function(String source) onSource;

  @override
  State<DocumentPane> createState() => _DocumentPaneState();
}

class _DocumentPaneState extends State<DocumentPane> {
  String? _draft;
  String? _draftError;
  String? _renaming;
  String? _renameProblem;
  void Function()? _stopGuard;

  /// True when the id field copies instead of moving.
  bool _copying = false;
  bool _confirmDelete = false;

  static const String _panelId = 'animus-view-panel';
  static const String _tabPrefix = 'animus-view-tab';
  static const String _renameInputId = 'animus-rename-input';

  static const List<TabOption<ViewMode>> _views = <TabOption<ViewMode>>[
    TabOption<ViewMode>(ViewMode.visual, 'Visual'),
    TabOption<ViewMode>(ViewMode.preview, 'Preview'),
    TabOption<ViewMode>(ViewMode.code, 'Code'),
    TabOption<ViewMode>(ViewMode.split, 'Split'),
  ];

  @override
  void initState() {
    super.initState();
    // Closing the tab with Code text that was never applied asks first.
    _stopGuard = guardUnload(() => _draft != null);
  }

  @override
  void dispose() {
    _stopGuard?.call();
    super.dispose();
  }

  void _startRename({required bool copy}) {
    setState(() {
      _renaming = copy ? '${component.document.id}-copy' : component.document.id;
      _copying = copy;
      _renameProblem = null;
    });
    focusWhenShown(_renameInputId);
  }

  void _cancelRename() {
    final bool copying = _copying;
    setState(() {
      _renaming = null;
      _renameProblem = null;
    });
    focusWhenShown(copying ? 'animus-doc-duplicate' : 'animus-doc-rename');
  }

  Future<void> _submitRename() async {
    final String? renaming = _renaming;
    if (renaming == null) return;
    final String? problem = await (_copying ? component.onDuplicate! : component.onRename!)(renaming.trim());
    if (!mounted) return;
    setState(() {
      _renameProblem = problem;
      if (problem == null) _renaming = null;
    });
    if (problem != null) focusById(_renameInputId);
  }

  void _edit(String value) {
    String? error;
    try {
      jsonDecode(value);
    } on FormatException catch (invalid) {
      error = invalid.message;
    }
    setState(() {
      _draft = value;
      _draftError = error;
    });
  }

  Future<void> _apply() async {
    final String? draft = _draft;
    if (draft == null || _draftError != null) return;
    await component.onSource(draft);
    if (mounted) setState(() => _draft = null);
  }

  @override
  Widget build(BuildContext context) {
    final AnimusDocument document = component.document;
    final ViewMode view = component.view;
    final int selectedTab = _views.indexWhere((TabOption<ViewMode> option) => option.value == view);
    return dom.div(classes: 'animus-document', <Widget>[
      dom.header(classes: 'animus-document-header', <Widget>[
        dom.div(classes: 'animus-document-title', <Widget>[
          dom.span(classes: 'animus-eyebrow', <Widget>[Component.text(document.kind.label)]),
          dom.h1(id: mainHeadingId, attributes: const <String, String>{'tabindex': '-1'}, <Widget>[Component.text(guiPlainText(document.title))]),
          dom.code(classes: 'animus-muted animus-document-path', <Widget>[Component.text(document.path)]),
        ]),
        if (component.onRename != null || component.onDelete != null || component.onDuplicate != null) _manage(document),
        TabStrip<ViewMode>(
          idPrefix: _tabPrefix,
          label: 'View',
          options: _views,
          selected: view,
          panelId: _panelId,
          onSelect: component.onView,
        ),
      ]),
      _diagnostics(document),
      dom.div(
        id: _panelId,
        classes: 'animus-views is-${view.name}${component.editor != null ? ' has-editor' : ''}',
        attributes: <String, String>{'role': 'tabpanel', 'aria-labelledby': TabStrip.tabId(_tabPrefix, selectedTab)},
        <Widget>[
        if (component.editor != null && view != ViewMode.code)
          component.editor!
        else if (view == ViewMode.visual || (view == ViewMode.split && !_staged(document)))
          _visual(document),
        if (component.editor == null && (view == ViewMode.preview || (view == ViewMode.split && _staged(document)))) _preview(document),
        if (view == ViewMode.code || view == ViewMode.split) _code(document),
      ]),
    ]);
  }

  Widget _manage(AnimusDocument document) {
    final String? renaming = _renaming;
    if (_confirmDelete && component.onDelete != null) {
      return ConfirmRow(
        question: 'Delete ${document.id}?',
        detail: 'Undo in the top bar brings it back.',
        confirmLabel: 'Delete ${document.kind == DocumentKind.npc ? 'NPC' : 'document'}',
        onConfirm: () {
          setState(() => _confirmDelete = false);
          component.onDelete!();
        },
        onCancel: () {
          setState(() => _confirmDelete = false);
          focusWhenShown('animus-doc-delete');
        },
      );
    }
    if (renaming == null) {
      return dom.div(classes: 'animus-row', attributes: const <String, String>{'role': 'group', 'aria-label': 'Manage document'}, <Widget>[
        if (component.onRename != null)
          Button.ghost(id: 'animus-doc-rename', label: 'Rename', size: ButtonSize.small, onPressed: () => _startRename(copy: false)),
        if (component.onDuplicate != null)
          Button.ghost(id: 'animus-doc-duplicate', label: 'Duplicate', size: ButtonSize.small, onPressed: () => _startRename(copy: true)),
        if (component.onDelete != null)
          Button.ghost(id: 'animus-doc-delete', label: 'Delete', size: ButtonSize.small, onPressed: () => setState(() => _confirmDelete = true)),
      ]);
    }
    return dom.div(classes: 'animus-row animus-rename', <Widget>[
      OfflineTextField(
        id: _renameInputId,
        label: _copying ? 'Id of the copy' : 'New id',
        value: renaming,
        error: _renameProblem,
        onChanged: (String value) => setState(() {
          _renaming = value;
          _renameProblem = null;
        }),
        onSubmit: _submitRename,
        onCancel: _cancelRename,
      ),
      Button.primary(label: _copying ? 'Copy' : 'Move', size: ButtonSize.small, onPressed: _submitRename),
      Button.ghost(label: 'Cancel', size: ButtonSize.small, onPressed: _cancelRename),
    ]);
  }

  Widget _diagnostics(AnimusDocument document) {
    final List<Diagnostic> server = component.serverDiagnostics;
    if (_draftError != null) {
      return const dom.p(classes: 'animus-draft-error', attributes: <String, String>{'role': 'status'}, <Widget>[
        Component.text('The draft in Code has a JSON error, so it is not applied. The saved document below is unchanged.'),
      ]);
    }
    if (document.diagnostics.isEmpty && server.isEmpty) {
      return const dom.p(classes: 'animus-ok', <Widget>[Component.text('Valid against the shared schema.')]);
    }
    // A kind without a shared schema gets one plain note, not a diagnostic
    // with a "$" path.
    final List<Diagnostic> noSchema = <Diagnostic>[for (final Diagnostic d in document.diagnostics) if (d.code == 'no-schema') d];
    if (noSchema.length == document.diagnostics.length && server.isEmpty) {
      return dom.p(classes: 'animus-muted animus-schema-note', <Widget>[Component.text(noSchema.first.message)]);
    }
    final List<DocumentFix> fixes = fixesFor(document);
    final Widget list = _diagnosticList(document, server);
    if (fixes.isEmpty) return list;
    Future<void> apply(Object? json) => component.onSource(prettyJson(json));
    return dom.div(classes: 'animus-diagnostics-block', <Widget>[
      list,
      dom.div(classes: 'animus-fixes', attributes: const <String, String>{'role': 'group', 'aria-label': 'Fixes'}, <Widget>[
        const dom.span(classes: 'animus-muted', <Widget>[Component.text('Fixes:')]),
        for (final DocumentFix fix in fixes.take(8))
          Button.secondary(
            label: fix.label,
            size: ButtonSize.small,
            attributes: <String, String>{'title': fix.path},
            onPressed: () => apply(fix.apply(document.json)),
          ),
        if (fixes.length > 1)
          Button.primary(label: 'Fix all ${fixes.length}', size: ButtonSize.small, onPressed: () => apply(applyFixes(document.json, fixes))),
      ]),
    ]);
  }

  Widget _diagnosticList(AnimusDocument document, List<Diagnostic> server) {
    return dom.ul(classes: 'animus-diagnostics', <Widget>[
      for (final Diagnostic diagnostic in document.diagnostics.where((Diagnostic d) => d.code != 'no-schema').take(50))
        dom.li(classes: 'is-${diagnostic.severity.name}', <Widget>[
          dom.code(<Widget>[Component.text(diagnostic.path)]),
          Component.text(' ${diagnostic.message}'),
        ]),
      for (final Diagnostic diagnostic in server.take(50))
        dom.li(classes: 'is-${diagnostic.severity.name} is-server', <Widget>[
          const dom.span(classes: 'animus-tag is-server', <Widget>[Component.text('server')]),
          dom.code(<Widget>[Component.text(diagnostic.path)]),
          Component.text(' ${diagnostic.message}'),
        ]),
    ]);
  }

  InspectSchemas get _schemas => InspectSchemas.from(component.liveSchemas);

  List<Diagnostic> _allDiagnostics(AnimusDocument document) => <Diagnostic>[...document.diagnostics, ...component.serverDiagnostics];

  List<String> _ids(DocumentKind kind) => <String>[
        for (final AnimusDocument other in component.workspaceDocuments)
          if (other.kind == kind) other.id,
      ];

  Widget _visual(AnimusDocument document) {
    final Object? json = document.json;
    final JsonEdit? onJson = component.onJson;
    if (json is! Map<String, Object?>) {
      return const dom.section(classes: 'animus-panel', <Widget>[
        Component.text('This document is not a JSON object, so Visual has no fields to show. Open Code to fix the text; the problems are listed above.'),
      ]);
    }
    if (document.kind == DocumentKind.skin) {
      return SkinSummary(document: document, workspaceDocuments: component.workspaceDocuments, onOpen: component.onOpen, onLoaded: () => setState(() {}));
    }
    if (onJson == null) return _fields(json);
    final List<AnimusDocument> workspace = component.workspaceDocuments;
    return switch (document.kind) {
      DocumentKind.npc => dom.div(classes: 'animus-npc-visual', <Widget>[
          NpcInspector(
            document: document,
            onJson: onJson,
            schemas: _schemas,
            diagnostics: _allDiagnostics(document),
            workspaceDocuments: workspace,
            onSkinLoaded: () => setState(() {}),
            onOpen: component.onOpen,
          ),
          _preview(document),
        ]),
      DocumentKind.behavior => BehaviorEditor(document: document, onJson: onJson, schemas: _schemas, diagnostics: _allDiagnostics(document)),
      DocumentKind.route => dom.section(classes: 'animus-panel animus-inspector', attributes: const <String, String>{'aria-label': 'Route'}, <Widget>[
          RouteEditor(
            value: json,
            path: r'$',
            diagnostics: _allDiagnostics(document),
            onChanged: (JsonMap value) => onJson(value, coalesce: 'route'),
          ),
        ]),
      DocumentKind.pack => PackEditor(document: document, schema: _schemas.pack, onJson: onJson, diagnostics: _allDiagnostics(document)),
      DocumentKind.serverBehaviors => ServerBehaviorsEditor(
          document: document,
          onJson: onJson,
          workspaceDocuments: workspace,
          onOpen: component.onOpen,
          diagnostics: <Diagnostic>[..._allDiagnostics(document), ...serverBehaviorsDiagnostics(json, workspace)],
        ),
      DocumentKind.inventory => InventoryEditor(document: document, onJson: onJson, presets: workspacePresets(workspace)),
      DocumentKind.shop => ShopEditor(document: document, onJson: onJson),
      DocumentKind.menu => MenuEditor(document: document, onJson: onJson, animations: workspaceAnimations(workspace)),
      DocumentKind.hologram => HologramEditor(document: document, onJson: onJson, animations: workspaceAnimations(workspace), presets: workspacePresets(workspace)),
      DocumentKind.textAnimation || DocumentKind.presets => guiDataEditor(document, onJson, workspace) ?? _fields(json),
      _ => _fields(json),
    };
  }

  /// Every top-level field with a one-line summary, for kinds with no editor.
  Widget _fields(JsonMap json) {
    return dom.section(classes: 'animus-panel animus-fields', <Widget>[
      dom.table(<Widget>[
        dom.tbody(<Widget>[
          for (final MapEntry<String, Object?> field in json.entries)
            dom.tr(<Widget>[
              dom.th(attributes: const <String, String>{'scope': 'row'}, <Widget>[Component.text(field.key)]),
              dom.td(<Widget>[Component.text(summarize(field.value))]),
            ]),
        ]),
      ]),
    ]);
  }

  /// The route an NPC follows: its inline navigation fields, or the route
  /// document it names when the workspace has it.
  JsonMap? _routeOf(AnimusDocument document) {
    final JsonMap navigation = asMap(asMap(asMap(document.json)['modules'])[navigationModule]);
    if (navigation.isEmpty) return null;
    final String? reference = asString(navigation['route']);
    if (reference == null) return navigation;
    for (final AnimusDocument other in component.workspaceDocuments) {
      if (other.kind == DocumentKind.route && other.id == reference) return asMap(other.json);
    }
    return null;
  }

  /// Kinds whose Preview is the 3D stage, which Split shows beside the code.
  bool _staged(AnimusDocument document) => document.kind == DocumentKind.npc && document.json is Map<String, Object?>;

  /// The rig an NPC's `rig` names, with that rig's animations.
  (AnimusDocument?, List<AnimusDocument>) _rigOf(AnimusDocument document) {
    final String? id = asString(asMap(asMap(document.json)['rig'])['id']);
    if (id == null) return (null, const <AnimusDocument>[]);
    AnimusDocument? rig;
    final List<AnimusDocument> animations = <AnimusDocument>[];
    for (final AnimusDocument other in component.workspaceDocuments) {
      if (other.kind == DocumentKind.rig && other.id == id) rig = other;
      if (other.kind == DocumentKind.animation && asMap(other.json)['rig'] == id) animations.add(other);
    }
    return (rig, animations);
  }

  Widget _preview(AnimusDocument document) {
    if (_staged(document)) {
      final (AnimusDocument? rig, List<AnimusDocument> animations) = _rigOf(document);
      return NpcPreview(
        key: ValueKey<String>('npc-preview:${document.id}'),
        document: document,
        skinDocument: component.skinDocument,
        route: _routeOf(document),
        rig: rig,
        animations: animations,
        resources: component.resources,
      );
    }
    if (document.kind == DocumentKind.behavior && document.json is Map<String, Object?>) {
      return DryRunPanel(
        key: ValueKey<String>('dry-run:${document.id}'),
        document: document,
        workspaceDocuments: component.workspaceDocuments,
        resources: component.resources,
        onOpen: component.onOpen,
      );
    }
    final Object? json = document.json;
    if (json is Map<String, Object?> && guiEditorKinds.contains(document.kind)) {
      return switch (document.kind) {
        DocumentKind.inventory => InventoryPreview(document: document),
        DocumentKind.shop => ShopPreview(document: document),
        DocumentKind.menu => MenuPreview(document: document, animations: workspaceAnimations(component.workspaceDocuments)),
        _ => HologramPreview(document: document, animations: workspaceAnimations(component.workspaceDocuments)),
      };
    }
    final Map<String, Object?> object = json is Map<String, Object?> ? json : const <String, Object?>{};
    final List<(String, String)> facts = previewFacts(document.kind, object);
    return dom.section(classes: 'animus-panel animus-preview', <Widget>[
      dom.dl(<Widget>[
        for (final (String label, String value) in facts) ...<Widget>[
          dom.dt(<Widget>[Component.text(label)]),
          dom.dd(<Widget>[Component.text(value)]),
        ],
      ]),
    ]);
  }

  Widget _code(AnimusDocument document) {
    final String text = _draft ?? document.source;
    final InspectSchemas schemas = _schemas;
    final ValueHints? hints = switch (document.kind) {
      DocumentKind.behavior => behaviorHints(guiIds: <DocumentKind, List<String>>{for (final DocumentKind kind in <DocumentKind>[DocumentKind.inventory, DocumentKind.menu, DocumentKind.shop]) kind: _ids(kind)}),
      // TODO(L3 integration): pass npcBehaviorIds(component.workspaceDocuments) from npc_catalog.dart so server behaviors drop out.
      DocumentKind.npc => npcHints(behaviorIds: _ids(DocumentKind.behavior), skinIds: _ids(DocumentKind.skin)),
      _ => null,
    };
    return dom.section(classes: 'animus-panel animus-code', <Widget>[
      JsonCodeEditor(
        text: text,
        onChanged: _edit,
        schema: schemas.forKind(document.kind),
        catalog: document.kind == DocumentKind.behavior ? schemas.catalog : null,
        hints: hints,
        diagnostics: document.diagnostics,
        serverDiagnostics: component.serverDiagnostics,
      ),
      dom.div(classes: 'animus-row', <Widget>[
        Button.primary(
          label: 'Apply to draft',
          size: ButtonSize.small,
          disabled: _draft == null || _draftError != null,
          onPressed: _apply,
        ),
        if (_draft != null)
          Button.ghost(label: 'Discard', size: ButtonSize.small, onPressed: () => setState(() => _draft = null)),
        if (_draftError != null)
          dom.span(classes: 'animus-error', <Widget>[
            Component.text('Not valid JSON yet: $_draftError. Fix the text, or Discard to go back to the saved document.'),
          ]),
      ]),
    ]);
  }
}

/// A one-line description of a JSON value for the Visual table.
String summarize(Object? value) {
  if (value is Map<String, Object?>) {
    return value.isEmpty ? '{}' : '{ ${value.keys.take(6).join(', ')}${value.length > 6 ? ', ...' : ''} }';
  }
  if (value is List<Object?>) return '${value.length} item${value.length == 1 ? '' : 's'}';
  if (value is String) return value;
  return '$value';
}

/// Labelled facts the Preview shows for a kind, until the 3D stage renders it.
List<(String, String)> previewFacts(DocumentKind kind, Map<String, Object?> json) {
  String text(Object? value) => value == null ? '-' : summarize(value);
  int count(Object? value) => value is Map<String, Object?> ? value.length : (value is List<Object?> ? value.length : 0);
  switch (kind) {
    case DocumentKind.npc:
      final Object? position = json['position'];
      final Map<String, Object?> at = position is Map<String, Object?> ? position : const <String, Object?>{};
      return <(String, String)>[
        ('Name', text(json['name'])),
        ('Type', text(json['type'])),
        ('Spawned', text(json['spawned'])),
        ('Position', '${text(at['world'])} ${text(at['x'])}, ${text(at['y'])}, ${text(at['z'])}'),
        ('Behaviors', json['behaviors'] is List<Object?> ? (json['behaviors']! as List<Object?>).join(', ') : '-'),
        ('Modules', json['modules'] is Map<String, Object?> ? (json['modules']! as Map<String, Object?>).keys.join(', ') : '-'),
      ];
    case DocumentKind.behavior:
      return <(String, String)>[
        ('Entry', text(json['entry'])),
        ('Routines', '${count(json['routines'])}'),
        ('Triggers', '${count(json['triggers'])}'),
        ('Variables', '${count(json['variables'])}'),
      ];
    case DocumentKind.serverBehaviors:
      final List<Object?> listed = json['behaviors'] is List<Object?> ? json['behaviors']! as List<Object?> : const <Object?>[];
      return <(String, String)>[
        ('Runs', listed.isEmpty ? 'nothing: the list is empty' : '${listed.length} server behavior${listed.length == 1 ? '' : 's'}'),
        ('Order', listed.isEmpty ? '-' : listed.join(', ')),
      ];
    case DocumentKind.rig:
      return <(String, String)>[
        ('Body', text(json['body'] ?? 'hidden')),
        ('Bones', '${count(json['bones'])}'),
        ('Animations', json['animations'] is Map<String, Object?> ? (json['animations']! as Map<String, Object?>).keys.join(', ') : '-'),
      ];
    case DocumentKind.animation:
      return <(String, String)>[
        ('Rig', text(json['rig'])),
        ('Duration', '${text(json['durationTicks'])} ticks'),
        ('Loop', text(json['loop'] ?? 'once')),
        ('Tracks', '${count(json['tracks'])}'),
      ];
    default:
      return <(String, String)>[for (final MapEntry<String, Object?> field in json.entries.take(8)) (field.key, text(field.value))];
  }
}
