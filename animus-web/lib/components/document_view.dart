import 'dart:convert';
import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../logic/conversation/conversation_json.dart';
import '../logic/diagnostic_text.dart';
import '../logic/gui/gui_json.dart';
import '../logic/gui/gui_text.dart';
import '../logic/gui/gui_presets.dart';
import '../logic/gui/text_animation.dart';
import '../logic/inspect/inspect_schemas.dart';
import '../logic/inspect/json_completion.dart';
import '../logic/inspect/schema_fields.dart';
import '../logic/inspect/server_behaviors.dart';
import '../logic/suggest/completion_sources.dart';
import '../logic/npc/group_model.dart';
import '../logic/npc/npc_catalog.dart';
import '../logic/npc/route_model.dart';
import '../logic/npc/skin_edits.dart';
import '../logic/offline/document_fixes.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../services/browser.dart';
import '../state/workspace_route.dart';
import 'gui/gui_fields.dart';
import 'gui/gui_targets.dart';
import 'tab_strip.dart';
import 'offline/dry_run_panel.dart';
import 'offline/offline_fields.dart';
import 'recording/recording_view.dart';
import '../logic/recording/recording_edit.dart' show recordingProblems, recordingTags;
import '../logic/recording/recording_model.dart' show Recording;
import '../logic/recording/recording_route.dart';
import '../logic/offline/new_documents.dart' show idHint, newDocumentPath, validDocumentId;
import '../state/editor_store.dart' show DocumentChange;
import 'gui/gui_data_editors.dart';
import 'gui/hologram_editor.dart';
import 'gui/inventory_editor.dart';
import 'gui/menu_editor.dart';
import 'gui/shop_editor.dart';
import 'gui/shop_previews.dart';
import 'conversation/conversation_attach.dart';
import 'conversation/conversation_view.dart';
import 'inspect/behavior_editor.dart';
import 'inspect/json_code_editor.dart';
import 'inspect/inspect_fields.dart';
import 'inspect/npc_inspector.dart';
import 'inspect/typed_values_editor.dart';
import 'inspect/server_behaviors_editor.dart';
import 'npc/pack_editor.dart';
import 'npc/route_editor.dart';
import 'npc/skin_summary.dart';
import 'npc_preview.dart';
import 'pending_field.dart';
import 'info_tip.dart';
import 'suggest/suggest_sources.dart';
import '../config/docs_links.dart';

/// Kinds with a visual editor and a painted or 3D preview.
const Set<DocumentKind> guiEditorKinds = <DocumentKind>{DocumentKind.inventory, DocumentKind.shop, DocumentKind.menu, DocumentKind.hologram};

/// One document with its Visual, Preview, Code and Split views.
class DocumentPane extends StatefulWidget {
  const DocumentPane({
    required this.document,
    required this.view,
    required this.onView,
    required this.onSource,
    required this.onJson,
    this.onRename,
    this.onDelete,
    this.onDuplicate,
    this.skinDocument,
    this.resources = const <String, Uint8List>{},
    this.editor,
    this.serverDiagnostics = const <Diagnostic>[],
    this.liveSchemas,
    this.liveLookups,
    this.workspaceDocuments = const <AnimusDocument>[],
    this.onOpen,
    this.onCommit,
    this.onServerPreview,
    this.newConversationHref,
    this.skinEditing,
    this.serverBehaviors = const <String>[],
    this.codeDraft,
    this.onCodeDraft,
    super.key,
  });

  /// Code text typed earlier and not applied yet, kept by the store while
  /// the user looks at other documents.
  final String? codeDraft;

  /// Keeps the Code draft (null drops it) outside this pane.
  final void Function(String? text)? onCodeDraft;

  /// Writes several documents as one undoable change (attaching a
  /// conversation to NPCs); null hides that list.
  final ConversationCommit? onCommit;

  /// Starts the paired server's preview; null offline.
  final void Function()? onServerPreview;

  /// Where a new conversation is made.
  final String? newConversationHref;

  /// Skin edits that write several documents and the kept PNGs.
  final SkinEditing? skinEditing;

  /// Behavior ids in the connected server's catalog, for an NPC's picker.
  final List<String> serverBehaviors;

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

  /// The connected server's registries, commands and PlaceholderAPI
  /// identifiers for completion; null offline.
  final ServerLookups? liveLookups;

  /// Visual editor edits.
  final JsonEdit onJson;

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

  /// The first RecordingDocument rule a recording's Code draft breaks.
  String? _recordingRefusal;
  String? _renaming;
  String? _renameProblem;

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

  static const List<TabOption<ViewMode>> _behaviorViews = <TabOption<ViewMode>>[
    TabOption<ViewMode>(ViewMode.conversation, 'Conversation'),
    ..._views,
  ];

  List<TabOption<ViewMode>> get _tabs => component.document.kind == DocumentKind.behavior ? _behaviorViews : _views;

  /// The view to show: the Conversation tab exists for behaviors only.
  ViewMode get _view => component.view == ViewMode.conversation && component.document.kind != DocumentKind.behavior ? ViewMode.visual : component.view;

  @override
  void initState() {
    super.initState();
    // A Code draft left on another document comes back where it was.
    final String? kept = component.codeDraft;
    if (kept != null) _check(kept);
    pendingProblems.addListener(_pendingChanged);
    // A behavior shaped like a conversation opens on its Conversation tab.
    if (component.document.kind == DocumentKind.behavior && component.view == ViewMode.visual && conversationOf(component.document.json) != null) {
      Future<void>.microtask(() {
        if (mounted) component.onView(ViewMode.conversation);
      });
    }
  }

  @override
  void dispose() {
    pendingProblems.removeListener(_pendingChanged);
    super.dispose();
  }

  void _pendingChanged() {
    if (mounted) setState(() {});
  }

  /// The document's diagnostics less those of a field the user is filling.
  List<Diagnostic> _shown(AnimusDocument document) => pendingProblems.visible(document.diagnostics);

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
    setState(() => _check(value));
    component.onCodeDraft?.call(value);
  }

  /// Holds [value] as the Code draft with its JSON error or recording refusal.
  void _check(String value) {
    String? error;
    String? refusal;
    try {
      final Object? decoded = jsonDecode(value);
      if (component.document.kind == DocumentKind.recording) refusal = recordingProblems(decoded, id: component.document.id).firstOrNull;
    } on FormatException catch (invalid) {
      error = invalid.message;
    }
    _draft = value;
    _draftError = error;
    _recordingRefusal = refusal;
  }

  Future<void> _apply() async {
    final String? draft = _draft;
    if (draft == null || _draftError != null || _recordingRefusal != null) return;
    await component.onSource(draft);
    component.onCodeDraft?.call(null);
    if (mounted) setState(() => _draft = null);
  }

  void _discard() {
    component.onCodeDraft?.call(null);
    setState(() {
      _draft = null;
      _draftError = null;
      _recordingRefusal = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final AnimusDocument document = component.document;
    final ViewMode view = _view;
    final int selectedTab = _tabs.indexWhere((TabOption<ViewMode> option) => option.value == view);
    return dom.div(classes: 'animus-document', <Widget>[
      dom.header(classes: 'animus-document-header', <Widget>[
        dom.div(classes: 'animus-document-title', <Widget>[
          dom.span(classes: 'animus-eyebrow', <Widget>[Component.text(document.kind.label)]),
          dom.h1(id: mainHeadingId, attributes: const <String, String>{'tabindex': '-1'}, <Widget>[Component.text(guiPlainText(document.title))]),
          dom.code(classes: 'animus-muted animus-document-path', <Widget>[Component.text(document.path)]),
        ]),
        if (component.onRename != null || component.onDelete != null || component.onDuplicate != null) _manage(document) else ?_revision(document),
        TabStrip<ViewMode>(
          idPrefix: _tabPrefix,
          label: 'View',
          options: _tabs,
          selected: view,
          panelId: _panelId,
          onSelect: component.onView,
        ),
      ]),
      _diagnostics(document),
      if (_draft != null && view != ViewMode.code && view != ViewMode.split)
        dom.div(classes: 'animus-row animus-code-draft-note', attributes: const <String, String>{'role': 'status', 'data-testid': 'code-draft-note'}, <Widget>[
          const dom.span(<Widget>[Component.text('Code draft not applied. This view shows the document without it.')]),
          Button.secondary(label: 'Open Code', size: ButtonSize.small, onPressed: () => component.onView(ViewMode.code)),
        ]),
      SuggestSources(
        documents: component.workspaceDocuments,
        catalog: _schemas.catalog,
        lookups: component.liveLookups,
        child: dom.div(
        id: _panelId,
        classes: 'animus-views is-${view.name}${component.editor != null ? ' has-editor' : ''}',
        attributes: <String, String>{'role': 'tabpanel', 'aria-labelledby': TabStrip.tabId(_tabPrefix, selectedTab)},
        <Widget>[
        if (view == ViewMode.conversation)
          DocsScope(link: Docs.forKind(document.kind), child: _conversation(document))
        else if (component.editor != null && view != ViewMode.code)
          DocsScope(link: Docs.forKind(document.kind), child: component.editor!)
        else if (view == ViewMode.visual || (view == ViewMode.split && !_staged(document)))
          DocsScope(link: Docs.forKind(document.kind), child: _visual(document)),
        if (component.editor == null && (view == ViewMode.preview || (view == ViewMode.split && _staged(document)))) _preview(document),
        if (view == ViewMode.code || view == ViewMode.split) _code(document),
      ]),
      ),
    ]);
  }

  Widget _manage(AnimusDocument document) {
    final String? renaming = _renaming;
    if (_confirmDelete && component.onDelete != null) {
      return ConfirmRow(
        question: 'Delete ${document.id}?',
        detail: document.kind == DocumentKind.skin ? skinDeleteWarning(component.workspaceDocuments, document.id) : 'Undo brings it back.',
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
        ?_revision(document),
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

  /// The document's revision, read only: the server raises it on every
  /// apply, so nobody edits it by hand.
  Widget? _revision(AnimusDocument document) {
    final Object? json = document.json;
    final Object? revision = json is Map<String, Object?> ? json['revision'] : null;
    if (revision is! int) return null;
    return dom.span(
      classes: 'animus-muted animus-document-revision',
      attributes: const <String, String>{'title': 'The server raises it on every apply.'},
      <Widget>[Component.text('revision $revision')],
    );
  }

  Widget _diagnostics(AnimusDocument document) {
    final List<Diagnostic> server = component.serverDiagnostics;
    final List<Diagnostic> shown = _shown(document);
    if (_draftError != null) {
      return const dom.p(classes: 'animus-draft-error', attributes: <String, String>{'role': 'status'}, <Widget>[
        Component.text('The Code draft has a JSON error and is not applied.'),
      ]);
    }
    if (shown.isEmpty && server.isEmpty) {
      if (document.diagnostics.isNotEmpty) return const dom.p(classes: 'animus-muted', attributes: <String, String>{'role': 'status'}, <Widget>[Component.text('Checked once you leave the new field.')]);
      return const dom.p(classes: 'animus-ok', <Widget>[Component.text('Valid against the shared schema.')]);
    }
    // A kind without a shared schema gets one plain note, not a diagnostic
    // with a "$" path.
    final List<Diagnostic> noSchema = <Diagnostic>[for (final Diagnostic d in shown) if (d.code == 'no-schema') d];
    if (noSchema.length == shown.length && server.isEmpty) {
      return dom.p(classes: 'animus-muted animus-schema-note', <Widget>[Component.text(noSchema.first.message)]);
    }
    final List<DocumentFix> fixes = fixesFor(document);
    final Widget list = _diagnosticList(shown, server);
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

  Widget _diagnosticList(List<Diagnostic> shown, List<Diagnostic> server) {
    final List<Diagnostic> local = withoutRepeats(<Diagnostic>[for (final Diagnostic d in shown) if (d.code != 'no-schema') d]);
    return dom.ul(classes: 'animus-diagnostics', <Widget>[
      for (final Diagnostic diagnostic in local.take(50))
        dom.li(classes: 'is-${diagnostic.severity.name}', attributes: <String, String>{'title': diagnostic.path}, <Widget>[
          Component.text(diagnosticSentence(diagnostic)),
        ]),
      for (final Diagnostic diagnostic in withoutRepeats(serverOnly(local, server)).take(50))
        dom.li(classes: 'is-${diagnostic.severity.name} is-server', attributes: <String, String>{'title': diagnostic.path}, <Widget>[
          const dom.span(classes: 'animus-tag is-server', <Widget>[Component.text('server')]),
          Component.text(' ${diagnosticSentence(diagnostic)}'),
        ]),
    ]);
  }

  InspectSchemas get _schemas => InspectSchemas.from(component.liveSchemas);

  List<Diagnostic> _allDiagnostics(AnimusDocument document) => <Diagnostic>[..._shown(document), ...component.serverDiagnostics];

  List<String> _ids(DocumentKind kind) => <String>[
        for (final AnimusDocument other in component.workspaceDocuments)
          if (other.kind == kind) other.id,
      ];

  /// A new route following the recording, each leg in the style recorded on it; opens it. A problem to show, or null.
  Future<String?> _makeRoute(AnimusDocument recording, String id) async {
    if (!validDocumentId(DocumentKind.route, id)) return 'Use ${idHint(DocumentKind.route)}.';
    if (component.workspaceDocuments.any((AnimusDocument other) => other.kind == DocumentKind.route && other.id == id)) return 'Route $id already exists.';
    final Recording? read = Recording.parse(recording.json);
    final JsonMap? route = read == null ? null : recordingRoute(read);
    if (route == null) return 'The recording needs two frames to make a route.';
    await component.onCommit!(<DocumentChange>[DocumentChange(DocumentKind.route, id, prettyJson(route), path: newDocumentPath(DocumentKind.route, id))], 'route $id from ${recording.id}');
    component.onOpen?.call(DocumentKind.route, id);
    return null;
  }

  Widget _visual(AnimusDocument document) {
    final Object? json = document.json;
    final JsonEdit onJson = component.onJson;
    if (json is! Map<String, Object?>) {
      return const dom.section(classes: 'animus-panel', <Widget>[
        Component.text('Not a JSON object. Fix it in Code.'),
      ]);
    }
    if (document.kind == DocumentKind.recording) {
      return RecordingView(
        key: ValueKey<String>('recording:${document.id}'),
        document: document,
        onJson: onJson,
        onRename: component.onRename,
        onMakeRoute: component.onCommit == null ? null : (String id) => _makeRoute(document, id),
        knownTags: recordingTags(component.workspaceDocuments),
      );
    }
    if (document.kind == DocumentKind.skin) {
      return SkinSummary(
        document: document,
        workspaceDocuments: component.workspaceDocuments,
        onOpen: component.onOpen,
        onLoaded: () => setState(() {}),
        editing: component.skinEditing,
      );
    }
    final List<AnimusDocument> workspace = component.workspaceDocuments;
    return GuiTargets(documents: workspace, kind: document.kind, child: switch (document.kind) {
      DocumentKind.npc => dom.div(classes: 'animus-npc-visual', <Widget>[
          NpcInspector(
            document: document,
            onJson: onJson,
            schemas: _schemas,
            diagnostics: _allDiagnostics(document),
            workspaceDocuments: workspace,
            onSkinLoaded: () => setState(() {}),
            skinEditing: component.skinEditing,
            onOpen: component.onOpen,
            onCommit: component.onCommit,
            serverBehaviors: component.serverBehaviors,
          ),
          _preview(document),
        ]),
      DocumentKind.behavior => BehaviorEditor(
          document: document,
          onJson: onJson,
          schemas: _schemas,
          diagnostics: _allDiagnostics(document),
          onConversation: () => component.onView(ViewMode.conversation),
        ),
      DocumentKind.route => dom.section(classes: 'animus-panel animus-inspector', attributes: const <String, String>{'aria-label': 'Route'}, <Widget>[
          RouteEditor(
            value: json,
            path: r'$',
            diagnostics: _allDiagnostics(document),
            recordings: routeRecordings(workspace),
            onChanged: (JsonMap value, JsonMap from) => onJson(writeAt(from, const <JsonStep>[], value), coalesce: 'route'),
          ),
        ]),
      DocumentKind.group => NpcInspector.forGroup(
          document: document,
          onJson: onJson,
          schemas: _schemas,
          diagnostics: _allDiagnostics(document),
          workspaceDocuments: workspace,
          onSkinLoaded: () => setState(() {}),
            skinEditing: component.skinEditing,
          onOpen: component.onOpen,
          onCommit: component.onCommit,
          serverBehaviors: component.serverBehaviors,
        ),
      DocumentKind.pack => PackEditor(document: document, schema: _schemas.pack, onJson: onJson, diagnostics: _allDiagnostics(document)),
      DocumentKind.serverBehaviors => ServerBehaviorsEditor(
          document: document,
          onJson: onJson,
          workspaceDocuments: workspace,
          onOpen: component.onOpen,
          diagnostics: <Diagnostic>[..._allDiagnostics(document), ...serverBehaviorsDiagnostics(json, workspace)],
        ),
      DocumentKind.inventory => InventoryEditor(document: document, onJson: onJson, presets: workspacePresets(workspace)),
      DocumentKind.shop => ShopEditor(document: document, onJson: onJson, workspaceDocuments: workspace, onCommit: component.onCommit, onOpen: component.onOpen),
      DocumentKind.menu => MenuEditor(document: document, onJson: onJson, animations: workspaceAnimations(workspace)),
      DocumentKind.hologram => HologramEditor(document: document, onJson: onJson, animations: workspaceAnimations(workspace), presets: workspacePresets(workspace)),
      DocumentKind.textAnimation || DocumentKind.presets => guiDataEditor(document, onJson, workspace) ?? _form(document, json),
      _ => _form(document, json),
    });
  }

  Widget _conversation(AnimusDocument document) {
    final JsonEdit onJson = component.onJson;
    if (document.json is! Map<String, Object?>) {
      return const dom.section(classes: 'animus-panel', <Widget>[Component.text('Not a JSON object. Fix it in Code.')]);
    }
    return ConversationView(
      key: ValueKey<String>('conversation:${document.id}'),
      document: document,
      onJson: onJson,
      catalog: _schemas.catalog,
      workspaceDocuments: component.workspaceDocuments,
      onCommit: component.onCommit,
      onServerPreview: component.onServerPreview,
      onOpenVisual: () => component.onView(ViewMode.visual),
      newHref: component.newConversationHref,
    );
  }

  /// A form generated from the kind's schema, for kinds without their own
  /// editor (an animation whose rig is not here); without a schema, a typed
  /// key/value editor.
  Widget _form(AnimusDocument document, JsonMap json) {
    final SchemaView? schema = _schemas.forKind(document.kind);
    void put(JsonMap next) => component.onJson(writeAt(json, const <JsonStep>[], next), coalesce: 'form');
    final String? missingRig = document.kind == DocumentKind.animation ? asString(json['rig']) : null;
    return dom.section(
      classes: 'animus-panel animus-inspector animus-document-form',
      attributes: <String, String>{'aria-label': '${document.kind.label} fields', 'data-form': document.kind.wireName},
      <Widget>[
        if (missingRig != null)
          dom.p(classes: 'animus-muted animus-form-note', attributes: const <String, String>{'data-form': 'rig-missing'}, <Widget>[
            withTip(Component.text('Rig $missingRig is not in this workspace.'), 'The timeline needs the rig. Add or import it, and this animation opens in the rig editor.'),
          ]),
        if (schema != null)
          InspectObject(schema: schema, node: schema.root, value: json, path: r'$', diagnostics: _allDiagnostics(document), skip: const <String>{'revision'}, onChanged: put)
        else
          KeyValueEditor(value: json, label: '${document.kind.label} fields', onChanged: put),
      ],
    );
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

  /// The workspace skin document [document]'s `skin` names, when present.
  AnimusDocument? _skinFor(AnimusDocument document) {
    final String? skin = asString(asMap(document.json)['skin']);
    if (skin == null) return null;
    for (final AnimusDocument other in component.workspaceDocuments) {
      if (other.kind == DocumentKind.skin && other.id == skin) return other;
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
      // The stage shows what the server runs: the NPC over its groups.
      final NpcEffective? effective = effectiveNpc(asMap(document.json), workspaceGroups(component.workspaceDocuments));
      final AnimusDocument staged = effective == null ? document : document.copyWith(json: effective.json, source: prettyJson(effective.json));
      final (AnimusDocument? rig, List<AnimusDocument> animations) = _rigOf(staged);
      return NpcPreview(
        key: ValueKey<String>('npc-preview:${document.id}'),
        document: staged,
        skinDocument: _skinFor(staged) ?? component.skinDocument,
        route: _routeOf(staged),
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
    if (document.kind == DocumentKind.recording) {
      return RecordingView(key: ValueKey<String>('recording-preview:${document.id}'), document: document, facts: false);
    }
    final Object? json = document.json;
    if (json is Map<String, Object?> && guiEditorKinds.contains(document.kind)) {
      return switch (document.kind) {
        DocumentKind.inventory => InventoryPreview(document: document),
        DocumentKind.shop => ShopPreview(document: document, layouts: workspaceLayouts(component.workspaceDocuments)),
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
      DocumentKind.npc => npcHints(behaviorIds: npcBehaviorIds(component.workspaceDocuments), skinIds: _ids(DocumentKind.skin)),
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
          disabled: _draft == null || _draftError != null || _recordingRefusal != null,
          onPressed: _apply,
        ),
        if (_draft != null)
          Button.ghost(
            label: 'Discard',
            size: ButtonSize.small,
            onPressed: _discard,
          ),
        if (_recordingRefusal != null && _draftError == null)
          dom.span(classes: 'animus-error', attributes: const <String, String>{'data-recording': 'code-refusal', 'role': 'alert'}, <Widget>[
            Component.text('Not applied: $_recordingRefusal'),
          ]),
        if (_draftError != null)
          dom.span(classes: 'animus-error', <Widget>[
            Component.text('Not valid JSON: $_draftError. Fix it or Discard.'),
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
        ('Runs', listed.isEmpty ? 'nothing (empty list)' : '${listed.length} server behavior${listed.length == 1 ? '' : 's'}'),
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
