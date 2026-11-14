/// What a dropdown that picks a document needs from the screen around it:
/// the workspace, the route it sits on (to come back to) and navigation.
/// The shell provides one around every workspace screen.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/select/select_model.dart';
import '../../model/display_name.dart';
import '../../model/document_kind.dart';
import '../../state/document_return.dart';
import '../../state/workspace_route.dart';

class PickerScope extends InheritedWidget {
  const PickerScope({required this.workspaceId, required this.route, required this.onNavigate, required super.child, this.nameOf, super.key});

  final String workspaceId;
  final EditorRoute route;
  final void Function(EditorRoute route) onNavigate;

  /// The name a document's list row shows, or null when the workspace does
  /// not list it; a dropdown that picks documents shows this, not the id.
  final String? Function(DocumentKind kind, String id)? nameOf;

  static PickerScope? of(BuildContext context) => context.dependOnInheritedComponentOfExactType<PickerScope>();

  /// How a page names the [kind] document [id] it refers to: its list
  /// name, else its id in words. A chest GUI id may name a menu.
  static String nameIn(BuildContext context, DocumentKind kind, String id) {
    final String? Function(DocumentKind kind, String id)? nameOf = of(context)?.nameOf;
    return nameOf?.call(kind, id) ?? (kind == DocumentKind.inventory ? nameOf?.call(DocumentKind.menu, id) : null) ?? displayName(kind, id, null);
  }

  @override
  bool updateShouldNotify(PickerScope oldComponent) => oldComponent.workspaceId != workspaceId || oldComponent.route != route;

  /// The footer rows of a dropdown that picks a [kind] document, its search
  /// text [query] named on "+ New".
  List<SelectAction> actionsFor(DocumentKind kind, {String query = ''}) => referenceActions(kind, query: query);

  /// Runs footer row [actionId] of a [kind] dropdown known as [field],
  /// with the search text [search].
  void run(String actionId, DocumentKind kind, String field, String search) {
    if (actionId == newActionId) {
      final DocumentRoute? here = route is DocumentRoute ? route as DocumentRoute : null;
      onNavigate(documentReturn.begin(
        workspaceId: workspaceId,
        origin: route,
        field: field,
        kind: kind,
        name: search,
        folder: here == null ? null : folderFor(here.kind, here.documentId, kind),
      ));
    } else if (actionId == manageActionId) {
      onNavigate(listRouteFor(workspaceId, kind));
    }
  }
}

const String newActionId = 'new';
const String manageActionId = 'manage';

/// How one document of [kind] is named in a sentence: "rig", "chest GUI".
String kindNoun(DocumentKind kind) => switch (kind) {
      DocumentKind.npc => 'NPC',
      DocumentKind.inventory => 'chest GUI',
      DocumentKind.menu => 'menu',
      DocumentKind.textAnimation => 'text animation',
      DocumentKind.serverBehaviors => 'server behaviors list',
      DocumentKind.presets => 'preset',
      _ => kind.label.substring(0, kind.label.length - 1).toLowerCase(),
    };

/// The plural for "Manage ...": "rigs", "chest GUIs", "NPCs".
String kindPlural(DocumentKind kind) => switch (kind) {
      DocumentKind.npc => 'NPCs',
      DocumentKind.inventory => 'chest GUIs',
      _ => kind.label.toLowerCase(),
    };

/// The footer of a dropdown that picks a [kind] document; "+ New" names the
/// search text [query] the new document takes. Recordings are made in game,
/// so theirs says how instead of offering New.
List<SelectAction> referenceActions(DocumentKind kind, {String query = ''}) => <SelectAction>[
      if (kind == DocumentKind.recording)
        const SelectAction(id: recordInGameActionId, label: 'Record in game', hint: 'Run /animus record start <id> in game.')
      else
        SelectAction(id: newActionId, label: '+ New ${kindNoun(kind)}${query.trim().isEmpty ? '' : ' "${query.trim()}"'}'),
      SelectAction(id: manageActionId, label: 'Manage ${kindPlural(kind)}'),
    ];

/// The recordings footer row; it only shows its tip.
const String recordInGameActionId = 'record';

/// Where "Manage" goes for [kind].
EditorRoute listRouteFor(String workspaceId, DocumentKind kind) => switch (kind) {
      DocumentKind.npc => NpcListRoute(workspaceId),
      DocumentKind.recording => RecordingListRoute(workspaceId),
      _ => KindListRoute(workspaceId, kind),
    };
