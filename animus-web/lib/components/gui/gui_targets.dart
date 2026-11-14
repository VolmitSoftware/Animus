/// The workspace around a GUI editor, for fields that name another
/// document (an action's chest GUI, shop or navigation target) and the kind
/// of document being edited. The document view provides one. On a server
/// the library lists documents not read yet, so they can be picked too.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_text.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../state/library.dart';
import '../conversation/conversation_attach.dart' show LibraryOf;

/// An NPC with a place in a world, for "put it above this NPC" pickers.
class NpcPlace {
  const NpcPlace(this.id, this.name, this.world, this.x, this.y, this.z);

  final String id;
  final String name;
  final String world;
  final double x;
  final double y;
  final double z;
}

class GuiTargets extends InheritedWidget {
  const GuiTargets({required this.documents, required this.kind, this.library, required super.child, super.key});

  final List<AnimusDocument> documents;

  /// The kind of the document being edited.
  final DocumentKind kind;

  /// Every entry of a kind, read or not.
  final LibraryOf? library;

  static GuiTargets? of(BuildContext context) => context.dependOnInheritedComponentOfExactType<GuiTargets>();

  /// Ids of the workspace's [kind] documents, read or not.
  List<String> ids(DocumentKind kind) {
    final Set<String> seen = <String>{};
    return <String>[
      for (final AnimusDocument document in documents)
        if (document.kind == kind && seen.add(document.id)) document.id,
      for (final LibraryEntry entry in library?.call(kind) ?? const <LibraryEntry>[])
        if (seen.add(entry.id)) entry.id,
    ];
  }

  /// The NPCs that stand in a world: the read documents, then the summaries
  /// of those not read yet.
  List<NpcPlace> npcPlaces() {
    final Set<String> seen = <String>{};
    final List<NpcPlace> places = <NpcPlace>[];
    for (final AnimusDocument document in documents) {
      if (document.kind != DocumentKind.npc) continue;
      final JsonMap json = asMap(document.json);
      final JsonMap at = asMap(json['position']);
      final String? world = asString(at['world']);
      if (world == null || !seen.add(document.id)) continue;
      final String name = guiPlainText(asString(json['name']) ?? '').trim();
      places.add(NpcPlace(document.id, name.isEmpty ? document.id : name, world, asDouble(at['x']) ?? 0, asDouble(at['y']) ?? 0, asDouble(at['z']) ?? 0));
    }
    for (final LibraryEntry entry in library?.call(DocumentKind.npc) ?? const <LibraryEntry>[]) {
      final String? world = entry.summary.world;
      if (world == null || !seen.add(entry.id)) continue;
      places.add(NpcPlace(entry.id, entry.name, world, entry.summary.x ?? 0, entry.summary.y ?? 0, entry.summary.z ?? 0));
    }
    return places;
  }

  @override
  bool updateShouldNotify(GuiTargets oldComponent) => !identical(oldComponent.documents, documents) || oldComponent.kind != kind || oldComponent.library != library;
}
