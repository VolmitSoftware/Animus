/// Which [FieldProfile] a behavior field gets: capability arguments by
/// capability and key, trigger filters by key. The placeholders follow the
/// built-in actions (wiki animus/07-behaviors): `{player_name}` in say,
/// message, actionbar and title text, and `{player}` too in commands.
library;

import '../../model/document_kind.dart';
import 'completion_sources.dart';
import 'field_profile.dart';

const Map<String, String> _playerName = <String, String>{'player_name': "The player's name"};
const Map<String, String> _commandTokens = <String, String>{
  'player_name': "The player's name",
  'player': "The player's UUID",
};

/// Text the formatter colours, with `{player_name}`.
const FieldProfile _spoken = FieldProfile(miniMessage: true, papi: true, placeholders: _playerName);
const FieldProfile _styled = FieldProfile(miniMessage: true, papi: true);

/// The profile of argument [key] of [capability].
FieldProfile argumentProfile(String? capability, String key) {
  switch ((capability, key)) {
    case ('animus:say' || 'animus:message' || 'animus:actionbar', 'text'):
    case ('animus:title', 'title' || 'subtitle'):
      return _spoken;
    case ('animus:choices', 'prompt'):
    case ('animus:give-book', 'title' || 'author'):
    case ('animus:give-item', 'name'):
    case ('animus:set-appearance', 'name'):
      return _styled;
    case ('animus:command', 'command'):
      return const FieldProfile(command: true, placeholders: _commandTokens, papi: true);
    case ('animus:flag-state' || 'animus:set-flag', 'name'):
      return const FieldProfile(values: <ValueRef>[ValueRef.flags()]);
    case ('animus:quest-state' || 'animus:set-quest-state' || 'animus:advance-quest-state', 'name'):
      return const FieldProfile(values: <ValueRef>[ValueRef.quests()]);
    case ('animus:give-item' || 'animus:take-item' || 'animus:set-equipment', 'item'):
      return idProfile(const ValueRef.registry(RegistryKind.item));
    case ('animus:play-sound', 'sound'):
      return idProfile(const ValueRef.registry(RegistryKind.sound));
    case ('animus:spawn-particle', 'particle'):
      return idProfile(const ValueRef.registry(RegistryKind.particle));
    case ('animus:set-attribute', 'attribute'):
      return idProfile(const ValueRef.registry(RegistryKind.attribute));
    case ('animus:teleport', 'world'):
      return idProfile(const ValueRef.registry(RegistryKind.world));
    case ('animus:navigate', 'route'):
      return idProfile(const ValueRef.document(DocumentKind.route));
    case ('animus:play-animation', 'animation'):
      return idProfile(const ValueRef.document(DocumentKind.animation));
    case ('animus:play-recording', 'recording'):
      return idProfile(const ValueRef.document(DocumentKind.recording));
    case ('animus:set-appearance', 'skin'):
      return idProfile(const ValueRef.document(DocumentKind.skin));
    case ('animus:open-shop', 'shop'):
      return idProfile(const ValueRef.document(DocumentKind.shop));
    case ('animus:open-gui', 'gui'):
      return const FieldProfile(values: <ValueRef>[
        ValueRef.document(DocumentKind.inventory),
        ValueRef.document(DocumentKind.menu),
        ValueRef.document(DocumentKind.shop),
      ]);
  }
  return FieldProfile.none;
}

/// A shop entry's broadcast line (`ShopScreen` fills these).
const FieldProfile shopBroadcastProfile = FieldProfile(miniMessage: true, placeholders: <String, String>{
  'player': "The buyer's name",
  'item': 'The item bought',
  'price': 'The price paid',
  'shop': 'The shop id',
});

/// A shop entry's command, run by the console after a purchase.
const FieldProfile shopCommandProfile = FieldProfile(command: true, placeholders: <String, String>{
  'player': "The buyer's name",
  'uuid': "The buyer's UUID",
  'amount': 'Units bought',
  'price': 'The price paid',
});

/// A GUI item field: an item id, or a template that computes one.
const FieldProfile guiItemProfile = FieldProfile(holes: ExpressionSyntax.gui, values: <ValueRef>[ValueRef.registry(RegistryKind.item)]);

/// One id from [ref], as a whole field.
FieldProfile idProfile(ValueRef ref) => FieldProfile(values: <ValueRef>[ref]);

/// The profile of trigger filter [key]; `expression` fields (`cancel`,
/// `consume`) are behavior expressions.
FieldProfile triggerFieldProfile(String key, {required String type}) {
  if (type == 'expression') return FieldProfile.behaviorExpression;
  return switch (key) {
    'item' || 'bow' => idProfile(const ValueRef.registry(RegistryKind.item)),
    'block' => idProfile(const ValueRef.registry(RegistryKind.block)),
    'projectile' || 'shooter' || 'entity' || 'damager' => idProfile(const ValueRef.registry(RegistryKind.entity)),
    'world' || 'from' || 'to' => idProfile(const ValueRef.registry(RegistryKind.world)),
    'animation' => idProfile(const ValueRef.document(DocumentKind.animation)),
    _ => FieldProfile.none,
  };
}
