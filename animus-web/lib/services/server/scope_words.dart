/// A pairing's permissions in plain words. Pure Dart.
library;

/// Each permission (`scope` in the protocol) as the editor names it.
const Map<String, String> scopeWords = <String, String>{
  'read': 'See files',
  'validate': 'Check changes',
  'preview': 'Preview in game',
  'apply:npcs': 'Edit NPCs',
  'apply:behaviors': 'Edit behaviors',
  'apply:gui': 'Edit shops and GUIs',
  'apply:rigs': 'Edit rigs',
  'apply:commands': 'Run commands',
  'import': 'Import',
  'spawn': 'Spawn and despawn',
  'terrain': 'See terrain',
};

/// A permission in words; an unknown one as it is.
String scopeWord(String scope) => scopeWords[scope] ?? scope;

/// The permissions in words, in the order of [scopeWords], unknown ones last.
List<String> scopeSentences(Iterable<String> scopes) {
  final Set<String> given = scopes.toSet();
  return <String>[
    for (final String scope in scopeWords.keys)
      if (given.contains(scope)) scopeWords[scope]!,
    for (final String scope in given)
      if (!scopeWords.containsKey(scope)) scope,
  ];
}

/// What a pairing made by an operator allows, before the server says: every
/// permission unless the pair command narrowed it.
const List<String> fullPairing = <String>['Edit NPCs, behaviors, shops and GUIs', 'Spawn and despawn NPCs', 'Preview changes in game', 'Import from other plugins'];
