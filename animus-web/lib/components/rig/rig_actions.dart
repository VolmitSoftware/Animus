/// Workspace actions shared by the shell and the rig editor: Blockbench
/// import and data-folder export.
library;

import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';

import '../../logic/bbmodel_import.dart';
import '../../logic/data_folder_export.dart';
import '../../logic/rig_edit.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../services/browser.dart';
import '../../state/editor_store.dart';
import '../../state/workspace_repository.dart';
import '../info_tip.dart';

/// Picks a `.bbmodel`, imports it as a new rig in [namespace] with its
/// animations and pack sources, and returns the rig id; null when cancelled
/// or refused (the reason is shown).
Future<String?> importBbmodelFile(EditorStore store, String namespace) async {
  final (String, Uint8List)? picked = await pickFile('.bbmodel,application/json');
  if (picked == null) return null;
  try {
    final String id = importRigId(store.workspace, namespace, picked.$1);
    final BbmodelImport imported = importBbmodel(picked.$2, rigId: id);
    await store.commit(
      documents: <DocumentChange>[
        DocumentChange(DocumentKind.rig, id, encodeDocument(imported.rig)),
        for (final JsonMap animation in imported.animations)
          DocumentChange(DocumentKind.animation, animation['id']! as String, encodeDocument(animation)),
      ],
      resources: imported.resources,
    );
    ArcaneSonner.success('Imported ${picked.$1} as $id: ${bonesOf(imported.rig).length} bones, ${imported.animations.length} animations.');
    for (final String warning in imported.warnings.take(4)) {
      ArcaneSonner.warning(warning);
    }
    return id;
  } on BbmodelImportException catch (refused) {
    ArcaneSonner.error('Could not import: ${refused.message}');
    return null;
  }
}

/// A free rig id for an imported file: `<namespace>:<file name>`, suffixed
/// when taken.
String importRigId(WorkspaceSnapshot? workspace, String namespace, String fileName) {
  final String base = '$namespace:${sanitizeName(fileName.replaceAll(RegExp(r'\.bbmodel$', caseSensitive: false), ''), 'model')}';
  bool taken(String id) =>
      workspace?.documents.any((AnimusDocument document) => document.kind == DocumentKind.rig && document.id == id) ?? false;
  if (!taken(base)) return base;
  for (int suffix = 2;; suffix++) {
    if (!taken('$base-$suffix')) return '$base-$suffix';
  }
}

/// Downloads the whole workspace as a data-folder zip.
void exportWorkspace(WorkspaceSnapshot workspace) {
  final String name = '${sanitizeName(workspace.name, 'animus')}.zip';
  downloadBytes(name, zipFiles(workspaceFiles(workspace)));
  ArcaneSonner.success(
    'Saved $name: ${workspace.documents.length} documents, ${workspace.resources.length} pack files. Unzip over plugins/Animus.',
  );
}

/// The rig editor's file buttons: Blockbench import and the server files
/// download, under one labelled caption.
class RigFileButtons extends StatelessWidget {
  const RigFileButtons({required this.busy, required this.onImport, required this.onDownload, super.key});

  /// True while an import runs.
  final bool busy;
  final void Function() onImport;
  final void Function() onDownload;

  @override
  Widget build(BuildContext context) => dom.div(classes: 'animus-rig-field', <Widget>[
        dom.span(<Widget>[
          withTip(
            const Component.text('Files'),
            'Import makes a new rig from a Blockbench model. Download zips this rig, its animations and pack files for the server.',
            docs: Docs.blockbench,
          ),
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          Button.secondary(label: 'Import .bbmodel', icon: ArcaneIcon.upload(), size: ButtonSize.small, disabled: busy, onPressed: onImport),
          Button.secondary(label: 'Download server files', icon: ArcaneIcon.download(), size: ButtonSize.small, onPressed: onDownload),
        ]),
      ]);
}
