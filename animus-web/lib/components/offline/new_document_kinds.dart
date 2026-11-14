/// Which kinds the New page offers, as plain data so it is unit tested.
library;

import '../../logic/offline/new_documents.dart';
import '../../model/document_kind.dart';
import '../../ui/editor_mode.dart';

/// The kinds Simple mode offers: the Simple nav's jobs that can be made in
/// the browser (recordings are made in game).
const List<DocumentKind> simpleNewKinds = <DocumentKind>[
  DocumentKind.npc,
  DocumentKind.behavior,
  DocumentKind.shop,
  DocumentKind.route,
  DocumentKind.hologram,
];

/// Kinds whose id is fixed or worked out from the content, so the form
/// asks for none.
const Set<DocumentKind> fixedIdKinds = <DocumentKind>{DocumentKind.skin, DocumentKind.presets, DocumentKind.serverBehaviors, DocumentKind.recording};

/// The Kind picker's entries: Simple's kinds or every creatable one, with
/// [current] kept when the address names a kind the mode does not list.
List<DocumentKind> newPageKinds(EditorMode mode, {DocumentKind? current}) {
  final List<DocumentKind> base = mode == EditorMode.advanced ? creatableKinds : simpleNewKinds;
  if (current == null || base.contains(current)) return base;
  return <DocumentKind>[...base, current];
}

