import 'dart:convert';
import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../logic/gui/gui_json.dart';
import '../logic/gui/text_animation.dart';
import '../logic/inspect/inspect_schemas.dart';
import '../logic/inspect/json_completion.dart';
import '../logic/npc/route_model.dart';
import '../logic/offline/document_fixes.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../state/workspace_route.dart';
import 'gui/gui_fields.dart';
import 'offline/dry_run_panel.dart';
import 'offline/offline_fields.dart';
import 'gui/hologram_editor.dart';
import 'gui/inventory_editor.dart';
import 'gui/menu_editor.dart';
import 'gui/shop_editor.dart';
import 'inspect/behavior_editor.dart';
import 'inspect/json_code_editor.dart';
import 'inspect/npc_inspector.dart';
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

  /// True when the id field copies instead of moving.
  bool _copying = false;
  bool _confirmDelete = false;

  static const Map<ViewMode, String> _labels = <ViewMode, String>{
    ViewMode.visual: 'Visual',
    ViewMode.preview: 'Preview',
    ViewMode.code: 'Code',
    ViewMode.split: 'Split',
  };

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
    return dom.div(classes: 'animus-document', <Widget>[
      dom.header(classes: 'animus-document-header', <Widget>[
        dom.div(<Widget>[
          dom.span(classes: 'animus-eyebrow', <Widget>[Component.text(document.kind.label)]),
          dom.h2(<Widget>[Component.text(document.title)]),
          dom.code(classes: 'animus-muted', <Widget>[Component.text(document.path)]),
        ]),
        if (component.onRename != null || component.onDelete != null || component.onDuplicate != null) _manage(document),
        dom.div(classes: 'animus-segmented', attributes: const <String, String>{'role': 'tablist'}, <Widget>[
          for (final ViewMode option in ViewMode.values)
            Button(
              label: _labels[option],
              variant: option == view ? ButtonVariant.primary : ButtonVariant.ghost,
              size: ButtonSize.small,
              attributes: <String, String>{'role': 'tab', 'aria-selected': '${option == view}'},
              onPressed: () => component.onView(option),
            ),
        ]),
      ]),
      _diagnostics(document),
      dom.div(classes: 'animus-views is-${view.name}${component.editor != null ? ' has-editor' : ''}', <Widget>[
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
        onCancel: () => setState(() => _confirmDelete = false),
      );
    }
    if (renaming == null) {
      return dom.div(classes: 'animus-row', attributes: const <String, String>{'role': 'group', 'aria-label': 'Manage document'}, <Widget>[
        if (component.onRename != null)
          Button.ghost(label: 'Rename', size: ButtonSize.small, onPressed: () => setState(() {
            _renaming = document.id;
            _copying = false;
          })),
        if (component.onDuplicate != null)
          Button.ghost(label: 'Duplicate', size: ButtonSize.small, onPressed: () => setState(() {
            _renaming = '${document.id}-copy';
            _copying = true;
          })),
        if (component.onDelete != null) Button.ghost(label: 'Delete', size: ButtonSize.small, onPressed: () => setState(() => _confirmDelete = true)),
      ]);
    }
    return dom.div(classes: 'animus-row animus-rename', <Widget>[
      GuiTextInput(value: renaming, label: _copying ? 'Id of the copy' : 'New id', onChanged: (String value) => setState(() => _renaming = value)),
      Button.primary(
        label: _copying ? 'Copy' : 'Move',
        size: ButtonSize.small,
        onPressed: () async {
          final String? problem = await (_copying ? component.onDuplicate! : component.onRename!)(renaming.trim());
          if (!mounted) return;
          setState(() {
            _renameProblem = problem;
            if (problem == null) _renaming = null;
          });
        },
      ),
      Button.ghost(label: 'Cancel', size: ButtonSize.small, onPressed: () => setState(() {
        _renaming = null;
        _renameProblem = null;
      })),
      if (_renameProblem != null) dom.span(classes: 'animus-error', <Widget>[Component.text(_renameProblem!)]),
    ]);
  }

  Widget _diagnostics(AnimusDocument document) {
    final List<Diagnostic> server = component.serverDiagnostics;
    if (document.diagnostics.isEmpty && server.isEmpty) {
      return const dom.p(classes: 'animus-ok', <Widget>[Component.text('Valid against the shared schema.')]);
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
      for (final Diagnostic diagnostic in document.diagnostics.take(50))
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
    if (json is Map<String, Object?> && onJson != null && guiEditorKinds.contains(document.kind)) {
      return switch (document.kind) {
        DocumentKind.inventory => InventoryEditor(document: document, onJson: onJson),
        DocumentKind.shop => ShopEditor(document: document, onJson: onJson),
        DocumentKind.menu => MenuEditor(document: document, onJson: onJson, animations: workspaceAnimations(component.workspaceDocuments)),
        _ => HologramEditor(document: document, onJson: onJson, animations: workspaceAnimations(component.workspaceDocuments)),
      };
    }
    if (json is Map<String, Object?> && onJson != null && document.kind == DocumentKind.npc) {
      final Widget inspector = NpcInspector(
        document: document,
        onJson: onJson,
        schemas: _schemas,
        diagnostics: _allDiagnostics(document),
        workspaceDocuments: component.workspaceDocuments,
        onSkinLoaded: () => setState(() {}),
        onOpen: component.onOpen,
      );
      return dom.div(classes: 'animus-npc-visual', <Widget>[inspector, _preview(document)]);
    }
    if (json is Map<String, Object?> && onJson != null && document.kind == DocumentKind.route) {
      return dom.section(classes: 'animus-panel animus-inspector', attributes: const <String, String>{'aria-label': 'Route'}, <Widget>[
        RouteEditor(
          value: json,
          path: r'$',
          diagnostics: _allDiagnostics(document),
          onChanged: (JsonMap value) => onJson(value, coalesce: 'route'),
        ),
      ]);
    }
    if (json is Map<String, Object?> && onJson != null && document.kind == DocumentKind.behavior) {
      return BehaviorEditor(document: document, onJson: onJson, schemas: _schemas, diagnostics: _allDiagnostics(document));
    }
    if (json is Map<String, Object?> && onJson != null && document.kind == DocumentKind.pack) {
      return PackEditor(document: document, schema: _schemas.pack, onJson: onJson, diagnostics: _allDiagnostics(document));
    }
    if (json is Map<String, Object?> && document.kind == DocumentKind.skin) {
      return SkinSummary(document: document, workspaceDocuments: component.workspaceDocuments, onOpen: component.onOpen, onLoaded: () => setState(() {}));
    }
    if (json is! Map<String, Object?>) {
      return const dom.section(classes: 'animus-panel', <Widget>[Component.text('The document is not a JSON object.')]);
    }
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
        if (_draftError != null) dom.span(classes: 'animus-error', <Widget>[Component.text('Invalid JSON: $_draftError')]),
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
