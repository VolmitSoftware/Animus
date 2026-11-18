/// The start page's plain-Dart parts: the ways in, the Continue list (each
/// paired server once, with its workspace, then the workspaces kept in this
/// browser) and the first-steps checklist. Pure Dart.
library;

import '../../services/server/server_profiles.dart';
import '../../state/workspace_repository.dart';
import '../server/pair_copy.dart' show pairCommand;

/// What a way in does; the start page wires each to its work.
enum StartAction { pair, examples, files, blank, import }

/// A way in as a row of the start page: a label, one line on what it does
/// and its buttons, in order.
class StartWay {
  const StartWay(this.action, this.title, this.line, this.buttons);

  final StartAction action;
  final String title;
  final String line;
  final List<String> buttons;
}

/// The rows under the first visit's three cards (a blank workspace and
/// Import), or on a [returning] visit every way in, each with its line, in
/// Simple and Advanced alike.
List<StartWay> startWays({required bool returning}) => <StartWay>[
      if (returning) ...<StartWay>[
        const StartWay(StartAction.pair, 'Server', 'Run $pairCommand in game, then open its link here.', <String>['Pair a server']),
        const StartWay(StartAction.examples, 'Examples', 'Example NPCs, shops and behaviors to change. Nothing reaches a server.', <String>['Open examples']),
        const StartWay(StartAction.files, 'My files', 'A copy of your Animus folder, as a folder or a zip.', <String>['Open folder', 'Open zip']),
      ],
      const StartWay(StartAction.blank, 'Blank', 'An empty workspace to build in.', <String>['Blank workspace']),
      const StartWay(StartAction.import, 'Other plugins', 'NPCs, shops and scripts from Citizens, Shopkeepers, dtlTraders and Denizen.', <String>['Import']),
    ];

/// One row of the Continue list: a paired server (with its workspace once it
/// was opened) or a workspace kept in this browser.
class ContinueRow {
  const ContinueRow({this.server, this.workspace, this.orphan = false});

  final ServerProfile? server;
  final WorkspaceSummary? workspace;

  /// A server workspace whose server is no longer paired.
  final bool orphan;

  bool get isServer => server != null;
  String get name => server?.name ?? workspace!.name;
  String get key => server == null ? 'workspace:${workspace!.id}' : 'server:${server!.id}';
}

/// Servers first, each once, then the workspaces that belong to no paired
/// server.
List<ContinueRow> continueRows(List<ServerProfile> servers, List<WorkspaceSummary> workspaces) {
  final Set<String> paired = <String>{for (final ServerProfile server in servers) server.id};
  return <ContinueRow>[
    for (final ServerProfile server in servers)
      ContinueRow(server: server, workspace: workspaces.where((WorkspaceSummary each) => each.serverId == server.id).firstOrNull),
    for (final WorkspaceSummary workspace in workspaces)
      if (workspace.serverId == null || !paired.contains(workspace.serverId)) ContinueRow(workspace: workspace, orphan: workspace.serverId != null),
  ];
}

/// "1169 files" for a workspace, nothing for a server not opened yet.
String documentsLine(int count) => count == 1 ? '1 file' : '$count files';

/// One step of the first-steps checklist.
class FirstStep {
  const FirstStep(this.label, this.hint, {required this.done});

  final String label;
  final String hint;
  final bool done;
}

/// The checklist on Home: pick an NPC, change something and, on a server,
/// apply. Each step ticks itself from what the workspace shows.
List<FirstStep> firstSteps({required bool openedNpc, required bool changed, bool? applied}) => <FirstStep>[
      FirstStep('Pick an NPC', 'Open one from NPCs.', done: openedNpc),
      FirstStep('Change something', 'Edit a field in its form.', done: changed),
      if (applied != null) FirstStep('Apply', 'Press Apply in the bar at the bottom.', done: applied),
    ];

/// The localStorage key that hides the checklist for good.
const String firstStepsHiddenKey = 'animus.firstSteps.hidden';
