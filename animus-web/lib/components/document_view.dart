/// The document frame: the page header (breadcrumb, title that renames on a
/// click, a Valid or "N problems" badge that opens the problem list, and the
/// actions menu), then the kind's form with its preview beside it. The
/// preview sits right of the form from 1100 px, in a band above it that
/// folds away from 761 px, and behind the header's Preview button on
/// phones. Advanced adds the Form, Code and Split switch. The per-kind forms
/// are the kinds' own editors.
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../config/docs_links.dart';
import '../logic/conversation/conversation_json.dart';
import '../logic/diagnostic_text.dart';
import '../logic/gui/gui_json.dart';
import '../logic/gui/gui_presets.dart';
import '../logic/gui/gui_text.dart';
import '../logic/gui/text_animation.dart';
import '../logic/inspect/inspect_schemas.dart';
import '../logic/inspect/json_completion.dart';
import '../logic/inspect/schema_fields.dart';
import '../logic/inspect/server_behaviors.dart';
import '../logic/npc/group_model.dart';
import '../logic/npc/npc_catalog.dart';
import '../logic/npc/route_model.dart';
import '../logic/npc/route_usage.dart';
import '../logic/npc/skin_edits.dart';
import '../logic/offline/document_fixes.dart';
import '../logic/offline/new_documents.dart' show idHint, newDocumentPath, validDocumentId;
import '../logic/recording/recording_edit.dart' show recordingProblems, recordingTags;
import '../logic/recording/recording_model.dart' show Recording;
import '../logic/recording/recording_route.dart';
import '../logic/suggest/completion_sources.dart';
import '../logic/npc/npc_tabs.dart' show NpcTab;
import '../model/animus_document.dart';
import '../model/display_name.dart';
import '../model/document_kind.dart';
import '../model/document_summary.dart' show summarizeDocument;
import 'gui/gui_split.dart' show PreviewToggle;
import '../services/browser.dart';
import '../state/library.dart';
import '../state/editor_store.dart' show DocumentChange;
import '../state/workspace_route.dart';
import '../ui/action_row.dart';
import '../ui/alert.dart';
import '../ui/badge.dart';
import '../ui/library_rows.dart' show npcLine;
import '../ui/confirm_dialog.dart';
import '../ui/dialog.dart';
import '../ui/field.dart';
import '../ui/info_tip.dart';
import '../ui/menu.dart';
import '../ui/mode.dart';
import '../ui/page_header.dart';
import '../ui/stack.dart';
import '../ui/stage_frame.dart';
import '../ui/toggle_choice.dart';
import '../ui/tone.dart';
import 'conversation/conversation_attach.dart';
import 'conversation/conversation_view.dart';
import 'gui/gui_data_editors.dart';
import 'gui/gui_fields.dart';
import 'gui/gui_targets.dart';
import 'gui/hologram_editor.dart';
import 'gui/inventory_editor.dart';
import 'gui/menu_editor.dart';
import 'gui/shop_editor.dart';
import 'inspect/behavior_editor.dart';
import 'inspect/code_view.dart';
import 'inspect/inspect_fields.dart';
import 'inspect/npc_inspector.dart';
import 'inspect/server_behaviors_editor.dart';
import 'inspect/typed_values_editor.dart';
import 'list_page.dart' show listNoun;
import 'npc/pack_editor.dart';
import 'npc/route_editor.dart';
import 'npc/skin_summary.dart';
import 'npc_preview.dart';
import 'offline/dry_run_panel.dart';
import 'pending_field.dart';
import 'quest/quest_editor.dart';
import 'recording/recording_view.dart';
import 'shell_sidebar.dart' show jobOfKind, jobRoute, kindCrumb;
import 'suggest/suggest_sources.dart';

/// Kinds whose title is a `name` field the header renames in place.
/// Kinds whose heading is a field of the document, and that field: Rename
/// edits it. Other kinds go by their id (or words of it), which Rename changes.
const Map<DocumentKind, String> nameFields = <DocumentKind, String>{
  DocumentKind.npc: 'name',
  DocumentKind.pack: 'name',
  DocumentKind.shop: 'title',
  DocumentKind.inventory: 'title',
  DocumentKind.quest: 'title',
};

/// Where the preview goes at the current width.
enum PreviewPlace { side, band, button }

class DocumentPane extends StatefulWidget {
  const DocumentPane({
    required this.document,
    required this.view,
    required this.onView,
    required this.onSource,
    required this.onJson,
    required this.workspaceId,
    required this.workspaceName,
    this.name,
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
    this.library,
    this.load,
    this.skinEditing,
    this.serverBehaviors = const <String>[],
    this.serverPlugins = const <String>{},
    this.npcLibrary,
    this.skinTexture,
    this.codeDraft,
    this.onCodeDraft,
    this.tab,
    super.key,
  });

  final String workspaceId;

  /// The form tab the page opens on, from its address.
  final String? tab;

  /// For the breadcrumb.
  final String workspaceName;

  /// What the document's list row calls it; the heading says the same.
  final String? name;

  /// Code text typed earlier and not applied yet, kept by the store while
  /// the user looks at other documents.
  final String? codeDraft;

  /// Keeps the Code draft (null drops it) outside this pane.
  final void Function(String? text)? onCodeDraft;

  /// Writes several documents as one undoable change (attaching a
  /// conversation to NPCs); null hides that list.
  final ConversationCommit? onCommit;

  /// The workspace library (every catalog entry, read or not), for pickers
  /// that list a whole kind.
  final LibraryOf? library;

  /// Reads documents of a server workspace that are not here yet.
  final LoadDocuments? load;

  /// Skin edits that write several documents and the kept PNGs.
  final SkinEditing? skinEditing;

  /// Behavior ids in the connected server's catalog, for an NPC's picker.
  final List<String> serverBehaviors;

  /// The plugin integrations the connected server reports running; the NPC
  /// editor offers their modules and fields only then.
  final Set<String> serverPlugins;

  /// A server workspace's NPCs from the catalog, for the other NPCs the
  /// preview draws around this one; null in a local workspace.
  final List<LibraryEntry>? npcLibrary;

  /// The texture address of a skin from the catalog, for those NPCs' skins.
  final String? Function(String skinId)? skinTexture;

  /// Opens another document of the workspace (a behavior, route or shop an
  /// NPC names).
  final void Function(DocumentKind kind, String id)? onOpen;

  /// Every document of the workspace, for completing references.
  final List<AnimusDocument> workspaceDocuments;

  /// Diagnostics the connected server returned for this document's draft.
  final List<Diagnostic> serverDiagnostics;

  /// The connected server's `GET /v1/schemas` map; null offline, where the
  /// embedded generated schemas apply.
  final Map<String, Object?>? liveSchemas;

  /// The connected server's registries, commands and PlaceholderAPI
  /// identifiers for completion; null offline.
  final ServerLookups? liveLookups;

  /// Form edits.
  final JsonEdit onJson;

  /// Moves the document to a new id; returns a problem to show, or null.
  final Future<String?> Function(String id)? onRename;

  /// Deletes the document after the user confirmed it.
  final Future<void> Function()? onDelete;

  /// Copies the document to a new id; returns a problem to show, or null.
  final Future<String?> Function(String id)? onDuplicate;

  /// Workspace resources: rig textures and the PNGs of local skins.
  final Map<String, Uint8List> resources;

  /// A structured editor (the rig editor) shown as the form.
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

  /// The id dialog: a copy (Duplicate) or a move (Change id).
  bool? _idCopy;
  String _idValue = '';
  String? _idProblem;
  bool _problemsOpen = false;
  bool _previewShown = false;
  PreviewPlace _place = PreviewPlace.side;
  bool _wide = true;
  final List<void Function()> _stops = <void Function()>[];

  static const String _problemsId = 'ui-doc-problems';
  static const String _idInputId = 'ui-doc-id-input';

  @override
  void initState() {
    super.initState();
    final String? kept = component.codeDraft;
    if (kept != null) _check(kept);
    pendingProblems.addListener(_pendingChanged);
    final (bool phone, void Function() stopPhone) = watchMedia('(max-width: 760px)', (bool _) => _measure());
    final (bool band, void Function() stopBand) = watchMedia('(max-width: 1099px)', (bool _) => _measure());
    _place = phone ? PreviewPlace.button : (band ? PreviewPlace.band : PreviewPlace.side);
    _wide = !band;
    _stops.addAll(<void Function()>[stopPhone, stopBand]);
    // A behavior shaped like a conversation opens on its Conversation view.
    if (component.document.kind == DocumentKind.behavior && component.view == ViewMode.visual && conversationOf(component.document.json) != null) {
      Future<void>.microtask(() {
        if (mounted) component.onView(ViewMode.conversation);
      });
    }
  }

  @override
  void dispose() {
    for (final void Function() stop in _stops) {
      stop();
    }
    pendingProblems.removeListener(_pendingChanged);
    super.dispose();
  }

  void _measure() {
    if (!mounted) return;
    final bool phone = web.window.matchMedia('(max-width: 760px)').matches;
    final bool band = web.window.matchMedia('(max-width: 1099px)').matches;
    setState(() {
      _place = phone ? PreviewPlace.button : (band ? PreviewPlace.band : PreviewPlace.side);
      _wide = !band;
    });
  }

  void _pendingChanged() {
    if (mounted) setState(() {});
  }

  /// The document's diagnostics less those of a field the user is filling.
  List<Diagnostic> _shown(AnimusDocument document) => pendingProblems.visible(document.diagnostics);

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

  /// The views the switch offers, all in Advanced: Conversation (for a
  /// behavior that reads as one) and Steps for behaviors (Form for other
  /// kinds), Code and (from 1100 px) Split.
  List<ChoiceOption<ViewMode>> _views(AnimusDocument document, bool advanced) => <ChoiceOption<ViewMode>>[
        if (advanced && document.kind == DocumentKind.behavior && conversationOf(document.json) != null) const ChoiceOption<ViewMode>(ViewMode.conversation, 'Conversation'),
        if (advanced) ChoiceOption<ViewMode>(ViewMode.visual, document.kind == DocumentKind.behavior ? 'Steps' : 'Form'),
        if (advanced) const ChoiceOption<ViewMode>(ViewMode.code, 'Code'),
        if (advanced && _wide) const ChoiceOption<ViewMode>(ViewMode.split, 'Split'),
      ];

  /// The view to show: Code and Split are Advanced, Split needs 1100 px,
  /// Conversation is for behaviors, and Preview is the form with its preview.
  /// Simple shows a behavior as its conversation when it is one, else as
  /// steps.
  ViewMode _viewFor(AnimusDocument document, bool advanced) {
    final ViewMode view = component.view;
    if (!advanced && document.kind == DocumentKind.behavior) return conversationOf(document.json) != null ? ViewMode.conversation : ViewMode.visual;
    if (view == ViewMode.conversation) return document.kind == DocumentKind.behavior ? view : ViewMode.visual;
    if (view == ViewMode.preview) return ViewMode.visual;
    if (!advanced && (view == ViewMode.code || view == ViewMode.split)) return ViewMode.visual;
    if (view == ViewMode.split && !_wide) return ViewMode.code;
    return view;
  }

  /// Renames the title: the `name` of an NPC or pack, otherwise the id.
  Future<String?> _rename(String value) => component.onRename!(value);

  void _startId({required bool copy}) {
    setState(() {
      _idCopy = copy;
      _idValue = copy ? '${component.document.id}-copy' : component.document.id;
      _idProblem = null;
    });
    focusWhenShown(_idInputId);
  }

  Future<void> _submitId() async {
    final bool? copy = _idCopy;
    if (copy == null) return;
    final String? problem = await (copy ? component.onDuplicate! : component.onRename!)(_idValue.trim());
    if (!mounted) return;
    setState(() {
      _idProblem = problem;
      if (problem == null) _idCopy = null;
    });
  }

  Future<void> _delete() async {
    final AnimusDocument document = component.document;
    final bool yes = await askToConfirm(
      title: 'Delete $_displayName?',
      message: document.kind == DocumentKind.skin ? skinDeleteWarning(component.workspaceDocuments, document.id) : 'Undo brings it back.',
      confirmLabel: 'Delete ${listNoun(document.kind)}',
      destructive: true,
    );
    if (yes) await component.onDelete!();
  }

  /// The name the document's list row shows; its plain title, else its id.
  String get _displayName {
    final String plain = guiPlainText(component.document.title).trim();
    return component.name ?? (plain.isEmpty ? component.document.id : plain);
  }

  bool _hasPreview(AnimusDocument document) =>
      component.editor == null && document.json is Map<String, Object?> && (document.kind == DocumentKind.npc || document.kind == DocumentKind.behavior);

  /// Kinds whose editor puts its preview beside the form ([GuiSplit]); on
  /// phones the header's Preview button shows it. A chest GUI's window is
  /// the editor itself, so it has no button.
  bool _splitPreview(AnimusDocument document, ViewMode view) =>
      component.editor == null &&
      view == ViewMode.visual &&
      const <DocumentKind>{DocumentKind.shop, DocumentKind.quest, DocumentKind.textAnimation, DocumentKind.hologram, DocumentKind.menu}.contains(document.kind);

  @override
  Widget build(BuildContext context) {
    final AnimusDocument document = component.document;
    final bool advanced = ModeScope.advanced(context);
    final ViewMode view = _viewFor(document, advanced);
    final List<ChoiceOption<ViewMode>> views = _views(document, advanced);
    final List<Diagnostic> shown = _shown(document);
    final List<Diagnostic> problems = groupRepeats(withoutRepeats(<Diagnostic>[
      for (final Diagnostic d in shown) if (d.code != 'no-schema') d,
      ...serverOnly(shown, component.serverDiagnostics),
    ]));
    final DocumentCheck check = documentCheck(all: document.diagnostics, shown: shown, problems: problems);
    final int errors = problems.where((Diagnostic d) => d.severity == DiagnosticSeverity.error).length;
    final bool preview = _hasPreview(document) && (view == ViewMode.visual || view == ViewMode.conversation);
    final String? nameField = nameFields[document.kind];
    // A kind named by a form field (an NPC's Name) is renamed there only;
    // the header's Rename moves the id of the others.
    final bool canRename = nameField == null && component.onRename != null;
    final String title = _displayName;
    return dom.div(classes: 'ui-doc', <Widget>[
      PageHeader(
        title: title,
        // Advanced names the file; Simple tells an unnamed NPC apart by where it stands.
        detail: advanced
            ? (title != document.id ? document.id : null)
            : needsPath(document.kind, title)
                ? npcLine(summarizeDocument(document.kind, document.id, document.json), place: true)
                : null,
        renameValue: document.id,
        renameLabel: 'Id',
        onRename: canRename ? _rename : null,
        crumbs: <Crumb>[
          Crumb(component.workspaceName, href: WorkspaceRoute(component.workspaceId).format()),
          Crumb(jobOfKind(document.kind).label, href: jobRoute(jobOfKind(document.kind), component.workspaceId).format()),
          // A job with several kinds (Rigs: rigs, animations, skins) names
          // the tab too, as Search and the list do.
          if (kindCrumb(document.kind) case final String tab) Crumb(tab, href: KindListRoute(component.workspaceId, document.kind).format()),
        ],
        status: _draftError != null
            ? const StatusBadge('Code not used', tone: BadgeTone.warning)
            : check != DocumentCheck.problems
                ? (check == DocumentCheck.valid ? const StatusBadge('Valid', tone: BadgeTone.ok) : const StatusBadge('Checking', tone: BadgeTone.neutral))
                : BadgeButton(
                    errors == 0 ? '${problems.length} ${problems.length == 1 ? 'warning' : 'warnings'}' : '$errors ${errors == 1 ? 'problem' : 'problems'}',
                    tone: errors == 0 ? BadgeTone.warning : BadgeTone.error,
                    expanded: _problemsOpen,
                    controls: _problemsId,
                    onPressed: () => setState(() => _problemsOpen = !_problemsOpen),
                  ),
        actions: <AppMenuEntry>[
          if (component.onDuplicate != null) AppMenuEntry('Duplicate', () => _startId(copy: true)),
          if (advanced && nameField != null && component.onRename != null) AppMenuEntry('Change id', () => _startId(copy: false)),
          if (component.onDelete != null) ...<AppMenuEntry>[const AppMenuEntry.separator(), AppMenuEntry('Delete', _delete, destructive: true)],
        ],
        viewSwitch: views.length > 1
            ? ToggleChoice<ViewMode>(label: 'View', options: views, value: view, onChanged: component.onView)
            : null,
        secondary: <RowAction>[
          if (_place == PreviewPlace.button && (preview || _splitPreview(document, view)))
            // Only phones have this button: an icon, so the title keeps the line.
            RowAction(
              document.kind == DocumentKind.behavior ? 'Test' : 'Preview',
              () => setState(() => _previewShown = !_previewShown),
              icon: ArcaneIcon.eye(),
              iconOnly: true,
              pressed: _previewShown,
              attributes: const <String, String>{'aria-controls': 'ui-doc-preview'},
            ),
        ],
      ),
      if (_problemsOpen && problems.isNotEmpty) dom.div(id: _problemsId, classes: 'ui-doc-problems', <Widget>[_diagnostics(document, problems)]),
      if (_draft != null && view != ViewMode.code && view != ViewMode.split)
        dom.div(classes: 'ui-doc-note', attributes: const <String, String>{'role': 'status', 'data-testid': 'code-draft-note'}, <Widget>[
          const dom.span(<Widget>[Component.text('Code not used yet. This view shows the document without it.')]),
          Button.outline(label: 'Open Code', size: ButtonSize.sm, onPressed: () => component.onView(ViewMode.code)),
        ]),
      SuggestSources(
        documents: component.workspaceDocuments,
        catalog: _schemas.catalog,
        lookups: component.liveLookups,
        child: PreviewToggle(shown: _previewShown, child: _body(document, view, preview)),
      ),
      if (_idCopy != null) _idDialog(document),
    ]);
  }

  Widget _body(AnimusDocument document, ViewMode view, bool preview) {
    final Widget docs = DocsScope(link: Docs.forKind(document.kind), child: _editor(document, view));
    if (view == ViewMode.code) return dom.div(classes: 'ui-doc-body is-code', <Widget>[_code(document)]);
    if (view == ViewMode.split) {
      return dom.div(classes: 'ui-doc-body is-split', <Widget>[
        dom.div(classes: 'ui-doc-form', <Widget>[docs]),
        dom.div(classes: 'ui-doc-side', <Widget>[_code(document)]),
      ]);
    }
    if (!preview) return dom.div(classes: 'ui-doc-body${component.editor != null || _fullWidth(document) ? '' : ' is-plain'}', <Widget>[docs]);
    final Widget stage = dom.div(id: 'ui-doc-preview', classes: 'ui-doc-preview', <Widget>[_preview(document)]);
    return switch (_place) {
      PreviewPlace.side => dom.div(classes: 'ui-doc-body has-preview', <Widget>[
          dom.div(classes: 'ui-doc-form', <Widget>[docs]),
          StageSide(child: stage),
        ]),
      PreviewPlace.band => dom.div(classes: 'ui-doc-body is-band', <Widget>[
          stage,
          dom.div(classes: 'ui-doc-form', <Widget>[docs]),
        ]),
      PreviewPlace.button => dom.div(classes: 'ui-doc-body is-stacked', <Widget>[
          if (_previewShown) stage,
          dom.div(classes: 'ui-doc-form', <Widget>[docs]),
        ]),
    };
  }

  /// Editors that draw their own window or stage take the whole width.
  bool _fullWidth(AnimusDocument document) => const <DocumentKind>{
        DocumentKind.inventory,
        DocumentKind.shop,
        DocumentKind.menu,
        DocumentKind.hologram,
        DocumentKind.quest,
        DocumentKind.recording,
        DocumentKind.route,
        DocumentKind.textAnimation,
        DocumentKind.presets,
      }.contains(document.kind);

  Widget _editor(AnimusDocument document, ViewMode view) {
    if (view == ViewMode.conversation) return _conversation(document);
    if (component.editor != null) return component.editor!;
    return _visual(document);
  }

  Widget _idDialog(AnimusDocument document) {
    final bool copy = _idCopy!;
    return AppDialog(
      open: true,
      title: copy ? 'Duplicate $_displayName' : 'Change id',
      onClose: () => setState(() => _idCopy = null),
      footer: DialogFooter(
        cancel: RowAction('Cancel', () => setState(() => _idCopy = null)),
        primary: RowAction(copy ? 'Duplicate' : 'Change id', _submitId),
      ),
      children: <Widget>[
        Field(
          label: copy ? 'Id of the copy' : 'New id',
          forId: _idInputId,
          tip: 'Use ${idHint(document.kind)}. References move with it.',
          error: _idProblem,
          child: TextInput(
            id: _idInputId,
            value: _idValue,
            onChanged: (String value) => setState(() {
              _idValue = value;
              _idProblem = null;
            }),
            onSubmitted: (String _) => _submitId(),
          ),
        ),
      ],
    );
  }

  /// The problem list the status badge opens, with the fixes the editor
  /// knows.
  Widget _diagnostics(AnimusDocument document, List<Diagnostic> problems) {
    final List<DocumentFix> fixes = fixesFor(document);
    Future<void> apply(Object? json) => component.onSource(prettyJson(json));
    return dom.div(classes: 'ui-problem-list', <Widget>[
      _diagnosticList(problems),
      if (fixes.isNotEmpty)
        ActionRow(
          dense: true,
          label: 'Fixes',
          secondary: <RowAction>[for (final DocumentFix fix in fixes.take(8)) RowAction(fix.label, () => apply(fix.apply(document.json)))],
          primary: fixes.length > 1 ? RowAction('Fix all ${fixes.length}', () => apply(applyFixes(document.json, fixes))) : null,
        ),
    ]);
  }

  Widget _diagnosticList(List<Diagnostic> problems) => dom.ul(classes: 'ui-problems', <Widget>[
        for (final Diagnostic diagnostic in problems.take(50))
          dom.li(classes: 'is-${diagnostic.severity.name}', <Widget>[Component.text(diagnosticSentence(diagnostic))]),
      ]);

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

  /// A route document on the world of the first NPC that walks it, so the
  /// map draws that NPC and its terrain and 3D can show; a short line names
  /// who uses it.
  Widget _route(AnimusDocument document, JsonMap json, List<AnimusDocument> workspace) {
    final List<AnimusDocument> users = routeUsers(workspace, document.id);
    final RouteUserPlace? place = users.isEmpty ? null : routeUserPlace(users.first.json);
    final String? usedBy = usedByLine(<String>[for (final AnimusDocument user in users) guiPlainText(user.title)]);
    return dom.div(classes: 'animus-route-document', <Widget>[
      if (usedBy != null) dom.p(attributes: const <String, String>{'data-testid': 'route-used-by'}, <Widget>[Text(usedBy, size: FontSize.sm, color: TextColor.muted)]),
      RouteEditor(
        value: json,
        path: r'$',
        world: place?.world,
        origin: place?.origin,
        diagnostics: _allDiagnostics(document),
        recordings: routeRecordings(workspace),
        onChanged: (JsonMap value, JsonMap from) => component.onJson(writeAt(from, const <JsonStep>[], value), coalesce: 'route'),
      ),
    ]);
  }

  Widget _visual(AnimusDocument document) {
    final Object? json = document.json;
    final JsonEdit onJson = component.onJson;
    if (json is! Map<String, Object?>) {
      return const AppAlert(tone: AlertTone.warning, message: 'Not a JSON object. Fix it in Code.');
    }
    if (document.kind == DocumentKind.recording) {
      return RecordingView(
        key: ValueKey<String>('recording:${document.id}'),
        document: document,
        onJson: onJson,
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
    return GuiTargets(documents: workspace, kind: document.kind, library: component.library, child: switch (document.kind) {
      DocumentKind.npc => NpcInspector(
            document: document,
            tab: NpcTab.values.where((NpcTab each) => each.slug == component.tab).firstOrNull,
            onJson: onJson,
            schemas: _schemas,
            diagnostics: _allDiagnostics(document),
            workspaceDocuments: workspace,
            onSkinLoaded: () => setState(() {}),
            skinEditing: component.skinEditing,
            onOpen: component.onOpen,
            onCommit: component.onCommit,
            serverBehaviors: component.serverBehaviors,
            serverPlugins: component.serverPlugins,
          ),
      DocumentKind.behavior => BehaviorEditor(
          document: document,
          onJson: onJson,
          schemas: _schemas,
          diagnostics: _allDiagnostics(document),
        ),
      DocumentKind.route => _route(document, json, workspace),
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
      DocumentKind.quest => QuestEditor(document: document, onJson: onJson, workspaceDocuments: workspace, npcLibrary: component.library?.call(DocumentKind.npc) ?? const <LibraryEntry>[]),
      DocumentKind.menu => MenuEditor(document: document, onJson: onJson, animations: workspaceAnimations(workspace)),
      DocumentKind.hologram => HologramEditor(document: document, onJson: onJson, animations: workspaceAnimations(workspace), presets: workspacePresets(workspace)),
      DocumentKind.textAnimation || DocumentKind.presets => guiDataEditor(document, onJson, workspace) ?? _form(document, json),
      _ => _form(document, json),
    });
  }

  Widget _conversation(AnimusDocument document) {
    final JsonEdit onJson = component.onJson;
    if (document.json is! Map<String, Object?>) {
      return const AppAlert(tone: AlertTone.warning, message: 'Not a JSON object. Fix it in Code.');
    }
    return ConversationView(
      key: ValueKey<String>('conversation:${document.id}'),
      document: document,
      onJson: onJson,
      catalog: _schemas.catalog,
      workspaceDocuments: component.workspaceDocuments,
      library: component.library,
      load: component.load,
      onCommit: component.onCommit,
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
      classes: 'animus-document-form',
      attributes: <String, String>{'aria-label': '${document.kind.label} fields', 'data-form': document.kind.wireName},
      <Widget>[
        stack(spacing: 12, <Widget>[
          if (missingRig != null)
            dom.p(attributes: const <String, String>{'data-form': 'rig-missing'}, <Widget>[
              TipLabel(label: Component.text('Rig $missingRig is not in this workspace.'), tip: 'The timeline needs the rig. Add or import it, and this animation opens in the rig editor.'),
            ]),
          if (schema != null)
            InspectObject(schema: schema, node: schema.root, value: json, path: r'$', diagnostics: _allDiagnostics(document), skip: const <String>{'revision'}, onChanged: put)
          else
            KeyValueEditor(value: json, label: '${document.kind.label} fields', onChanged: put),
        ]),
      ],
    );
  }

  /// The route an NPC follows: its inline navigation fields, or the route
  /// document it names when the workspace has it.
  JsonMap? _routeOf(AnimusDocument document) {
    final JsonMap navigation = asMap(asMap(asMap(document.json)['modules'])[navigationModule]);
    if (navigation.isEmpty) return null;
    // An NPC playing a recording walks the recorded path.
    if (asString(navigation['recording']) case final String recording) {
      for (final AnimusDocument other in component.workspaceDocuments) {
        if (other.kind == DocumentKind.recording && other.id == recording) return recordingPathOf(other.json);
      }
      return null;
    }
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

  /// The pane beside the form: the NPC on the 3D stage, or a behavior's
  /// Test (a dry run in the browser).
  Widget _preview(AnimusDocument document) {
    if (document.kind == DocumentKind.behavior) {
      return DryRunPanel(
        key: ValueKey<String>('dry-run:${document.id}'),
        document: document,
        workspaceDocuments: component.workspaceDocuments,
        resources: component.resources,
        onOpen: component.onOpen,
        band: _place == PreviewPlace.band,
      );
    }
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
      neighbours: component.npcLibrary,
      skinTexture: component.skinTexture,
      collapsible: _place == PreviewPlace.band,
    );
  }

  Widget _code(AnimusDocument document) {
    final InspectSchemas schemas = _schemas;
    final ValueHints? hints = switch (document.kind) {
      DocumentKind.behavior => behaviorHints(guiIds: <DocumentKind, List<String>>{for (final DocumentKind kind in <DocumentKind>[DocumentKind.inventory, DocumentKind.menu, DocumentKind.shop]) kind: _ids(kind)}),
      DocumentKind.npc => npcHints(behaviorIds: npcBehaviorIds(component.workspaceDocuments), skinIds: _ids(DocumentKind.skin)),
      _ => null,
    };
    return CodeView(
      text: _draft ?? document.source,
      onChanged: _edit,
      onUse: _draft == null || _draftError != null || _recordingRefusal != null ? null : _apply,
      onDiscard: _draft == null ? null : _discard,
      schema: schemas.forKind(document.kind),
      catalog: document.kind == DocumentKind.behavior ? schemas.catalog : null,
      hints: hints,
      diagnostics: document.diagnostics,
      serverDiagnostics: component.serverDiagnostics,
      refusal: _draftError == null ? _recordingRefusal : null,
    );
  }
}
