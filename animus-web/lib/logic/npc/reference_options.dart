/// Options for the NPC inspector's document pickers.
library;

import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import 'npc_catalog.dart';

/// Ids to pick from, with a label and a group heading for some of them.
class PickerOptions {
  const PickerOptions(this.ids, {this.labels = const <String, String>{}, this.groups = const <String, String>{}});

  final List<String> ids;
  final Map<String, String> labels;
  final Map<String, String> groups;
}

/// Behaviors an NPC can add: the workspace's NPC behaviors, then the
/// connected server's that the workspace lacks ([server], from its catalog),
/// leaving out the [listed] ones. Groups are named only when both kinds show.
PickerOptions behaviorPickerOptions(Iterable<AnimusDocument> documents, {required Iterable<String> listed, Iterable<String> server = const <String>[]}) {
  final Set<String> skip = listed.toSet();
  final Set<String> inWorkspace = <String>{
    for (final AnimusDocument document in documents)
      if (document.kind == DocumentKind.behavior) document.id,
  };
  final List<String> local = <String>[
    for (final String id in npcBehaviorIds(documents))
      if (!skip.contains(id)) id,
  ];
  final List<String> remote = <String>[
    for (final String id in server.toSet())
      if (!inWorkspace.contains(id) && !skip.contains(id)) id,
  ];
  return PickerOptions(
    <String>[...local, ...remote],
    groups: remote.isEmpty
        ? const <String, String>{}
        : <String, String>{
            for (final String id in local) id: 'Workspace',
            for (final String id in remote) id: 'On the server',
          },
  );
}

/// The workspace's shop ids, sorted.
List<String> shopPickerOptions(Iterable<AnimusDocument> documents) => <String>[
      for (final AnimusDocument document in documents)
        if (document.kind == DocumentKind.shop) document.id,
    ]..sort();
