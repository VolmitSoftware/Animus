/// Parts of an NPC definition that work only with a server plugin: the
/// editor offers them while the connected server reports that plugin
/// running, and always shows what a definition already uses.
library;

/// Module keys, by the capability feature the server reports for the plugin.
const Map<String, String> integrationModules = <String, String>{'animus:modelengine': 'modelengine', 'mythic:mob': 'mythicmobs'};

/// Definition fields, by the capability feature the server reports for the plugin.
const Map<String, String> integrationFields = <String, String>{'disguise': 'libsdisguises'};

/// The plugin features [capabilities] reports available.
Set<String> reportedPlugins(Map<String, Object?>? capabilities) {
  final Object? features = capabilities?['features'];
  if (features is! Map<String, Object?>) return const <String>{};
  final Set<String> plugins = <String>{...integrationModules.values, ...integrationFields.values};
  return <String>{
    for (final MapEntry<String, Object?> feature in features.entries)
      if (plugins.contains(feature.key) && feature.value is Map<String, Object?> && (feature.value! as Map<String, Object?>)['available'] == true) feature.key,
  };
}

/// Whether the editor offers [module] to add with [plugins] running.
bool offersModule(String module, Set<String> plugins) {
  final String? plugin = integrationModules[module];
  return plugin == null || plugins.contains(plugin);
}

/// Whether the editor offers [field] to fill with [plugins] running.
bool offersField(String field, Set<String> plugins) {
  final String? plugin = integrationFields[field];
  return plugin == null || plugins.contains(plugin);
}
