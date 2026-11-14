/// Which NPCs walk which route: an NPC's `animus:navigation` either holds
/// points of its own (an inline route), names a route document, or plays a
/// recording. The catalog summary only says "route" for both of the first
/// two, so these read the NPC document itself.
library;

import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../gui/gui_json.dart';
import 'route_model.dart';

JsonMap _navigation(Object? npc) => asMap(asMap(asMap(npc)['modules'])[navigationModule]);

/// The NPC's own route: its navigation module when it has points and names
/// no route document; else null.
JsonMap? inlineRouteOf(Object? npc) {
  final JsonMap navigation = _navigation(npc);
  if (navigation['route'] is String || navigation['recording'] is String) return null;
  return asList(navigation['points']).isEmpty ? null : navigation;
}

/// The route document the NPC's navigation names, or null.
String? routeIdOf(Object? npc) => asString(_navigation(npc)['route']);

/// The recording the NPC's navigation plays, or null.
String? recordingIdOf(Object? npc) => asString(_navigation(npc)['recording']);

/// The NPCs of [documents] whose navigation names route [id], in order.
List<AnimusDocument> routeUsers(Iterable<AnimusDocument> documents, String id) => <AnimusDocument>[
      for (final AnimusDocument document in documents)
        if (document.kind == DocumentKind.npc && routeIdOf(document.json) == id) document,
    ];

/// Where an NPC stands: its world, and x, y and z when all three are set.
typedef RouteUserPlace = ({String? world, (double, double, double)? origin});

RouteUserPlace routeUserPlace(Object? npc) {
  final JsonMap position = asMap(asMap(npc)['position']);
  final double? x = asDouble(position['x']), y = asDouble(position['y']), z = asDouble(position['z']);
  return (world: asString(position['world']), origin: x == null || y == null || z == null ? null : (x, y, z));
}

/// `Used by Porter`, `Used by Porter and Guard`, `Used by Porter and 2 more`;
/// null for no users.
String? usedByLine(List<String> names) => switch (names.length) {
      0 => null,
      1 => 'Used by ${names.first}',
      2 => 'Used by ${names.first} and ${names.last}',
      _ => 'Used by ${names.first} and ${names.length - 1} more',
    };
