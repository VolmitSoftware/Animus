/// The start page. A first visit shows three ways in (pair a server, try the
/// examples, open my files) with Blank workspace and Import under them. A
/// returning visit lists what to continue first (paired servers with their
/// state, then the workspaces kept in this browser) and folds the ways in
/// into one row below.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/offline/starter_workspace.dart';
import '../../logic/plural.dart';
import '../../services/browser.dart';
import '../../state/editor_store.dart';
import '../../state/server_hub.dart';
import '../../state/workspace_repository.dart';
import '../../state/workspace_route.dart';
import '../../ui/alert.dart';
import '../../ui/skeleton.dart';
import '../brand_mark.dart';
import 'continue_list.dart';
import 'page_parts.dart';
import 'start_choices.dart';
import '../../ui/stack.dart';
import '../../model/display_name.dart' show closing;

class HomeScreen extends StatefulWidget {
  const HomeScreen({
    required this.store,
    required this.hub,
    required this.busy,
    required this.onOpenFolder,
    required this.onOpenZip,
    required this.onNavigate,
    this.openProblem,
    super.key,
  });

  /// Why the last picked folder or zip opened nothing.
  final String? openProblem;

  final EditorStore store;
  final ServerHub hub;
  final bool busy;
  final void Function() onOpenFolder;
  final void Function() onOpenZip;
  final void Function(EditorRoute route) onNavigate;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  bool _working = false;

  @override
  void initState() {
    super.initState();
    // One hello per saved server: Online, Checking or Not reachable.
    component.hub.probeServers();
  }

  Future<void> _run(String what, Future<void> Function() task) async {
    setState(() => _working = true);
    try {
      await task();
    } on Object catch (failure) {
      ArcaneSonner.error('Could not $what ($failure). Reload and try again.');
    } finally {
      if (mounted) setState(() => _working = false);
    }
  }

  void _blank(String name) => _run('create the workspace', () async {
        final WorkspaceSnapshot made = await component.store.createWorkspace(name);
        ArcaneSonner.success('Created ${made.name}.');
        component.onNavigate(WorkspaceRoute(made.id));
        focusMainHeading();
      });

  void _examples() => _run('open the examples', () async {
        final WorkspaceSnapshot made = await component.store.openFiles('Example packs', starterWorkspaceFiles());
        ArcaneSonner.success('Opened ${made.name} (${countOf(made.documents.length, 'document')}).');
        component.onNavigate(WorkspaceRoute(made.id));
        focusMainHeading();
      });

  @override
  Widget build(BuildContext context) {
    final EditorStore store = component.store;
    final bool busy = component.busy || _working;
    final StartActions actions = StartActions(
      busy: busy,
      onExamples: _examples,
      onOpenFolder: component.onOpenFolder,
      onOpenZip: component.onOpenZip,
      onBlank: _blank,
      onNavigate: component.onNavigate,
    );
    final bool listed = store.workspacesListed;
    final bool returning = component.hub.servers.isNotEmpty || store.workspaces.isNotEmpty;
    final WorkspaceSnapshot? deleted = store.deleted;
    final bool restorable = deleted != null && !store.workspaces.any((WorkspaceSummary summary) => summary.id == deleted.id);
    return dom.main_(classes: 'ui-start', attributes: const <String, String>{'aria-labelledby': mainHeadingId}, <Widget>[
      Row(spacing: 16, children: <Widget>[
        const BrandMark(size: 40, classes: 'ui-start-mark'),
        stack(spacing: 4, <Widget>[
          const dom.h1(id: mainHeadingId, classes: 'ui-title', attributes: <String, String>{'tabindex': '-1'}, <Widget>[Component.text('Animus web editor')]),
          mutedText('Edit NPCs, shops and behaviors in the browser.'),
        ]),
      ]),
      if (component.openProblem != null)
        dom.div(attributes: const <String, String>{'data-testid': 'open-problem'}, <Widget>[
          AppAlert(tone: AlertTone.error, title: 'Nothing opened', message: component.openProblem!, live: true),
        ]),
      if (restorable)
        AppAlert(
          key: const ValueKey<String>('restore'),
          tone: AlertTone.info,
          message: 'Deleted ${closing(deleted.name)} You can restore it until you close this tab.',
          action: Button.outline(label: 'Restore', size: ButtonSize.md, disabled: busy, onPressed: () => _run('restore ${deleted.name}', () async => store.restoreDeleted())),
        ),
      if (store.listProblem != null)
        AppAlert(tone: AlertTone.error, title: 'Workspaces did not load', message: store.listProblem!, live: true)
      else if (!listed)
        const Skeleton(rows: 3, label: 'Loading workspaces')
      else if (returning) ...<Widget>[
        dom.section(attributes: const <String, String>{'aria-labelledby': 'start-continue'}, <Widget>[
          stack(spacing: 12, <Widget>[
            sectionHeading('Continue', id: 'start-continue'),
            ContinueList(store: store, hub: component.hub, busy: busy, onNavigate: component.onNavigate),
          ]),
        ]),
        dom.section(attributes: const <String, String>{'aria-labelledby': 'start-new'}, <Widget>[
          StartMore(actions: actions, compact: true, heading: sectionHeading('Start', id: 'start-new')),
        ]),
      ] else ...<Widget>[
        StartCards(actions: actions),
        StartMore(actions: actions),
      ],
    ]);
  }
}
