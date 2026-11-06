/// What GUI documents may contain in Animus: the supported and refused
/// action types (schema/gloss/source.json), each action's fields for the
/// editor, the components and icons each surface accepts, and the names and
/// functions expressions can use (wiki animus/11-guis-holograms-menus).
library;

import 'dart:convert';

import '../../config/shared_schemas.g.dart';
import '../../model/document_kind.dart';
import 'gui_expr_functions.dart';

/// The supported, Animus-only and refused action lists, read once from the
/// embedded `gloss/source.json`.
final class GuiActionLists {
  GuiActionLists._(this.supported, this.animus, this.refused, this.glossCommit);

  factory GuiActionLists._load() {
    final Map<String, Object?> source = jsonDecode(sharedSchemaSources['gloss/source.json']!) as Map<String, Object?>;
    final Map<String, Object?> actions = source['actions']! as Map<String, Object?>;
    List<String> names(String key) => <String>[for (final Object? name in actions[key]! as List<Object?>) name! as String];
    return GuiActionLists._(names('supported'), names('animus'), names('refused'), source['commit']! as String);
  }

  static final GuiActionLists instance = GuiActionLists._load();

  final List<String> supported;
  final List<String> animus;
  final List<String> refused;
  final String glossCommit;

  /// Every type the editor offers, in menu order.
  List<String> get offered => <String>[...supported, ...animus];

  bool isOffered(String type) => supported.contains(type) || animus.contains(type);
}

/// `sequence` fields Animus refuses (Gloss cutscene features).
const List<String> refusedSequenceFields = <String>['once', 'skippable', 'onSkip'];

enum GuiFieldKind {
  /// Text with `&` codes, MiniMessage and `{{ expr }}` holes.
  template,

  /// A whole expression (no braces), such as `when` or `source`.
  expression,

  /// Plain text: ids, sound keys, variable names.
  text,
  integer,
  number,
  boolean,
  choice,

  /// A nested action list.
  actions,

  /// `switch` cases: a map of value to action list.
  cases,

  /// `parallel` branches: a list of action lists.
  branches,

  /// An icon object (`give`, `take`).
  icon,

  /// Free JSON object (`inventory` args).
  object,
}

final class GuiField {
  const GuiField(this.name, this.kind, {this.required = false, this.choices = const <String>[], this.help = ''});

  final String name;
  final GuiFieldKind kind;
  final bool required;
  final List<String> choices;
  final String help;
}

const List<String> guiTriggers = <String>['any', 'left_click', 'right_click', 'shift_left_click', 'shift_right_click'];

/// Fields every action may carry.
const List<GuiField> commonActionFields = <GuiField>[
  GuiField('trigger', GuiFieldKind.choice, choices: guiTriggers, help: 'Which click runs it; any by default.'),
  GuiField('when', GuiFieldKind.expression, help: 'Runs only when this condition is true.'),
  GuiField('cooldownTicks', GuiFieldKind.integer, help: 'Per-viewer cooldown in ticks.'),
];

const List<String> _hudPriorities = <String>['ambient', 'notice', 'status', 'progress', 'interactive', 'modal', 'pinned'];

/// The type-specific fields of each offered action.
const Map<String, List<GuiField>> actionFields = <String, List<GuiField>>{
  'command': <GuiField>[
    GuiField('command', GuiFieldKind.template, required: true, help: 'Without the leading slash. Needs the GUI on the command allow-list.'),
    GuiField('source', GuiFieldKind.choice, choices: <String>['server', 'player'], help: 'server (default) or the viewer.'),
  ],
  'sound': <GuiField>[
    GuiField('sound', GuiFieldKind.text, required: true, help: 'Sound key, such as minecraft:ui.button.click.'),
    GuiField('source', GuiFieldKind.choice, choices: <String>['master', 'music', 'record', 'weather', 'block', 'hostile', 'neutral', 'player', 'ambient', 'voice']),
    GuiField('volume', GuiFieldKind.number),
    GuiField('pitch', GuiFieldKind.number),
  ],
  'message': <GuiField>[GuiField('message', GuiFieldKind.template, required: true)],
  'title': <GuiField>[
    GuiField('title', GuiFieldKind.template, required: true),
    GuiField('subtitle', GuiFieldKind.template),
    GuiField('fadeInTicks', GuiFieldKind.integer),
    GuiField('stayTicks', GuiFieldKind.integer),
    GuiField('fadeOutTicks', GuiFieldKind.integer),
    GuiField('priority', GuiFieldKind.choice, choices: _hudPriorities),
  ],
  'actionbar': <GuiField>[
    GuiField('text', GuiFieldKind.template, required: true),
    GuiField('ticks', GuiFieldKind.integer),
    GuiField('priority', GuiFieldKind.choice, choices: _hudPriorities),
  ],
  'close': <GuiField>[],
  'navigate': <GuiField>[
    GuiField('mode', GuiFieldKind.choice, choices: <String>['page', 'push', 'replace', 'back', 'home', 'close']),
    GuiField('target', GuiFieldKind.text, help: 'A GUI id, or next, prev or a page number with mode page.'),
    GuiField('list', GuiFieldKind.text),
  ],
  'inventory': <GuiField>[
    GuiField('id', GuiFieldKind.text, required: true, help: 'The chest GUI to open, such as town:menu.'),
    GuiField('args', GuiFieldKind.object, help: 'Values readable as args.*.'),
  ],
  'setSession': <GuiField>[
    GuiField('var', GuiFieldKind.text, required: true, help: 'Readable as session.<var>.'),
    GuiField('value', GuiFieldKind.expression),
  ],
  'give': <GuiField>[
    GuiField('item', GuiFieldKind.icon, required: true),
    GuiField('amount', GuiFieldKind.expression),
    GuiField('dropIfFull', GuiFieldKind.boolean),
  ],
  'take': <GuiField>[
    GuiField('item', GuiFieldKind.icon, required: true),
    GuiField('amount', GuiFieldKind.expression),
    GuiField('denyMessage', GuiFieldKind.template),
  ],
  'economy': <GuiField>[
    GuiField('op', GuiFieldKind.choice, required: true, choices: <String>['withdraw', 'deposit', 'has']),
    GuiField('amount', GuiFieldKind.expression, required: true),
    GuiField('denyMessage', GuiFieldKind.template),
  ],
  'delay': <GuiField>[GuiField('ticks', GuiFieldKind.integer, required: true)],
  'sequence': <GuiField>[GuiField('steps', GuiFieldKind.actions, required: true, help: 'Each step may carry atTicks.')],
  'if': <GuiField>[
    GuiField('when', GuiFieldKind.expression, required: true),
    GuiField('then', GuiFieldKind.actions, required: true),
    GuiField('else', GuiFieldKind.actions),
  ],
  'switch': <GuiField>[
    GuiField('on', GuiFieldKind.expression, required: true),
    GuiField('cases', GuiFieldKind.cases, required: true),
    GuiField('default', GuiFieldKind.actions),
  ],
  'chance': <GuiField>[
    GuiField('percent', GuiFieldKind.number, required: true),
    GuiField('then', GuiFieldKind.actions, required: true),
    GuiField('else', GuiFieldKind.actions),
  ],
  'cooldown': <GuiField>[
    GuiField('key', GuiFieldKind.text, required: true),
    GuiField('ticks', GuiFieldKind.integer, required: true),
    GuiField('then', GuiFieldKind.actions),
    GuiField('else', GuiFieldKind.actions),
  ],
  'stop': <GuiField>[],
  'call': <GuiField>[GuiField('action', GuiFieldKind.text, required: true, help: 'A named list under actions.')],
  'teleport': <GuiField>[
    GuiField('world', GuiFieldKind.text, required: true, help: 'A world key, such as minecraft:the_nether. A world that is not loaded stops the list.'),
    GuiField('x', GuiFieldKind.number, required: true),
    GuiField('y', GuiFieldKind.number, required: true),
    GuiField('z', GuiFieldKind.number, required: true),
    GuiField('yaw', GuiFieldKind.number, required: true),
    GuiField('pitch', GuiFieldKind.number, required: true),
  ],
  'bossbar': <GuiField>[
    GuiField('id', GuiFieldKind.text, required: true, help: 'The lane; a new bar on the same lane replaces the old one.'),
    GuiField('title', GuiFieldKind.template, required: true),
    GuiField('progress', GuiFieldKind.expression, help: '0 to 1.'),
    GuiField('color', GuiFieldKind.choice, choices: <String>['pink', 'blue', 'red', 'green', 'yellow', 'purple', 'white']),
    GuiField('style', GuiFieldKind.choice, choices: <String>['solid', 'segmented_6', 'segmented_10', 'segmented_12', 'segmented_20']),
    GuiField('ticks', GuiFieldKind.integer, help: 'Default 100; 0 hides the lane.'),
    GuiField('priority', GuiFieldKind.choice, choices: _hudPriorities, help: 'Read and checked; Animus shows every lane.'),
  ],
  'broadcast': <GuiField>[
    GuiField('message', GuiFieldKind.template, required: true),
    GuiField('scope', GuiFieldKind.choice, choices: <String>['server', 'world', 'radius']),
    GuiField('radius', GuiFieldKind.number, help: 'Blocks, with scope radius.'),
    GuiField('permission', GuiFieldKind.text, help: 'Only players with it see the message.'),
  ],
  'effect': <GuiField>[
    GuiField('effect', GuiFieldKind.text, required: true, help: 'A potion effect key, such as minecraft:speed.'),
    GuiField('ticks', GuiFieldKind.integer, required: true),
    GuiField('amplifier', GuiFieldKind.integer, help: '0 to 255.'),
  ],
  'particle': <GuiField>[
    GuiField('particle', GuiFieldKind.text, required: true, help: 'A particle key, such as minecraft:flame. Only the viewer sees them.'),
    GuiField('count', GuiFieldKind.integer),
    GuiField('offset', GuiFieldKind.number, help: 'Spread in blocks.'),
    GuiField('at', GuiFieldKind.choice, choices: <String>['viewer', 'location'], help: 'location is the hologram or menu; a chest window has none.'),
  ],
  'parallel': <GuiField>[GuiField('branches', GuiFieldKind.branches, required: true, help: 'Each list runs side by side.')],
  'repeat': <GuiField>[
    GuiField('times', GuiFieldKind.integer, required: true),
    GuiField('everyTicks', GuiFieldKind.integer, help: '0 runs every pass at once.'),
    GuiField('while', GuiFieldKind.expression, help: 'Checked before each pass; false ends the loop.'),
    GuiField('steps', GuiFieldKind.actions, required: true),
  ],
  'animus:open-shop': <GuiField>[GuiField('shop', GuiFieldKind.text, required: true, help: 'A shop id, such as town:baker.')],
};

/// A minimal valid action of [type], for the add menu.
Map<String, Object?> newAction(String type) => switch (type) {
      'command' => <String, Object?>{'type': type, 'command': 'say hello'},
      'sound' => <String, Object?>{'type': type, 'sound': 'minecraft:ui.button.click'},
      'message' => <String, Object?>{'type': type, 'message': '&7Hello, {{ viewer.name }}.'},
      'title' => <String, Object?>{'type': type, 'title': '&6Welcome'},
      'actionbar' => <String, Object?>{'type': type, 'text': '&eHello'},
      'navigate' => <String, Object?>{'type': type, 'mode': 'page', 'target': 'next'},
      'inventory' => <String, Object?>{'type': type, 'id': 'example:menu'},
      'setSession' => <String, Object?>{'type': type, 'var': 'choice', 'value': "'a'"},
      'give' || 'take' => <String, Object?>{'type': type, 'item': <String, Object?>{'type': 'item', 'item': 'minecraft:bread'}},
      'economy' => <String, Object?>{'type': type, 'op': 'withdraw', 'amount': '10'},
      'delay' => <String, Object?>{'type': type, 'ticks': 20},
      'sequence' => <String, Object?>{'type': type, 'steps': <Object?>[<String, Object?>{'type': 'message', 'message': 'Step one'}]},
      'if' => <String, Object?>{'type': type, 'when': 'viewer.level > 10', 'then': <Object?>[]},
      'switch' => <String, Object?>{'type': type, 'on': 'session.choice', 'cases': <String, Object?>{}},
      'chance' => <String, Object?>{'type': type, 'percent': 50, 'then': <Object?>[]},
      'cooldown' => <String, Object?>{'type': type, 'key': 'click', 'ticks': 20},
      'call' => <String, Object?>{'type': type, 'action': 'main'},
      'teleport' => <String, Object?>{'type': type, 'world': 'minecraft:overworld', 'x': 0, 'y': 64, 'z': 0, 'yaw': 0, 'pitch': 0},
      'bossbar' => <String, Object?>{'type': type, 'id': 'main', 'title': '&eHello'},
      'broadcast' => <String, Object?>{'type': type, 'message': '&e{{ viewer.name }} said hello'},
      'effect' => <String, Object?>{'type': type, 'effect': 'minecraft:speed', 'ticks': 200},
      'particle' => <String, Object?>{'type': type, 'particle': 'minecraft:happy_villager', 'count': 8},
      'parallel' => <String, Object?>{'type': type, 'branches': <Object?>[<Object?>[], <Object?>[]]},
      'repeat' => <String, Object?>{'type': type, 'times': 3, 'everyTicks': 20, 'steps': <Object?>[]},
      'animus:open-shop' => <String, Object?>{'type': type, 'shop': 'example:shop'},
      _ => <String, Object?>{'type': type},
    };

/// Component types each surface accepts, and those it refuses.
const List<String> chestComponentTypes = <String>['button', 'decoration', 'toggle'];
const List<String> chestRefusedComponentTypes = <String>['list', 'slider', 'field', 'tabs'];
const List<String> menuComponentTypes = <String>['button', 'decoration', 'toggle', 'list', 'tabs', 'slider'];
const List<String> menuRefusedComponentTypes = <String>['field'];

/// Actions a hologram refuses: it has no window to close and no named lists.
const List<String> hologramRefusedActions = <String>['close', 'call'];

/// Icon types each surface accepts, and those it refuses.
const List<String> chestIconTypes = <String>['item', 'playerHead'];
const List<String> chestRefusedIconTypes = <String>['text', 'textImage', 'animatedTextImage', 'block', 'entity', 'customItem'];
const List<String> menuIconTypes = <String>['text', 'item', 'block', 'playerHead', 'entity'];
const List<String> menuRefusedIconTypes = <String>['textImage', 'animatedTextImage', 'customItem'];

/// Supported hologram schema version (schema/gloss/source.json).
const int hologramSchemaVersion = 3;
const int inventorySchemaVersion = 1;

/// Variable names expressions can read, with a one-line meaning.
const Map<String, String> guiExpressionNames = <String, String>{
  'viewer.name': 'The viewer\'s name',
  'viewer.uuid': 'The viewer\'s UUID',
  'viewer.world': 'The viewer\'s world',
  'viewer.health': 'Health',
  'viewer.level': 'Experience level',
  'viewer.food': 'Food level',
  'viewer.sneaking': 'Sneaking',
  'viewer.x': 'Block X',
  'viewer.y': 'Block Y',
  'viewer.z': 'Block Z',
  'viewer.op': 'Operator (Bukkit)',
  'viewer.flying': 'Flying (Bukkit)',
  'viewer.gamemode': 'Game mode (Bukkit)',
  'player.name': 'Same as viewer.name',
  'player.uuid': 'Same as viewer.uuid',
  'server.online': 'Players online',
  'server.max': 'Player slots',
  'time.hour': 'Server clock hour',
  'time.minute': 'Minute',
  'time.second': 'Second',
  'time.day': 'Day of month',
  'time.month': 'Month',
  'time.year': 'Year',
  'time.weekday': 'Day of week',
  'time.millis': 'Epoch milliseconds',
  'npc.name': 'The NPC that opened the window',
  'npc.id': 'NPC id',
  'npc.uuid': 'NPC UUID',
  'npc.spawned': 'NPC is spawned',
  'npc.health': 'NPC health',
  'page': 'List page, from 1',
  'pages': 'List page count',
};

/// Prefixes whose members are open-ended (values passed in at run time).
const List<String> guiOpenNamespaces = <String>['args.', 'session.', 'state.'];

/// Every function name expressions can call.
List<String> get guiExpressionFunctions => previewStandardFunctionNames;

/// The data folder kind for each GUI surface the editors handle.
const List<DocumentKind> guiKinds = <DocumentKind>[DocumentKind.inventory, DocumentKind.menu, DocumentKind.hologram, DocumentKind.shop];
