/// The workspace around a GUI editor, for fields that name another
/// document (an action's chest GUI, shop or navigation target) and the kind
/// of document being edited. The document view provides one.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../model/animus_document.dart';
import '../../model/document_kind.dart';

class GuiTargets extends InheritedWidget {
  const GuiTargets({required this.documents, required this.kind, required super.child, super.key});

  final List<AnimusDocument> documents;

  /// The kind of the document being edited.
  final DocumentKind kind;

  static GuiTargets? of(BuildContext context) => context.dependOnInheritedComponentOfExactType<GuiTargets>();

  /// Ids of the workspace's [kind] documents.
  List<String> ids(DocumentKind kind) => <String>[
    for (final AnimusDocument document in documents)
      if (document.kind == kind) document.id,
  ];

  @override
  bool updateShouldNotify(GuiTargets oldComponent) => !identical(oldComponent.documents, documents) || oldComponent.kind != kind;
}
