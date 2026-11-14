/// The start page's plain-Dart parts: the Continue list (each paired server
/// once, with its workspace, then the workspaces kept in this browser) and
/// the first-steps checklist. Pure Dart.
library;

import '../../services/server/server_profiles.dart';
import '../../state/workspace_repository.dart';

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

/// "1169 documents" for a workspace, nothing for a server not opened yet.
String documentsLine(int count) => count == 1 ? '1 document' : '$count documents';

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
