/// Who answers a click on an NPC, in the order the server asks them
/// (`ViewerPresenter.interact`): every behavior with an interaction trigger
/// for that click starts, and one whose trigger has a `consume` check keeps
/// the click while it holds; then the first of hiring, a quest that has
/// something for the player, the shop and the GUI opens; riding, then the
/// seat, get only a plain right-click nothing else took. Pure Dart.
library;

import '../gui/gui_json.dart';
import 'mount_seat.dart';

/// A plain click: no sneaking, as most players click.
enum NpcClick {
  right('Right-click'),
  left('Left-click');

  const NpcClick(this.label);

  final String label;
}

/// What answers one kind of click on an NPC.
class ClickAnswers {
  const ClickAnswers(this.answering, this.line, {this.conflict = false});

  /// Everything that answers, in the order the server asks: behaviors by
  /// name, then hiring, quests, the shop, the GUI, riding and the seat.
  final List<String> answering;

  /// One sentence on who gets the click; null when one thing or nothing
  /// answers it.
  final String? line;

  /// Something answers that never gets the click.
  final bool conflict;
}

/// The trigger actions a plain [click] fires.
Set<String> _actions(NpcClick click) => switch (click) {
      NpcClick.right => const <String>{'interact', 'any'},
      NpcClick.left => const <String>{'attack', 'any'},
    };

/// Whether a click module's `openOn` (right by default) takes [click].
bool _opensOn(Object? openOn, NpcClick click) => switch (openOn) {
      'both' => true,
      'left' => click == NpcClick.left,
      _ => click == NpcClick.right,
    };

bool _on(Object? module) => module is Map<String, Object?> && module['enabled'] != false;

/// [names] as a list read aloud: "A", "A and B", "A, B and C".
String _spoken(List<String> names) => names.length < 2 ? names.join() : '${names.sublist(0, names.length - 1).join(', ')} and ${names.last}';

String _capital(String text) => text.isEmpty ? text : '${text[0].toUpperCase()}${text.substring(1)}';

/// A definite opener: its name, what the line says when it wins, and what
/// it says after a quest's "else".
typedef _Opener = ({String name, String wins, String otherwise});

/// What answers [click] on [npc], its definition with its trait groups
/// merged in. [behaviors] holds the workspace's behaviors by id; a behavior
/// it lacks is left out. [questNames] are the quests that give, take in or
/// have a talk objective at this NPC.
ClickAnswers clickAnswers(
  JsonMap npc,
  NpcClick click, {
  required Map<String, JsonMap> behaviors,
  required String Function(String id) behaviorName,
  required String Function(String id) shopName,
  Set<String> questNames = const <String>{},
}) {
  final Set<String> actions = _actions(click);
  final List<String> starting = <String>[];
  final List<String> consuming = <String>[];
  for (final Object? id in asList(npc['behaviors'])) {
    if (id is! String) continue;
    final JsonMap? behavior = behaviors[id];
    if (behavior == null) continue;
    final List<JsonMap> clicks = <JsonMap>[
      for (final Object? trigger in asList(behavior['triggers']))
        if (asMap(trigger)['event'] == 'interaction' && actions.contains(asString(asMap(trigger)['action']) ?? 'interact')) asMap(trigger),
    ];
    if (clicks.isEmpty) continue;
    starting.add(behaviorName(id));
    if (clicks.any((JsonMap trigger) => trigger.containsKey('consume'))) consuming.add(behaviorName(id));
  }
  final JsonMap modules = asMap(npc['modules']);
  final List<String> quests = click == NpcClick.right ? (questNames.toList()..sort()) : const <String>[];
  final List<_Opener> after = <_Opener>[
    if (click == NpcClick.right && _on(modules['animus:hire'])) (name: 'hiring', wins: 'opens hiring', otherwise: 'hiring'),
  ];
  final List<_Opener> later = <_Opener>[
    if (asString(asMap(modules['animus:shop'])['shop']) case final String shop when shop.isNotEmpty && _opensOn(asMap(modules['animus:shop'])['openOn'], click))
      (name: shopName(shop), wins: 'opens ${shopName(shop)}', otherwise: shopName(shop)),
    if (asMap(modules['animus:gui'])['open'] is Map<String, Object?> && _opensOn(asMap(asMap(modules['animus:gui'])['open'])['openOn'], click))
      (name: 'the GUI', wins: 'opens the GUI', otherwise: 'the GUI'),
    if (click == NpcClick.right && ridingOn(modules)) (name: 'riding', wins: 'lets the player ride it', otherwise: 'the player rides it'),
    if (click == NpcClick.right && seatOn(modules[seatModuleKey]) && asString(asMap(modules[seatModuleKey])['click']) != 'sneak-right')
      (name: 'the seat', wins: 'seats the player', otherwise: 'the player sits on it'),
  ];
  // Hiring is asked before quests; whatever comes first after them wins.
  final List<_Opener> definite = <_Opener>[...after, ...later];
  final List<String> answering = <String>[
    ...starting,
    ...after.map((_Opener opener) => opener.name),
    ...quests,
    ...later.map((_Opener opener) => opener.name),
  ];
  if (answering.length < 2) return ClickAnswers(answering, null);
  final List<String> parts = <String>[];
  if (starting.isNotEmpty) parts.add('starts ${_spoken(starting)}');
  final List<String> blocked;
  if (after.isNotEmpty) {
    // Hiring takes every right-click before a quest is asked.
    parts.add(after.first.wins);
    blocked = <String>[...quests, ...definite.skip(1).map((_Opener opener) => opener.name)];
  } else if (quests.isNotEmpty) {
    final String quest = quests.length == 1 ? '${quests.single} when it has' : '${quests.join(' or ')} when one has';
    final _Opener? next = definite.firstOrNull;
    parts.add('opens $quest something for the player${next == null ? '' : ', else ${next.otherwise}'}');
    blocked = <String>[for (final _Opener opener in definite.skip(1)) opener.name];
  } else if (definite.isNotEmpty) {
    parts.add(definite.first.wins);
    blocked = <String>[for (final _Opener opener in definite.skip(1)) opener.name];
  } else {
    blocked = const <String>[];
  }
  final StringBuffer line = StringBuffer('${click.label} ${parts.join(', then ')}.');
  if (blocked.isNotEmpty) line.write(' ${_capital(_spoken(blocked))} never ${blocked.length == 1 ? 'gets' : 'get'} it.');
  if (consuming.isNotEmpty && answering.length > starting.length) {
    line.write(' ${_spoken(consuming)} ${consuming.length == 1 ? 'keeps' : 'keep'} the click when ${consuming.length == 1 ? 'its' : 'their'} consume check holds.');
  }
  return ClickAnswers(answering, line.toString(), conflict: blocked.isNotEmpty);
}

/// Whether [quest] answers a click on the NPC with local id [npc]: it gives
/// the quest, takes it in, or has a talk objective there.
bool questAsks(JsonMap quest, String npc) {
  if (asList(quest['givers']).contains(npc) || quest['turnIn'] == npc) return true;
  for (final Object? stage in asList(quest['stages'])) {
    for (final Object? objective in asList(asMap(stage)['objectives'])) {
      if (asMap(objective)['npc'] == npc) return true;
    }
  }
  return false;
}
