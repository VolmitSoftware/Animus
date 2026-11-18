/// How the action and check pickers sort and find capabilities: a group
/// per kind of work (Talk, Move, Items, ...), a plain label, and the words
/// people search with ("attack" finds Fight, "walk" finds Walk to). Steps
/// that are not actions (Wait, Repeat, If) are listed under Flow, so a
/// search for "wait" finds the Wait step.
library;

import 'behavior_summary.dart' show capabilityWords;
import 'behavior_words.dart' show nodeWords;

/// The groups in the order the picker shows them.
const List<String> actionGroupOrder = <String>['Talk', 'Move', 'Look', 'Items', 'Flags and quests', 'World', 'Combat', 'Menus', 'Server', 'Flow'];

/// Each built-in capability: its group, its label, and search words.
const Map<String, (String, String, List<String>)> _actions = <String, (String, String, List<String>)>{
  'animus:say': ('Talk', 'Say', <String>['talk', 'speak', 'chat', 'line', 'greet', 'text', 'bubble']),
  'animus:message': ('Talk', 'Message', <String>['chat', 'broadcast', 'announce', 'tell', 'send', 'text', 'console']),
  'animus:title': ('Talk', 'Title', <String>['screen', 'big text', 'announce', 'subtitle']),
  'animus:actionbar': ('Talk', 'Action bar', <String>['hotbar', 'status', 'text']),
  'animus:choices': ('Talk', 'Ask', <String>['question', 'answer', 'options', 'choices', 'conversation', 'reply']),
  'animus:bossbar': ('Talk', 'Boss bar', <String>['bar', 'progress', 'countdown']),
  'animus:sidebar': ('Talk', 'Sidebar', <String>['scoreboard', 'board', 'lines']),
  'animus:navigate': ('Move', 'Walk to', <String>['walk', 'move', 'go', 'path', 'travel', 'route', 'navigate', 'run to', 'fly', 'swim']),
  'animus:stop-navigation': ('Move', 'Stop walking', <String>['halt', 'stand still', 'stop', 'walk']),
  'animus:play-recording': ('Move', 'Play recording', <String>['replay', 'playback', 'walk', 'path']),
  'animus:resume-patrol': ('Move', 'Resume route', <String>['patrol', 'route', 'continue', 'walk']),
  'animus:teleport': ('Move', 'Teleport', <String>['tp', 'warp', 'move', 'send', 'anchor']),
  'animus:look': ('Look', 'Look at', <String>['face', 'turn', 'stare', 'head', 'rotate', 'watch']),
  'animus:look-close': ('Look', 'Look at players', <String>['face players', 'watch', 'turn head', 'look close']),
  'animus:pose': ('Look', 'Pose', <String>['stance', 'posture', 'face']),
  'animus:animate': ('Look', 'Animate', <String>['swing', 'hurt', 'sneak', 'sit', 'sleep', 'gesture', 'emote', 'wave']),
  'animus:set-appearance': ('Look', 'Name or skin', <String>['skin', 'name', 'rename', 'disguise', 'appearance']),
  'animus:set-equipment': ('Look', 'Hold or wear', <String>['hold', 'wear', 'armor', 'weapon', 'equipment', 'hand']),
  'animus:set-attribute': ('Look', 'Set attribute', <String>['scale', 'size', 'speed', 'gravity', 'attribute']),
  'animus:play-animation': ('Look', 'Play animation', <String>['animate', 'rig', 'emote', 'model', 'dance']),
  'animus:stop-animation': ('Look', 'Stop animation', <String>['idle', 'animation']),
  'animus:give-item': ('Items', 'Give item', <String>['reward', 'gift', 'loot', 'hand out', 'pay', 'item']),
  'animus:take-item': ('Items', 'Take item', <String>['remove', 'collect', 'cost', 'deliver', 'item']),
  'animus:read-inventory': ('Items', 'Read inventory', <String>['inventory', 'items', 'check items', 'held']),
  'animus:give-book': ('Items', 'Give book', <String>['book', 'written book', 'reward', 'lore']),
  'animus:charge': ('Items', 'Charge', <String>['pay', 'cost', 'money', 'price', 'buy', 'levels', 'experience', 'xp']),
  'animus:can-afford': ('Items', 'Can afford', <String>['money', 'price', 'enough', 'cost', 'pay']),
  'animus:can-fit': ('Items', 'Has room for', <String>['inventory space', 'room', 'fit', 'full']),
  'animus:set-flag': ('Flags and quests', 'Set flag', <String>['remember', 'store', 'mark', 'save', 'state', 'clear flag']),
  'animus:flag-state': ('Flags and quests', 'Flag is', <String>['remember', 'check flag', 'has flag', 'state']),
  'animus:set-quest-state': ('Flags and quests', 'Set quest stage', <String>['quest', 'stage', 'progress']),
  'animus:advance-quest-state': ('Flags and quests', 'Advance quest stage', <String>['quest', 'stage', 'progress', 'next']),
  'animus:quest-state': ('Flags and quests', 'Quest stage is', <String>['quest', 'stage', 'progress']),
  'animus:start-quest': ('Flags and quests', 'Start quest', <String>['quest', 'mission', 'task', 'accept']),
  'animus:advance-quest': ('Flags and quests', 'Advance quest', <String>['quest', 'next stage', 'progress']),
  'animus:complete-quest': ('Flags and quests', 'Complete quest', <String>['quest', 'finish', 'turn in', 'reward']),
  'animus:quest-objective': ('Flags and quests', 'Count objective', <String>['quest', 'objective', 'progress', 'count', 'task']),
  'animus:quest-status': ('Flags and quests', 'Quest status is', <String>['quest', 'active', 'finished', 'ready']),
  'animus:explode': ('World', 'Explode', <String>['blast', 'boom', 'tnt', 'explosion']),
  'animus:play-sound': ('World', 'Play sound', <String>['sound', 'noise', 'music', 'sfx']),
  'animus:spawn-particle': ('World', 'Show particles', <String>['particle', 'effect', 'sparkle', 'fx']),
  'animus:ray-trace': ('World', 'Block player looks at', <String>['ray trace', 'raycast', 'target block', 'look at block', 'location']),
  'animus:read-block': ('World', 'Read block', <String>['block', 'check block', 'location']),
  'animus:build': ('World', 'Build', <String>['schematic', 'construct', 'place blocks', 'builder']),
  'animus:combat-control': ('Combat', 'Fight', <String>['attack', 'fight', 'target', 'guard', 'defend', 'aggro', 'kill', 'combat']),
  'animus:npc-state': ('Combat', 'NPC state is', <String>['health', 'spawned', 'alive', 'hurt']),
  'animus:open-gui': ('Menus', 'Open GUI', <String>['menu', 'gui', 'inventory', 'open', 'gloss']),
  'animus:open-shop': ('Menus', 'Open shop', <String>['shop', 'trade', 'buy', 'sell', 'store']),
  'animus:command': ('Server', 'Run command', <String>['console', 'command', 'execute', 'cmd']),
  'animus:has-permission': ('Server', 'Has permission', <String>['permission', 'rank', 'perm', 'vip']),
  'animus:commands-allowed': ('Server', 'Commands allowed', <String>['allow list', 'command']),
  'animus:player-in-range': ('Server', 'Player nearby', <String>['near', 'distance', 'close', 'range']),
};

/// Search words for each step type the Flow group lists.
const Map<String, List<String>> _flowWords = <String, List<String>>{
  'wait': <String>['pause', 'delay', 'sleep', 'ticks', 'seconds'],
  'condition': <String>['if', 'check', 'branch', 'else'],
  'repeat': <String>['loop', 'times', 'again'],
  'foreach': <String>['loop', 'each', 'list'],
  'while': <String>['loop', 'until'],
  'call': <String>['routine', 'run', 'function'],
  'set': <String>['variable', 'store', 'count'],
  'return': <String>['stop', 'end', 'exit'],
  'parallel': <String>['together', 'at once', 'same time'],
};

/// The step types Flow lists in an action picker, in order.
List<String> get flowTypes => _flowWords.keys.toList();

/// The group of capability [id]; a module's capabilities group by module.
String actionGroup(String id) => _actions[id]?.$1 ?? 'Module ${id.contains(':') ? id.substring(0, id.indexOf(':')) : id}';

/// The picker label of capability [id].
String actionLabel(String id) => _actions[id]?.$2 ?? capabilityWords(id);

/// What a search for [id] matches besides its label: its id, its
/// [description] and the words people use for it.
List<String> actionKeywords(String id, String description) => <String>[id, ...?_actions[id]?.$3, if (description.isNotEmpty) description];

/// What a search for step [type] in the Flow group matches.
List<String> flowKeywords(String type) => <String>[type, ...?_flowWords[type], nodeWords[type]?.line ?? ''];

/// Group order for sorting: built-in groups first, modules after, Flow last.
int actionGroupRank(String group) {
  if (group == 'Flow') return 1000;
  final int at = actionGroupOrder.indexOf(group);
  return at < 0 ? 500 : at;
}
