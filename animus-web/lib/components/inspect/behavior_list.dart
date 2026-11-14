/// The Behaviors page: every behavior of the workspace in one list (plain
/// names, what starts each one, conversations and the rest as filters),
/// and New behavior, which asks for a name and a kind in a dialog and opens
/// the new behavior.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/conversation/conversation_edit.dart' show newConversationBehavior;
import '../../logic/conversation/conversation_json.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/behavior_words.dart';
import '../../logic/offline/new_documents.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../services/browser.dart';
import '../../state/editor_store.dart';
import '../../state/library.dart';
import '../../state/workspace_repository.dart';
import '../../state/workspace_route.dart';
import '../../ui/action_row.dart';
import '../../ui/dialog.dart';
import '../../ui/field.dart';
import '../../ui/library_list.dart';
import '../../ui/library_query.dart';
import '../../ui/library_rows.dart';
import '../../ui/mode.dart';
import '../../ui/page_header.dart';
import '../list_page.dart' show SubKindTabs;
import '../shell_sidebar.dart' show jobOfKind;

const LibraryFilter conversationsFilter = LibraryFilter('conversation', 'Conversations');
const LibraryFilter otherBehaviorsFilter = LibraryFilter('other', 'Other');

/// What New behavior makes.
enum NewBehaviorKind {
  conversation('Conversation', 'Lines and answers. Starts on a click.'),
  blank('Blank behavior', 'No steps yet. You add them in Advanced.'),
  server('Server behavior', 'Runs on the server with no NPC.');

  const NewBehaviorKind(this.label, this.line);

  final String label;
  final String line;
}

/// The behavior New behavior writes for [kind] under [id].
JsonMap newBehavior(NewBehaviorKind kind, String id) => switch (kind) {
      NewBehaviorKind.conversation => newConversationBehavior(id),
      NewBehaviorKind.blank || NewBehaviorKind.server => <String, Object?>{
          'schemaVersion': 1,
          'revision': 1,
          'id': id,
          if (kind == NewBehaviorKind.server) 'server': true,
          'entry': 'main',
          'routines': <String, Object?>{
            'main': <String, Object?>{'type': 'sequence', 'steps': <Object?>[]},
          },
        },
    };

/// Whether a behavior reads as a conversation: the whole document when it
/// is here, else its catalog summary (it answers dialogue choices). Read
/// documents are remembered by their source.
final Map<String, (String, bool)> _conversationCache = <String, (String, bool)>{};

bool isConversation(LibraryEntry entry, AnimusDocument? document) {
  if (document == null) return entry.summary.triggers.contains('dialogue_choice');
  final (String, bool)? cached = _conversationCache[entry.key];
  if (cached != null && identical(cached.$1, document.source)) return cached.$2;
  final bool fits = conversationOf(document.json) != null;
  _conversationCache[entry.key] = (document.source, fits);
  return fits;
}

/// One behavior as a list row: its plain name (with its folder when another
/// behavior has the same name), what starts it, its state badges.
List<LibraryRow> behaviorRows(EditorStore store, String workspaceId) {
  final List<LibraryEntry> entries = store.library(DocumentKind.behavior);
  final Map<String, int> names = <String, int>{};
  for (final LibraryEntry entry in entries) {
    names.update(behaviorTitle(entry.id), (int count) => count + 1, ifAbsent: () => 1);
  }
  return <LibraryRow>[
    for (final LibraryEntry entry in entries) _row(store, workspaceId, entry, names),
  ];
}

LibraryRow _row(EditorStore store, String workspaceId, LibraryEntry entry, Map<String, int> names) {
  final LibraryRow base = entryRow(workspaceId, entry);
  final AnimusDocument? document = store.document(DocumentKind.behavior, entry.id);
  final bool talks = isConversation(entry, document);
  final String title = behaviorTitle(entry.id);
  final String folder = behaviorFolder(entry.id);
  final String start = document == null ? startLine(events: entry.summary.triggers) : startLine(triggers: asList(asMap(document.json)['triggers']));
  final bool server = document != null && asMap(document.json)['server'] == true;
  return LibraryRow(
    key: base.key,
    title: (names[title] ?? 0) > 1 && folder.isNotEmpty ? '$title ($folder)' : title,
    meta: <String>[if (talks) 'Conversation', if (server) 'Server', start].join(' · '),
    advancedMeta: base.advancedMeta,
    badges: base.badges,
    tags: <String>{...base.tags, talks ? conversationsFilter.id : otherBehaviorsFilter.id},
    href: base.href,
    keywords: '${base.keywords} $title',
    sortValues: <String, String>{LibrarySort.name.id: title.toLowerCase()},
  );
}

class BehaviorListPage extends StatefulWidget {
  const BehaviorListPage({required this.store, required this.workspace, required this.onNavigate, super.key});

  final EditorStore store;
  final WorkspaceSnapshot workspace;
  final void Function(EditorRoute route) onNavigate;

  @override
  State<BehaviorListPage> createState() => _BehaviorListPageState();
}

class _BehaviorListPageState extends State<BehaviorListPage> {
  static const String _nameId = 'new-behavior-name';

  bool _open = false;
  NewBehaviorKind _kind = NewBehaviorKind.conversation;
  String _name = '';
  String? _problem;
  bool _busy = false;

  void _start() {
    setState(() {
      _open = true;
      _kind = NewBehaviorKind.conversation;
      _name = '';
      _problem = null;
    });
    focusWhenShown(_nameId);
  }

  void _close() => setState(() => _open = false);

  Set<String> get _taken => <String>{for (final LibraryEntry entry in component.store.library(DocumentKind.behavior)) entry.id};

  /// The id the typed name gets: the workspace's usual folder and the name
  /// as a slug, numbered when taken.
  String _idFor(String name) {
    final String namespace = defaultNamespace(
      <String>[for (final AnimusDocument document in component.workspace.documents) document.id],
      component.workspace.name,
      server: component.workspace.server != null,
    );
    return freeId('$namespace:${slug(name, fallback: 'behavior')}', _taken);
  }

  Future<void> _create() async {
    if (_busy) return;
    if (_name.trim().isEmpty) {
      setState(() => _problem = 'Give it a name.');
      return;
    }
    final String id = _idFor(_name.trim());
    if (!validDocumentId(DocumentKind.behavior, id)) {
      setState(() => _problem = 'Use letters, digits and dashes.');
      return;
    }
    setState(() => _busy = true);
    final EditorStore store = component.store;
    final AnimusDocument? listed = store.document(DocumentKind.serverBehaviors, DocumentKind.serverBehaviorsId);
    await store.commit(
      documents: <DocumentChange>[
        DocumentChange(DocumentKind.behavior, id, prettyJson(newBehavior(_kind, id)), path: newDocumentPath(DocumentKind.behavior, id)),
        if (_kind == NewBehaviorKind.server && listed != null)
          DocumentChange.update(DocumentKind.serverBehaviors, listed.id, (Object? latest) {
            final JsonMap json = asMap(latest);
            return <String, Object?>{...json, 'behaviors': <Object?>[...asList(json['behaviors']), id]};
          }),
      ],
      label: 'create $id',
    );
    if (!mounted) return;
    setState(() => _busy = false);
    if (store.document(DocumentKind.behavior, id) == null) {
      setState(() => _problem = store.problem ?? 'Not created: the workspace changed. Press Create again.');
      return;
    }
    _close();
    component.onNavigate(DocumentRoute(component.workspace.id, DocumentKind.behavior, id, _kind == NewBehaviorKind.conversation ? ViewMode.conversation : ViewMode.visual));
  }

  @override
  Widget build(BuildContext context) {
    final bool advanced = ModeScope.advanced(context);
    final WorkspaceSnapshot workspace = component.workspace;
    final String job = jobOfKind(DocumentKind.behavior).label;
    final List<LibraryRow> rows = behaviorRows(component.store, workspace.id);
    return dom.div(classes: 'ui-list-page', <Widget>[
      PageHeader(
        title: job,
        crumbs: <Crumb>[Crumb(workspace.name, href: WorkspaceRoute(workspace.id).format()), Crumb(job)],
        // An empty list's own New is the one primary.
        primary: rows.isEmpty ? null : RowAction('New behavior', _start, icon: ArcaneIcon.plus(), attributes: const <String, String>{'data-testid': 'list-new'}),
      ),
      if (advanced)
        SubKindTabs(
          kinds: const <DocumentKind>[DocumentKind.behavior, DocumentKind.serverBehaviors],
          kind: DocumentKind.behavior,
          workspaceId: workspace.id,
          counts: component.store.libraryCounts(),
        ),
      LibraryList(
        key: ValueKey<String>('library:${workspace.id}:behavior'),
        label: 'Behaviors',
        noun: 'behavior',
        rows: rows,
        filters: const <LibraryFilter>[conversationsFilter, otherBehaviorsFilter, problemsFilter],
        emptyMessage: 'No behaviors yet.',
        emptyAction: Button.primary(label: 'New behavior', onPressed: _start),
      ),
      if (_open) _dialog(advanced),
    ]);
  }

  Widget _dialog(bool advanced) => AppDialog(
        open: true,
        id: 'new-behavior',
        title: 'New behavior',
        onClose: _close,
        footer: DialogFooter(cancel: RowAction('Cancel', _close), primary: RowAction('Create', _busy ? null : _create)),
        children: <Widget>[
          ArcaneRadioGroup<NewBehaviorKind>(
            name: 'new-behavior-kind',
            label: 'Kind',
            // The plain radio list: no boxes inside the dialog.
            variant: RadioGroupVariant.standard,
            value: _kind,
            options: <RadioOption<NewBehaviorKind>>[
              for (final NewBehaviorKind kind in NewBehaviorKind.values)
                if (advanced || kind != NewBehaviorKind.server) RadioOption<NewBehaviorKind>(value: kind, label: kind.label, description: kind.line),
            ],
            onChanged: (NewBehaviorKind kind) => setState(() => _kind = kind),
          ),
          Field(
            label: 'Name',
            forId: _nameId,
            required: true,
            error: _problem,
            hint: advanced && _name.trim().isNotEmpty ? _idFor(_name.trim()) : null,
            child: TextInput(
              id: _nameId,
              value: _name,
              onChanged: (String value) => setState(() {
                _name = value;
                _problem = null;
              }),
              onSubmitted: (String _) => _create(),
            ),
          ),
        ],
      );
}
