/// Port of core's `PotionEffects` vanilla table: the status effect a vanilla potion type applies.
library;

import 'java_lang.dart';

typedef PotionEffect = ({String id, int amplifier, int durationTicks});

MapEntry<String, PotionEffect> _potion(String name, String effect, int amplifier, int ticks) =>
    MapEntry<String, PotionEffect>('minecraft:$name', (id: 'minecraft:$effect', amplifier: amplifier, durationTicks: ticks));

final Map<String, PotionEffect> _potions = Map<String, PotionEffect>.fromEntries(<MapEntry<String, PotionEffect>>[
  _potion('night_vision', 'night_vision', 0, 3600), _potion('long_night_vision', 'night_vision', 0, 9600), //
  _potion('invisibility', 'invisibility', 0, 3600), _potion('long_invisibility', 'invisibility', 0, 9600),
  _potion('leaping', 'jump_boost', 0, 3600), _potion('long_leaping', 'jump_boost', 0, 9600), _potion('strong_leaping', 'jump_boost', 1, 1800),
  _potion('fire_resistance', 'fire_resistance', 0, 3600), _potion('long_fire_resistance', 'fire_resistance', 0, 9600),
  _potion('swiftness', 'speed', 0, 3600), _potion('long_swiftness', 'speed', 0, 9600), _potion('strong_swiftness', 'speed', 1, 1800),
  _potion('slowness', 'slowness', 0, 1800), _potion('long_slowness', 'slowness', 0, 4800), _potion('strong_slowness', 'slowness', 3, 400),
  _potion('turtle_master', 'slowness', 3, 400), _potion('long_turtle_master', 'slowness', 3, 800), _potion('strong_turtle_master', 'slowness', 5, 400),
  _potion('water_breathing', 'water_breathing', 0, 3600), _potion('long_water_breathing', 'water_breathing', 0, 9600),
  _potion('healing', 'instant_health', 0, 1), _potion('strong_healing', 'instant_health', 1, 1),
  _potion('harming', 'instant_damage', 0, 1), _potion('strong_harming', 'instant_damage', 1, 1),
  _potion('poison', 'poison', 0, 900), _potion('long_poison', 'poison', 0, 1800), _potion('strong_poison', 'poison', 1, 432),
  _potion('regeneration', 'regeneration', 0, 900), _potion('long_regeneration', 'regeneration', 0, 1800),
  _potion('strong_regeneration', 'regeneration', 1, 450),
  _potion('strength', 'strength', 0, 3600), _potion('long_strength', 'strength', 0, 9600), _potion('strong_strength', 'strength', 1, 1800),
  _potion('weakness', 'weakness', 0, 1800), _potion('long_weakness', 'weakness', 0, 4800), _potion('luck', 'luck', 0, 6000),
  _potion('slow_falling', 'slow_falling', 0, 1800), _potion('long_slow_falling', 'slow_falling', 0, 4800),
  _potion('wind_charged', 'wind_charged', 0, 3600), _potion('weaving', 'weaving', 0, 3600), _potion('oozing', 'oozing', 0, 3600),
  _potion('infested', 'infested', 0, 3600),
]);

/// `PotionEffects.of(String)`: the effect of a vanilla potion type such as `minecraft:strong_poison`, after
/// `MinecraftIds.normalize`.
PotionEffect? potionEffect(String potion) {
  final String trimmed = javaLower(javaTrim(potion));
  return _potions[trimmed.isEmpty || trimmed.contains(':') ? trimmed : 'minecraft:$trimmed'];
}
