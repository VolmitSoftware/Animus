/// The Movement page: routes and recordings side by side as two tabs, each
/// with a line that tells them apart, one list and its New button. New
/// route asks for a name and opens the new route; New recording tells how
/// to record one in game (recordings are made there) with the command to
/// copy.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/offline/new_documents.dart';
import '../../model/document_kind.dart';
import '../../services/browser.dart';
import '../../state/editor_store.dart';
import '../../state/library.dart';
import '../../state/workspace_repository.dart';
import '../../state/workspace_route.dart';
import '../../ui/action_row.dart';
import '../../ui/dialog.dart';
import '../../ui/field.dart';
import '../../ui/info_tip.dart';
import '../../ui/library_list.dart';
import '../../ui/library_query.dart';
import '../../ui/library_rows.dart';
import '../../ui/mode.dart';
import '../../ui/page_header.dart';
import '../../ui/tabs.dart';
import 'movement_rows.dart';
import '../npc/npc_layout.dart';

/// The two tabs, in order.
const List<DocumentKind> movementKinds = <DocumentKind>[DocumentKind.route, DocumentKind.recording];

class MovementPage extends StatefulWidget {
  const MovementPage({required this.store, required this.workspace, required this.kind, required this.onNavigate, super.key});

  final EditorStore store;
  final WorkspaceSnapshot workspace;

  /// The open tab: [DocumentKind.route] or [DocumentKind.recording].
  final DocumentKind kind;
  final void Function(EditorRoute route) onNavigate;

  @override
  State<MovementPage> createState() => _MovementPageState();
}

class _MovementPageState extends State<MovementPage> {
  static const String _nameId = 'movement-new-name';

  bool _open = false;
  String _name = '';
  String? _problem;
  bool _busy = false;

  /// The rows and what they were built from, so a rebuild with the same
  /// library hands the list the same rows.
  (int, String, DocumentKind)? _builtFrom;
  List<LibraryRow> _rows = const <LibraryRow>[];

  bool get _routes => component.kind != DocumentKind.recording;

  void _build() {
    final EditorStore store = component.store;
    final (int, String, DocumentKind) key = (store.libraryRevision, component.workspace.id, component.kind);
    if (key == _builtFrom) return;
    _builtFrom = key;
    final List<LibraryEntry> npcs = store.library(DocumentKind.npc);
    _rows = _routes
        ? routeRows(workspaceId: component.workspace.id, routes: store.library(DocumentKind.route), npcs: npcs, document: store.document)
        : recordingRows(workspaceId: component.workspace.id, recordings: store.library(DocumentKind.recording), npcs: npcs, document: store.document);
  }

  /// How the routes split between shared route files and NPCs' own
  /// points, when some NPCs carry their own.
  String _split() {
    final int own = _rows.where((LibraryRow row) => row.tags.contains(ownRoutesFilter.id)).length;
    if (own == 0) return '';
    final int shared = _rows.length - own;
    return ' $shared shared, $own on NPCs.';
  }

  void _start() {
    setState(() {
      _open = true;
      _name = '';
      _problem = null;
    });
    focusWhenShown(_nameId);
  }

  void _close() => setState(() => _open = false);

  String get _id => slug(_name.trim(), fallback: _routes ? 'route' : 'recording');

  /// The id a new route takes: the name as a slug, numbered when taken.
  String _freeRouteId() => freeId(_id, <String>{for (final LibraryEntry entry in component.store.library(DocumentKind.route)) entry.id});

  Future<void> _create() async {
    if (_busy) return;
    if (_name.trim().isEmpty) {
      setState(() => _problem = 'Give it a name.');
      return;
    }
    final String id = _freeRouteId();
    if (!validDocumentId(DocumentKind.route, id)) {
      setState(() => _problem = 'Use letters, digits and dashes.');
      return;
    }
    setState(() => _busy = true);
    final EditorStore store = component.store;
    await store.commit(
      documents: <DocumentChange>[DocumentChange(DocumentKind.route, id, prettyJson(newRoute()), path: newDocumentPath(DocumentKind.route, id))],
      label: 'create route $id',
    );
    if (!mounted) return;
    setState(() => _busy = false);
    if (store.document(DocumentKind.route, id) == null) {
      setState(() => _problem = store.problem ?? 'Not created: the workspace changed. Press Create again.');
      return;
    }
    _close();
    component.onNavigate(DocumentRoute(component.workspace.id, DocumentKind.route, id));
  }

  String get _command => '/animus record start $_id';

  void _copy() {
    final String command = _command;
    try {
      web.window.navigator.clipboard.writeText(command).toDart.then((JSAny? _) => ArcaneSonner.success('Copied $command'), onError: (Object _) => ArcaneSonner.error('Copy it from the box instead.'));
    } on Object {
      ArcaneSonner.error('Copy it from the box instead.');
    }
  }

  @override
  Widget build(BuildContext context) {
    _build();
    final bool advanced = ModeScope.advanced(context);
    final WorkspaceSnapshot workspace = component.workspace;
    final String noun = _routes ? 'route' : 'recording';
    final int tab = _routes ? 0 : 1;
    return dom.div(classes: 'ui-list-page animus-movement', <Widget>[
      PageHeader(
        title: 'Movement',
        crumbs: <Crumb>[Crumb(workspace.name, href: WorkspaceRoute(workspace.id).format()), const Crumb('Movement')],
        // An empty list's own New is the one primary.
        primary: _rows.isEmpty ? null : RowAction('New $noun', _start, icon: ArcaneIcon.plus(), attributes: const <String, String>{'data-testid': 'list-new'}),
      ),
      AppTabs(
        id: 'movement-tabs',
        label: 'Movement',
        tabs: const <String>['Routes', 'Recordings'],
        selected: tab,
        onSelect: (int index) => navigate(KindListRoute(workspace.id, movementKinds[index]).format()),
      ),
      dom.p(classes: 'animus-movement-line', styles: _lineStyles, <Widget>[
        TipLabel(
          label: Component.text(_routes ? 'Walks point to point.${_split()}' : 'Replays what you did.'),
          tip: _routes
              ? 'Points on the ground. The NPC walks from one to the next and finds its own way between them.'
              : 'Your own movement, replayed exactly: steps, looks, sneaks, arm swings.',
          docs: _routes ? Docs.routes : Docs.recordings,
        ),
      ]),
      LibraryList(
        key: ValueKey<String>('movement:${workspace.id}:${component.kind.wireName}'),
        id: 'movement-list',
        label: _routes ? 'Routes' : 'Recordings',
        noun: noun,
        rows: _rows,
        filters: <LibraryFilter>[
          if (_routes) ...<LibraryFilter>[sharedRoutesFilter, ownRoutesFilter],
          problemsFilter,
        ],
        emptyMessage: _routes ? 'No routes yet.' : 'No recordings yet.',
        emptyAction: Button.primary(label: 'New $noun', onPressed: _start),
      ),
      if (_open) _routes ? _newRoute(advanced) : _newRecording(),
    ]);
  }

  /// The line under the tabs: muted, set off from the tabs and the list.
  static final dom.Styles _lineStyles = const ArcaneStyleData(marginStringCustom: '0.5rem 0 0.75rem', fontSize: FontSize.sm, textColor: TextColor.muted).toStyles();

  Widget _nameField({String? hint}) => Field(
        label: 'Name',
        forId: _nameId,
        required: true,
        error: _problem,
        hint: hint,
        child: TextInput(
          id: _nameId,
          value: _name,
          onChanged: (String value) => setState(() {
            _name = value;
            _problem = null;
          }),
          onSubmitted: (String _) => _routes ? _create() : _copy(),
        ),
      );

  Widget _newRoute(bool advanced) => AppDialog(
        open: true,
        id: 'new-route',
        title: 'New route',
        onClose: _close,
        footer: DialogFooter(cancel: RowAction('Cancel', _close), primary: RowAction('Create', _busy ? null : _create)),
        children: <Widget>[
          _nameField(hint: advanced && _name.trim().isNotEmpty ? _freeRouteId() : null),
        ],
      );

  Widget _newRecording() => AppDialog(
        open: true,
        id: 'new-recording',
        title: 'New recording',
        onClose: _close,
        footer: DialogFooter(cancel: RowAction('Cancel', _close), primary: RowAction('Copy command', _copy, icon: ArcaneIcon.copy())),
        children: <Widget>[
          mutedLine('Recordings are made in game. Stand where it starts, run the command and walk.'),
          _nameField(),
          Field(
            label: 'Command',
            forId: 'movement-command',
            tip: 'Stop with /animus record stop. A paired server lists the recording here once it is saved.',
            docs: Docs.recordings,
            child: TextInput(id: 'movement-command', value: _command, readOnly: true),
          ),
        ],
      );
}
