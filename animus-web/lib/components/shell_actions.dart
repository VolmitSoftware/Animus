/// The shell's file and document actions: open a folder or zip, Gloss and
/// Blockbench import and export, and moving, copying and deleting a
/// document with its references. A mixin on the shell's state, so the
/// actions share its busy flag and navigation.
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../logic/data_folder.dart';
import '../logic/gui/gloss_bundle.dart';
import '../logic/gui/gui_json.dart';
import '../logic/offline/new_documents.dart';
import '../logic/plural.dart';
import '../logic/recording/rename_references.dart';
import '../model/animus_document.dart';
import '../model/display_name.dart';
import '../model/document_kind.dart';
import '../services/browser.dart';
import '../state/editor_store.dart';
import '../state/live_session.dart';
import '../state/workspace_repository.dart';
import '../state/workspace_route.dart';
import '../ui/confirm_dialog.dart';
import 'advanced_notice.dart';
import 'gui/gui_fields.dart' show stringList;
import 'rig/rig_actions.dart';
import 'rig/rig_editor.dart';
import 'shell.dart';

mixin WorkspaceActions on State<EditorShell> {
  /// A file pick or import is running; the open and import entries wait.
  bool busy = false;

  /// Why the last folder or zip opened nothing, shown on home.
  String? openProblem;

  EditorStore get store => component.store;

  /// In Simple, says how many of [documents] only Advanced lists, with
  /// Switch to Advanced; true when it did.
  bool announce(Arrival arrival, List<ArrivedDocument> documents, String workspaceId);

  Future<void> openFiles(Future<PickedFiles?> Function() pick, {required bool zip}) async {
    setState(() {
      busy = true;
      openProblem = null;
    });
    try {
      final PickedFiles? picked = await pick();
      if (picked == null) return;
      final WorkspaceSnapshot snapshot = await store.openFiles(picked.name, picked.files, requireDocuments: true, zip: zip);
      final int invalid = snapshot.documents.where((AnimusDocument document) => !document.valid).length;
      final int upgraded = store.upgraded.length;
      final String upgrades = upgraded == 0 ? '' : ' Upgraded ${countOf(upgraded, 'file')} to schemaVersion 2.';
      final bool told = announce(Arrival.imported, arrivedOf(snapshot.documents), snapshot.id);
      if (invalid > 0) {
        ArcaneSonner.warning('Opened ${countOf(snapshot.documents.length, 'document')}; $invalid ${invalid == 1 ? 'needs' : 'need'} repair.$upgrades');
      } else if (!told || upgraded > 0) {
        ArcaneSonner.success('Opened ${countOf(snapshot.documents.length, 'document')}.$upgrades');
      }
      component.onNavigate(WorkspaceRoute(snapshot.id));
      focusMainHeading();
    } on NoDocumentsFound catch (refused) {
      // Nothing was saved; home says why beside the open buttons.
      if (mounted) setState(() => openProblem = refused.message);
      if (component.route is! HomeRoute) ArcaneSonner.error(refused.message);
    } on Object catch (failure) {
      ArcaneSonner.error('Could not open the files ($failure). Pick plugins/Animus or a complete zip of it.');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  String _fileStem(WorkspaceSnapshot workspace) {
    final String stem = workspace.name.toLowerCase().replaceAll(RegExp(r'[^a-z0-9_-]+'), '-').replaceAll(RegExp(r'^-+|-+$'), '');
    return stem.isEmpty ? 'animus' : stem;
  }

  void exportGloss(WorkspaceSnapshot workspace) {
    final GlossExport exported = writeGlossBundle(workspace.documents);
    if (exported.exported == 0) {
      ArcaneSonner.warning('No chest GUIs, menus, holograms or presets Gloss can run.${exported.skipped.isEmpty ? '' : ' Skipped: ${exported.skipped.join('; ')}'}');
      return;
    }
    downloadBytes('${_fileStem(workspace)}.gloss-workspace.json', utf8.encode(exported.bundle), type: 'application/json');
    ArcaneSonner.success('Exported ${countOf(exported.exported, 'document')} as a Gloss bundle.'
        '${exported.skipped.isEmpty ? '' : ' Skipped ${exported.skipped.length}: ${exported.skipped.join('; ')}'}');
  }

  Future<void> importGloss() async {
    final (String, Uint8List)? picked = await pickFile('.json,application/json');
    if (picked == null) return;
    final GlossImport imported = readGlossBundle(utf8.decode(picked.$2, allowMalformed: true));
    if (imported.error != null) {
      ArcaneSonner.error(imported.error!);
      return;
    }
    final List<DocumentLocation> clashes = glossConflicts(imported, store.workspace?.documents ?? const <AnimusDocument>[]);
    if (clashes.isNotEmpty) {
      final String named = clashes.take(5).map((DocumentLocation l) => '${l.kind.label} ${l.id}').join(', ');
      final String more = clashes.length > 5 ? ' and ${clashes.length - 5} more' : '';
      final bool replace = await askToConfirm(
        title: 'Replace ${countOf(clashes.length, 'document')}?',
        message: '$named$more from ${picked.$1}. Undo brings them back.',
        confirmLabel: 'Replace and import',
        destructive: true,
      );
      if (!replace) return;
    }
    await _commitGloss(imported, picked.$1);
  }

  Future<void> _commitGloss(GlossImport imported, String file) async {
    await store.commit(
      documents: <DocumentChange>[
        for (final GlossImportedDocument document in imported.documents) DocumentChange(document.location.kind, document.location.id, document.source),
      ],
      label: 'import $file',
    );
    final int invalid = imported.documents.where((GlossImportedDocument d) => !(store.document(d.location.kind, d.location.id)?.valid ?? false)).length;
    final String skipped = imported.skipped.isEmpty ? '' : ' Skipped: ${imported.skipped.join('; ')}.';
    final String notes = imported.notes.isEmpty ? '' : ' ${imported.notes.join(' ')}';
    if (invalid > 0) {
      ArcaneSonner.warning('Imported ${countOf(imported.documents.length, 'document')} from $file; $invalid ${invalid == 1 ? 'uses' : 'use'} features Animus refuses.$skipped$notes');
    } else {
      ArcaneSonner.success('Imported ${countOf(imported.documents.length, 'document')} from $file.$skipped$notes');
    }
  }

  Future<void> importBbmodel(WorkspaceSnapshot workspace) async {
    setState(() => busy = true);
    try {
      String namespace = 'animus';
      for (final AnimusDocument document in workspace.documents) {
        if (document.kind == DocumentKind.rig && document.id.contains(':')) {
          namespace = document.id.substring(0, document.id.indexOf(':'));
          break;
        }
      }
      final String? id = await importBbmodelFile(store, namespace);
      if (id != null) component.onNavigate(DocumentRoute(workspace.id, DocumentKind.rig, id));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  /// Kinds whose id the user picks (a skin's is its content hash, presets
  /// have one fixed id).
  bool movable(DocumentKind kind) => kind != DocumentKind.skin && kind != DocumentKind.presets && kind != DocumentKind.serverBehaviors;

  /// Where [document] goes under [id]: a behavior inside a pack stays in its
  /// pack's folder.
  String _pathFor(AnimusDocument document, String id) {
    final int behaviors = document.path.indexOf('/behaviors/');
    if (document.kind == DocumentKind.behavior && document.path.startsWith('packs/') && behaviors > 0) {
      final int colon = id.indexOf(':');
      return '${document.path.substring(0, behaviors)}/behaviors/${id.substring(0, colon)}/${id.substring(colon + 1)}.json';
    }
    return newDocumentPath(document.kind, id);
  }

  /// Moves (or with [copy], copies) a document to [id] in one undo step. A
  /// move takes the references along: NPCs listing a renamed behavior or
  /// group, the animations and NPCs of a renamed rig, and the NPCs and routes
  /// that play a renamed recording.
  Future<String?> relocate(WorkspaceSnapshot workspace, AnimusDocument document, String id, {required bool copy}) async {
    final DocumentKind kind = document.kind;
    if (id == document.id) return copy ? 'Pick an id that is not taken.' : null;
    if (!validDocumentId(kind, id)) return 'Use ${idHint(kind)}.';
    if (store.document(kind, id) != null) return '${kind.label}: $id already exists.';
    final Object? json = document.json;
    final String source = json is Map<String, Object?> ? prettyJson(rewriteIdentity(kind, json, id, fresh: copy)) : document.source;
    final List<DocumentChange> changes = <DocumentChange>[
      if (!copy) DocumentChange(kind, document.id, null),
      DocumentChange(kind, id, source, path: _pathFor(document, id)),
    ];
    if (!copy) {
      for (final AnimusDocument other in workspace.documents) {
        final Object? value = other.json;
        if (value is! Map<String, Object?>) continue;
        if (kind == DocumentKind.behavior && other.kind == DocumentKind.npc && stringList(value['behaviors']).contains(document.id)) {
          changes.add(DocumentChange(other.kind, other.id, prettyJson(<String, Object?>{
            ...value,
            'behaviors': <String>[for (final String behavior in stringList(value['behaviors'])) behavior == document.id ? id : behavior],
          })));
        } else if (kind == DocumentKind.rig && other.kind == DocumentKind.animation && value['rig'] == document.id) {
          changes.add(DocumentChange(other.kind, other.id, prettyJson(<String, Object?>{...value, 'rig': id})));
        } else if (kind == DocumentKind.rig && other.kind == DocumentKind.npc && asMap(value['rig'])['id'] == document.id) {
          changes.add(DocumentChange(other.kind, other.id, prettyJson(<String, Object?>{...value, 'rig': <String, Object?>{...asMap(value['rig']), 'id': id}})));
        } else if (renamedReferences(kind, document.id, id, other.kind, value) case final JsonMap renamed) {
          changes.add(DocumentChange(other.kind, other.id, prettyJson(renamed)));
        }
      }
    }
    // Named as the lists name them, so Undo never reads an id.
    final String name = store.libraryEntry(kind, document.id)?.name ?? displayName(kind, document.id, null);
    await store.commit(documents: changes, label: copy ? 'duplicate $name' : 'rename $name');
    final int references = changes.length - (copy ? 1 : 2);
    ArcaneSonner.success(copy
        ? 'Copied $name.'
        : 'Renamed $name${references > 0 ? ' and updated $references reference${references == 1 ? '' : 's'}' : ''}.');
    component.onNavigate(DocumentRoute(workspace.id, kind, id));
    focusMainHeading();
    return null;
  }

  Future<void> deleteDocument(WorkspaceSnapshot workspace, AnimusDocument document) async {
    // The name the lists show, so Undo reads "Undo: delete Baker", not an id.
    final String name = store.libraryEntry(document.kind, document.id)?.name ?? document.id;
    await store.commit(documents: <DocumentChange>[DocumentChange(document.kind, document.id, null)], label: 'delete $name');
    component.onNavigate(WorkspaceRoute(workspace.id));
    focusMainHeading();
    ArcaneSonner.info('Deleted ${document.id}. Undo brings it back.');
  }

  /// The rig editor for a rig, or for an animation whose rig is here.
  Widget? rigEditor(WorkspaceSnapshot workspace, AnimusDocument document) {
    final Object? json = document.json;
    AnimusDocument? rig;
    String? animation;
    if (document.kind == DocumentKind.rig) {
      rig = document;
    } else if (document.kind == DocumentKind.animation && json is Map<String, Object?> && json['rig'] is String) {
      rig = store.document(DocumentKind.rig, json['rig']! as String);
      animation = document.id;
    }
    if (rig == null || rig.json is! Map<String, Object?>) return null;
    final DocumentRoute? current = component.route is DocumentRoute ? component.route as DocumentRoute : null;
    return RigEditor(
      key: ValueKey<String>('rig-editor:${rig.id}'),
      store: store,
      rig: rig,
      animationId: animation,
      onOpen: (DocumentKind kind, String id) => component.onNavigate(DocumentRoute(workspace.id, kind, id, current?.view ?? ViewMode.visual)),
    );
  }

  AnimusDocument? skinOf(AnimusDocument document) {
    final Object? json = document.json;
    if (document.kind != DocumentKind.npc || json is! Map<String, Object?> || json['skin'] is! String) return null;
    final String hash = json['skin']! as String;
    final AnimusDocument? skin = store.document(DocumentKind.skin, hash);
    if (skin == null && store.workspace?.server != null) component.session?.ensureLoaded(<DocumentRef>[DocumentRef(DocumentKind.skin, hash)]);
    return skin;
  }
}
