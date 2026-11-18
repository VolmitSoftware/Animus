/// What Search offers besides the files: the editor's actions and settings,
/// each findable by the words people type ("pair", "undo", "apply",
/// "theme"), and the items a file uses, so "bread" finds the bakery. Pure
/// Dart.
library;

import 'command_search.dart';
import 'item_ids.dart' show itemName;
import 'minecraft_ids.dart';

/// Keys whose value is an item: an id string or `{"id": ...}`.
const Set<String> _itemKeys = <String>{'item', 'icon'};

/// The item ids [json] uses, namespaced, once each, in the order met: item
/// and icon values and equipment slots. Other ids (entity types, sounds)
/// are not items and are left out.
List<String> itemMentions(Object? json) {
  final Set<String> found = <String>{};
  void take(Object? value) {
    final Object? id = value is Map<String, Object?> ? value['id'] : value;
    if (id is String && id.isNotEmpty && validMinecraftId(id)) found.add(normalizeMinecraftId(id));
  }

  void walk(Object? value, String? parentKey) {
    if (value is Map<String, Object?>) {
      for (final MapEntry<String, Object?> entry in value.entries) {
        if (_itemKeys.contains(entry.key) || parentKey == 'equipment') take(entry.value);
        walk(entry.value, entry.key);
      }
    } else if (value is List<Object?>) {
      for (final Object? each in value) {
        walk(each, parentKey);
      }
    }
  }

  walk(json, null);
  return found.toList();
}

/// The words Search matches [ids] by: each item's name ("Golden apple").
List<String> itemSearchTerms(List<String> ids) => <String>[for (final String id in ids) itemName(id)];

/// What the palette's commands run. A null run leaves its command out, so a
/// page without a workspace has no Undo and a local workspace no Apply.
class PaletteRuns {
  const PaletteRuns({
    required this.pair,
    required this.home,
    required this.mode,
    required this.shortcuts,
    required this.theme,
    required this.openFolder,
    required this.openZip,
    required this.importer,
    this.undo,
    this.redo,
    this.apply,
    this.review,
    this.send,
    this.newFile,
    this.exportZip,
    this.exportGloss,
    this.importBbmodel,
    this.importGloss,
  });

  final void Function() pair;
  final void Function() home;
  final void Function() mode;
  final void Function() shortcuts;
  final void Function() theme;
  final void Function() openFolder;
  final void Function() openZip;
  final void Function() importer;
  final void Function()? undo;
  final void Function()? redo;

  /// A server workspace: Apply as the save bar does, and Review.
  final void Function()? apply;
  final void Function()? review;

  /// A local workspace: Send to server.
  final void Function()? send;
  final void Function()? newFile;
  final void Function()? exportZip;
  final void Function()? exportGloss;
  final void Function()? importBbmodel;
  final void Function()? importGloss;
}

/// The editor's own commands. [advanced] adds the Gloss and Blockbench
/// files and flips the mode command; [dark] names the theme to switch to;
/// [undoLabel] and [redoLabel] say what Undo and Redo revert; [keys] writes
/// a shortcut for this keyboard; [importLabel] names the import page.
List<PaletteCommand> actionCommands(
  PaletteRuns runs, {
  required bool advanced,
  required bool dark,
  required String Function(String keys) keys,
  required String importLabel,
  String? undoLabel,
  String? redoLabel,
}) =>
    <PaletteCommand>[
      if (runs.undo case final void Function() undo)
        PaletteCommand('Edit', undoLabel == null ? 'Undo' : 'Undo: $undoLabel', undo, hint: keys('Mod+Z'), keywords: const <String>['undo', 'revert']),
      if (runs.redo case final void Function() redo)
        PaletteCommand('Edit', redoLabel == null ? 'Redo' : 'Redo: $redoLabel', redo, hint: keys('Mod+Shift+Z'), keywords: const <String>['redo']),
      if (runs.apply case final void Function() apply)
        PaletteCommand('Server', 'Apply changes', apply, hint: keys('Mod+S'), keywords: const <String>['apply', 'save', 'upload']),
      if (runs.review case final void Function() review)
        PaletteCommand('Server', 'Review changes', review, keywords: const <String>['review', 'apply', 'compare', 'conflicts']),
      if (runs.send case final void Function() send) PaletteCommand('Server', 'Send to server', send, keywords: const <String>['apply', 'upload']),
      PaletteCommand('Server', 'Pair a server', runs.pair, keywords: const <String>['pair', 'link', 'connect']),
      if (runs.newFile case final void Function() newFile) PaletteCommand('Create', 'New file', newFile, hint: 'N', keywords: const <String>['create', 'add']),
      if (runs.exportZip case final void Function() exportZip) PaletteCommand('Files', 'Export zip', exportZip, keywords: const <String>['download', 'backup']),
      if (advanced) ...<PaletteCommand>[
        if (runs.exportGloss case final void Function() exportGloss) PaletteCommand('Files', 'Export Gloss bundle', exportGloss, keywords: const <String>['download']),
        if (runs.importBbmodel case final void Function() importBbmodel)
          PaletteCommand('Files', 'Import Blockbench model', importBbmodel, keywords: const <String>['bbmodel', 'rig']),
        if (runs.importGloss case final void Function() importGloss) PaletteCommand('Files', 'Import Gloss bundle', importGloss),
      ],
      PaletteCommand('Files', 'Open folder', runs.openFolder, keywords: const <String>['plugins/animus']),
      PaletteCommand('Files', 'Open zip', runs.openZip),
      PaletteCommand('Files', importLabel, runs.importer, keywords: const <String>['citizens', 'shopkeepers', 'denizen']),
      PaletteCommand('Settings', advanced ? 'Switch to Simple' : 'Switch to Advanced', runs.mode, keywords: const <String>['mode', 'simple', 'advanced', 'settings']),
      PaletteCommand('Settings', 'Keyboard shortcuts', runs.shortcuts, hint: '?', keywords: const <String>['keys', 'help', 'settings']),
      PaletteCommand('Settings', dark ? 'Light theme' : 'Dark theme', runs.theme, keywords: const <String>['theme', 'dark', 'light', 'colors', 'settings']),
      PaletteCommand('Switch', 'All workspaces', runs.home, keywords: const <String>['home', 'start']),
    ];
