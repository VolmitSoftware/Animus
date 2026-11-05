import 'dart:convert';

import 'document_kind.dart';

enum DiagnosticSeverity { error, warning, info }

/// A field diagnostic, shaped like `diagnostic` in editor-v1.schema.json.
class Diagnostic {
  const Diagnostic(this.severity, this.path, this.message, {this.code});

  final DiagnosticSeverity severity;
  final String path;
  final String message;
  final String? code;

  Map<String, Object?> toJson() => <String, Object?>{
        'severity': severity.name,
        'path': path,
        'message': message,
        if (code != null) 'code': code,
      };

  static Diagnostic fromJson(Map<String, Object?> json) => Diagnostic(
        DiagnosticSeverity.values.byName(json['severity']! as String),
        json['path']! as String,
        json['message']! as String,
        code: json['code'] as String?,
      );

  @override
  String toString() => '${severity.name} $path: $message';
}

/// One document in a workspace. [source] is the exact text; [json] is its
/// parsed value, kept whole so unknown keys survive a round trip.
class AnimusDocument {
  const AnimusDocument({
    required this.kind,
    required this.id,
    required this.path,
    required this.source,
    required this.json,
    required this.diagnostics,
  });

  final DocumentKind kind;
  final String id;
  final String path;
  final String source;
  final Object? json;
  final List<Diagnostic> diagnostics;

  bool get valid => !diagnostics.any((Diagnostic diagnostic) => diagnostic.severity == DiagnosticSeverity.error);

  /// A display name: an NPC's name, a shop's title, otherwise the id.
  String get title {
    final Object? value = json;
    if (value is Map<String, Object?>) {
      final Object? name = kind == DocumentKind.npc ? value['name'] : (kind == DocumentKind.pack ? value['name'] : null);
      if (name is String && name.trim().isNotEmpty) return name;
    }
    return id;
  }

  AnimusDocument copyWith({String? source, Object? json, List<Diagnostic>? diagnostics}) => AnimusDocument(
        kind: kind,
        id: id,
        path: path,
        source: source ?? this.source,
        json: json ?? this.json,
        diagnostics: diagnostics ?? this.diagnostics,
      );

  Map<String, Object?> toJson() => <String, Object?>{
        'kind': kind.wireName,
        'id': id,
        'path': path,
        'source': source,
        'diagnostics': <Map<String, Object?>>[for (final Diagnostic diagnostic in diagnostics) diagnostic.toJson()],
      };

  static AnimusDocument fromJson(Map<String, Object?> json) {
    final String source = json['source']! as String;
    Object? parsed;
    try {
      parsed = jsonDecode(source);
    } on FormatException {
      parsed = null;
    }
    return AnimusDocument(
      kind: DocumentKind.parse(json['kind']! as String)!,
      id: json['id']! as String,
      path: json['path']! as String,
      source: source,
      json: parsed,
      diagnostics: <Diagnostic>[
        for (final Object? diagnostic in json['diagnostics']! as List<Object?>)
          Diagnostic.fromJson(diagnostic! as Map<String, Object?>),
      ],
    );
  }
}
