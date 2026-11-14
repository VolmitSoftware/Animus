/// Workspace actions shared by the shell and the rig editor (Blockbench
/// import, data-folder export) and the rig editor's toolbar.
library;

import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../config/docs_links.dart';

import '../../logic/bbmodel_import.dart';
import '../../logic/data_folder_export.dart';
import '../../logic/npc/npc_appearance.dart' show bodyLabel;
import '../../logic/plural.dart';
import '../../logic/rig_edit.dart';
import '../../mc/rigs/mc_rigs.dart' show mcBodyTypes;
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../services/browser.dart';
import '../../state/editor_store.dart';
import '../../state/workspace_repository.dart';
import '../../ui/action_row.dart';
import '../../ui/info_tip.dart';
import '../../ui/toggle_choice.dart';
import '../select/animus_select.dart';
import '../npc/npc_layout.dart';

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
    ArcaneSonner.success('Imported ${picked.$1} as $id: ${countOf(bonesOf(imported.rig).length, 'bone')}, ${countOf(imported.animations.length, 'animation')}.');
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
  ArcaneSonner.success(exportSummary(name, workspace));
}

/// The rig editor's toolbar: whether the NPC's own body shows under the
/// rig (and which body the preview draws then), Blockbench import and the
/// server files download. One row that never wraps; the file actions fold
/// into "..." when it is narrow.
class RigToolbar extends StatelessWidget {
  const RigToolbar({
    required this.bodyShown,
    required this.previewBody,
    required this.busy,
    required this.onBody,
    required this.onPreviewBody,
    required this.onImport,
    required this.onDownload,
    super.key,
  });

  /// The rig keeps the NPC's body (`body: shown`).
  final bool bodyShown;
  final String previewBody;

  /// True while an import runs.
  final bool busy;
  final void Function(bool shown) onBody;
  final void Function(String type) onPreviewBody;
  final void Function() onImport;
  final void Function() onDownload;

  @override
  Widget build(BuildContext context) => ActionRow(
        dense: true,
        label: 'Rig',
        moreId: 'rig-toolbar-more',
        leading: <Widget>[
          // The switch keeps its width, so a narrow toolbar folds its file
          // actions into "..." instead of squeezing it.
          wrapItem('none', ToggleChoice<bool>(
              label: 'Body',
              value: bodyShown,
              options: const <ChoiceOption<bool>>[ChoiceOption<bool>(false, 'Rig only'), ChoiceOption<bool>(true, 'With body')],
              onChanged: onBody,
            )),
          if (bodyShown)
            wrapItem('0 1 10rem', AnimusSelect.values(
                value: previewBody,
                values: mcBodyTypes,
                labels: <String, String>{for (final String type in mcBodyTypes) type: bodyLabel(type)},
                hiddenLabel: 'Preview body',
                onChanged: (String? value) {
                  if (value != null) onPreviewBody(value);
                },
              ), minWidth: '5rem'),
          const InfoButton(
            tip: 'Rig only hides the NPC\'s body and keeps its hitbox. With body keeps the body and the rig adds parts; the preview body is drawn here only.',
            docs: Docs.rigs,
          ),
        ],
        secondary: <RowAction>[
          RowAction('Import .bbmodel', busy ? null : onImport, icon: ArcaneIcon.upload(), loading: busy),
          RowAction('Download server files', onDownload, icon: ArcaneIcon.download()),
        ],
      );
}
