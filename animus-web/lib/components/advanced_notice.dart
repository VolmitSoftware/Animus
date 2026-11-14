/// What Simple says when documents arrive that only Advanced lists: an
/// opened folder or zip, an import, or a document the paired server gained.
/// One sentence with the count, beside one Switch to Advanced action that
/// lands on them. Home and lists that show nothing only because of those
/// kinds say how many there are instead of a plain empty state.
library;

import '../logic/plural.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../state/workspace_route.dart';
import '../ui/editor_mode.dart';
import 'shell_sidebar.dart' show kindShown, listedKinds;

/// One document that arrived.
typedef ArrivedDocument = ({DocumentKind kind, String id});

/// [documents] as arrivals.
List<ArrivedDocument> arrivedOf(List<AnimusDocument> documents) => <ArrivedDocument>[
      for (final AnimusDocument document in documents) (kind: document.kind, id: document.id),
    ];

/// Where documents came from.
enum Arrival {
  /// An opened folder or zip, or an import.
  imported,

  /// A document the paired server gained.
  server,
}

bool _hidden(DocumentKind kind) => !kindShown(kind, EditorMode.simple);

/// The sentence and the documents Switch to Advanced lands on.
final class AdvancedNotice {
  const AdvancedNotice(this.message, this.hidden);

  final String message;

  /// The arrived documents only Advanced lists, in arrival order.
  final List<ArrivedDocument> hidden;

  /// The one hidden document, else the list of the first hidden kind.
  EditorRoute landing(String workspaceId) {
    final ArrivedDocument first = hidden.first;
    return hidden.length == 1 ? DocumentRoute(workspaceId, first.kind, first.id) : KindListRoute(workspaceId, first.kind);
  }
}

String _isAre(int count) => count == 1 ? 'is' : 'are';

/// The notice for [documents], or null when Simple lists all of them.
AdvancedNotice? advancedNotice(Arrival arrival, List<ArrivedDocument> documents) {
  final List<ArrivedDocument> hidden = <ArrivedDocument>[
    for (final ArrivedDocument document in documents)
      if (_hidden(document.kind)) document,
  ];
  if (hidden.isEmpty) return null;
  final int count = hidden.length;
  final String message = switch (arrival) {
    Arrival.imported when count == documents.length => '${countOf(count, 'imported document')} ${_isAre(count)} in Advanced.',
    Arrival.imported => '$count of ${countOf(documents.length, 'imported document')} ${_isAre(count)} in Advanced.',
    Arrival.server => '${countOf(count, 'new document')} ${_isAre(count)} in Advanced.',
  };
  return AdvancedNotice(message, hidden);
}

/// What Home or a list says when everything it would show is in Advanced.
String hiddenDocumentsText(int count) => '${countOf(count, 'document')} ${_isAre(count)} in Advanced.';

/// What Search says when only documents Advanced lists match.
String hiddenMatchesText(int count) => '${countOf(count, 'match', plural: 'matches')} ${_isAre(count)} in Advanced.';

/// How many documents [counts] holds when Simple lists none of them; 0 when
/// Simple lists one or there are none.
int hiddenOnly(Map<DocumentKind, int> counts) {
  int hidden = 0;
  for (final MapEntry<DocumentKind, int> entry in counts.entries) {
    if (entry.value <= 0) continue;
    if (!_hidden(entry.key)) return 0;
    hidden += entry.value;
  }
  return hidden;
}

/// The list of the first kind in [counts] only Advanced lists, in list
/// order; null when there is none.
EditorRoute? hiddenLanding(String workspaceId, Map<DocumentKind, int> counts) {
  for (final DocumentKind kind in listedKinds) {
    if (_hidden(kind) && (counts[kind] ?? 0) > 0) return KindListRoute(workspaceId, kind);
  }
  return null;
}

/// Counts arrivals of one source together while each comes within [window]
/// of the last, so server news written in several events reads as one
/// arrival; a later one starts its own count. A document heard twice counts
/// once.
final class ArrivalNotices {
  ArrivalNotices({required this.window, DateTime Function()? clock}) : _clock = clock ?? DateTime.now;

  /// The longest pause between arrivals of one batch.
  final Duration window;
  final DateTime Function() _clock;

  Arrival? _source;
  DateTime? _at;
  final Map<String, ArrivedDocument> _documents = <String, ArrivedDocument>{};

  /// Adds [documents] and returns the notice for everything counted so far,
  /// or null when Simple lists all of it.
  AdvancedNotice? add(Arrival arrival, List<ArrivedDocument> documents) {
    final DateTime now = _clock();
    final DateTime? at = _at;
    if (arrival != _source || at == null || now.difference(at) > window) {
      _documents.clear();
      _source = arrival;
    }
    _at = now;
    for (final ArrivedDocument document in documents) {
      _documents['${document.kind.wireName}:${document.id}'] = document;
    }
    return advancedNotice(arrival, _documents.values.toList());
  }
}
