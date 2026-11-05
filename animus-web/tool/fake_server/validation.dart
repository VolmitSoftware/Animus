import 'dart:convert';

import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';

import 'protocol.dart';

/// Kinds whose documents carry a `revision` the server increments on apply.
const Set<DocumentKind> revisioned = <DocumentKind>{
  DocumentKind.npc,
  DocumentKind.behavior,
  DocumentKind.shop,
  DocumentKind.inventory,
  DocumentKind.hologram,
  DocumentKind.presets,
  DocumentKind.rig,
  DocumentKind.animation,
};

Set<String> _strings(String key) =>
    <String>{for (final Object? value in endpointTable[key]! as List<Object?>) value! as String};

/// Behavior capabilities and GUI action types a preview never runs.
final Set<String> refusedCapabilities = _strings('previewRefusedCapabilities');
final Set<String> refusedGuiActions = _strings('previewRefusedGuiActions');

Map<String, Object?> diagnostic(String severity, String path, String message, [String? code]) {
  final String bounded = message.trim().isEmpty ? 'invalid' : message;
  String? cleaned;
  if (code != null) {
    cleaned = code.toLowerCase().replaceAll(RegExp(r'[^a-z0-9-]'), '-');
    if (cleaned.length > 64) cleaned = cleaned.substring(0, 64);
  }
  return <String, Object?>{
    'severity': severity,
    'path': path.startsWith(r'$') ? path : r'$',
    'message': bounded.length > 2048 ? bounded.substring(0, 2048) : bounded,
    if (cleaned != null && cleaned.isNotEmpty) 'code': cleaned,
  };
}

bool hasErrors(List<Map<String, Object?>> diagnostics) =>
    diagnostics.any((Map<String, Object?> entry) => entry['severity'] == 'error');

/// The editor's own checks for one candidate: the shared schema, the GUI and
/// rig checks and the id-against-location checks (validateSource).
List<Map<String, Object?>> validateDocument(DocumentKind kind, String id, String path, Map<String, Object?> source) {
  final AnimusDocument document = validateSource(DocumentLocation(kind, id), path, jsonEncode(source), SharedSchemas.embedded);
  return <Map<String, Object?>>[
    for (final Diagnostic found in document.diagnostics)
      if (found.code != 'no-schema') diagnostic(found.severity.name, found.path, found.message, found.code),
  ];
}

Map<String, Object?> documentDiagnostics(DocumentKind kind, String id, List<Map<String, Object?>> diagnostics) =>
    <String, Object?>{'kind': kind.wireName, 'id': id, 'diagnostics': diagnostics};

/// Field diagnostics for everything in a candidate that a preview refuses,
/// exactly as EditorPreviews.refusals.
List<Map<String, Object?>> refusals(DocumentKind kind, Map<String, Object?> source) {
  final List<Map<String, Object?>> diagnostics = <Map<String, Object?>>[];
  switch (kind) {
    case DocumentKind.behavior:
      _scanCapabilities(source, r'$', diagnostics);
    case DocumentKind.inventory:
    case DocumentKind.menu:
    case DocumentKind.hologram:
      _scanActions(source, r'$', false, diagnostics);
    case DocumentKind.shop:
      diagnostics.add(diagnostic('error', r'$', 'shops move items and money, so a preview cannot run them; apply to try a shop', 'preview-refused'));
    default:
      break;
  }
  return diagnostics;
}

void _scanCapabilities(Object? value, String at, List<Map<String, Object?>> diagnostics) {
  if (value is Map<String, Object?>) {
    final Object? capability = value['capability'];
    if (capability is String && refusedCapabilities.contains(capability)) {
      diagnostics.add(diagnostic('error', '$at.capability', '$capability does not run in a preview', 'preview-refused'));
    }
    for (final MapEntry<String, Object?> entry in value.entries) {
      _scanCapabilities(entry.value, '$at.${entry.key}', diagnostics);
    }
  } else if (value is List<Object?>) {
    for (int index = 0; index < value.length; index++) {
      _scanCapabilities(value[index], '$at[$index]', diagnostics);
    }
  }
}

void _scanActions(Object? value, String at, bool inActions, List<Map<String, Object?>> diagnostics) {
  if (value is Map<String, Object?>) {
    final Object? type = value['type'];
    if (inActions && type is String && refusedGuiActions.contains(type)) {
      diagnostics.add(diagnostic('error', '$at.type', "action '$type' does not run in a preview", 'preview-refused'));
    }
    for (final MapEntry<String, Object?> entry in value.entries) {
      _scanActions(entry.value, '$at.${entry.key}', inActions || entry.key == 'actions' || entry.key.endsWith('Actions'), diagnostics);
    }
  } else if (value is List<Object?>) {
    for (int index = 0; index < value.length; index++) {
      _scanActions(value[index], '$at[$index]', inActions, diagnostics);
    }
  }
}
