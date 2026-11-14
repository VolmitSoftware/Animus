/// The tabs an NPC or trait group form is cut into, and which settings each
/// tab keeps behind Advanced. Simple shows the common fields; when a field
/// it hides holds a value, the tab counts it ("2 advanced settings") so
/// nothing that changes the NPC is out of sight. Pure Dart.
library;

import '../gui/gui_json.dart';
import 'combat_presets.dart' show combatHiddenSettings;
import 'combat_settings.dart' show combatModule;
import 'health_settings.dart' show healthModule;
import 'npc_appearance.dart' show bodySettingsTypes, clickRedirectField, npcTraits;
import 'npc_attributes.dart' show attributesField;
import 'module_rows.dart' show importedModule;
import 'npc_integrations.dart';
import 'route_model.dart' show navigationModule;

const String shopModuleKey = 'animus:shop';
const String mountModuleKey = 'animus:mount';

/// Root keys some tab edits; the rest show under Other fields on Modules.
final Set<String> npcLaidOut = <String>{
  'schemaVersion',
  'revision',
  'uuid',
  'id',
  'group',
  'name',
  'type',
  'spawned',
  'position',
  'skin',
  'behaviors',
  'modules',
  'tags',
  'equipment',
  'owner',
  'rig',
  'disguise',
  'pose',
  'armorStand',
  ...bodySettingsTypes.keys,
  'bossBar',
  'viewers',
  clickRedirectField,
  'hologram',
  'nameplate',
  'inventory',
  'poses',
  'anchors',
  'movement',
  'groups',
  'skins',
  attributesField,
  ...npcTraits,
};

/// Modules with a tab of their own (Movement, Talk and trade, Combat).
const Set<String> tabModules = <String>{navigationModule, combatModule, healthModule, shopModuleKey, mountModuleKey};

enum NpcTab {
  basics('Basics', advanced: false),
  members('Members', advanced: false),
  look('Look', advanced: false),
  movement('Movement', advanced: false),
  talk('Talk and trade', advanced: false),
  combat('Combat', advanced: false),
  attributes('Attributes', advanced: true),
  modules('Modules', advanced: true),
  integrations('Integrations', advanced: true);

  const NpcTab(this.label, {required this.advanced});

  final String label;

  /// Shown in Advanced only.
  final bool advanced;

  /// The panel's `data-npc-tab`: "Talk and trade" gives `talk-and-trade`.
  String get slug => label.toLowerCase().replaceAll(RegExp('[^a-z0-9]+'), '-');
}

/// The tabs of an NPC ([group] false) or trait group, in order. Simple
/// shows the Advanced tabs in [revealed] too.
List<NpcTab> npcTabs({required bool group, required bool advanced, Set<NpcTab> revealed = const <NpcTab>{}}) => <NpcTab>[
      for (final NpcTab tab in NpcTab.values)
        if ((group ? tab != NpcTab.basics && tab != NpcTab.integrations : tab != NpcTab.members) && (advanced || !tab.advanced || revealed.contains(tab))) tab,
    ];

bool _set(Object? value) => switch (value) {
      null => false,
      final String text => text.trim().isNotEmpty,
      final List<Object?> list => list.isNotEmpty,
      final Map<String, Object?> map => map.isNotEmpty,
      _ => true,
    };

/// Root keys an importer writes for its own records, never a setting.
const Set<String> importRecordKeys = <String>{'migration'};

/// [name] once for each setting [value] holds: each key of an object, one
/// for any other set value.
List<String> _each(Object? value, String name) => switch (value) {
      final Map<String, Object?> map => <String>[for (final String _ in map.keys) name],
      _ => <String>[if (_set(value)) name],
    };

/// The settings of [tab] that Simple does not show and that change the NPC
/// (a value other than the default), one entry each, named by the part of
/// the tab that holds it; a trait group passes its `values`. For Modules,
/// module keys and root keys no tab edits ([laidOut]). Import records
/// (`migration`, `citizens:review`) are bookkeeping and never count.
List<String> advancedInUse(NpcTab tab, JsonMap doc, {required bool group, Set<String> laidOut = const <String>{}}) {
  final JsonMap modules = asMap(doc['modules']);
  return switch (tab) {
    NpcTab.basics => <String>[
        if ((asDouble(asMap(doc['position'])['pitch']) ?? 0) != 0) 'Head tilt',
        if (_set(doc['owner'])) 'Owner',
        if (_set(doc['tags'])) 'Tags',
        if (_set(doc['groups'])) 'Trait groups',
        if (_set(doc['poses'])) 'Named facings',
        if (_set(doc['anchors'])) 'Anchors',
      ],
    NpcTab.members => <String>[if (_set(doc['tags'])) 'Tags'],
    NpcTab.look => <String>[
        for (final Object? item in asMap(doc['equipment']).values)
          if (_set(asMap(item)['components'])) 'Item components',
        for (final String field in _itemBodies)
          if (_set(asMap(asMap(doc[field])['item'])['components'])) 'Item components',
        if (_set(doc['rig'])) 'Rig',
        if (_set(doc['player'])) 'Skin layers',
        if (_set(doc['bossBar'])) 'Boss bar',
        if (_set(doc['viewers'])) 'Viewers',
        if (_set(doc['clickRedirect'])) 'Click redirect',
      ],
    NpcTab.movement => <String>[
        if (asMap(modules[navigationModule])['enabled'] == false) 'Movement off',
        ..._each(doc['movement'], 'Walking'),
        if (modules.containsKey(mountModuleKey)) 'Riding',
      ],
    NpcTab.talk => <String>[if (!group && _set(doc['inventory'])) 'Inventory'],
    NpcTab.combat => <String>[for (final List<JsonStep> _ in combatHiddenSettings(modules)) 'Combat'],
    NpcTab.attributes => _each(doc[attributesField], 'Attributes'),
    NpcTab.modules => <String>[
        for (final String key in modules.keys)
          if (!tabModules.contains(key) && !integrationModules.containsKey(key) && !importedModule(key)) key,
        if (laidOut.isNotEmpty)
          for (final String key in doc.keys)
            if (!laidOut.contains(key) && key != 'modules' && !importRecordKeys.contains(key)) key,
      ],
    NpcTab.integrations => <String>[
        for (final String field in integrationFields.keys)
          if (_set(doc[field])) _fieldLabel(field),
        for (final String key in integrationModules.keys)
          if (modules.containsKey(key)) key,
      ],
  };
}

/// Body settings that hold an item (`itemFrame.item`), whose component
/// text Simple keeps without showing.
const List<String> _itemBodies = <String>['itemFrame', 'glowItemFrame', 'itemDisplay', 'droppedItem'];

String _fieldLabel(String field) => '${field[0].toUpperCase()}${field.substring(1)}';

/// The Advanced-only tabs that hold settings in [doc]; Simple counts them on
/// its first tab, whose Show opens them.
List<NpcTab> hiddenTabsInUse(JsonMap doc, {required bool group, Set<String> laidOut = const <String>{}}) => <NpcTab>[
      for (final NpcTab tab in npcTabs(group: group, advanced: true))
        if (tab.advanced && advancedInUse(tab, doc, group: group, laidOut: laidOut).isNotEmpty) tab,
    ];

/// The count on [tab]'s one line in Simple, with [shown] the tabs on
/// screen: the settings it hides, and on the first tab also those of the
/// Advanced-only tabs not shown, which its Show opens.
int simpleInUse(NpcTab tab, JsonMap doc, {required bool group, required List<NpcTab> shown}) {
  int count = advancedInUse(tab, doc, group: group, laidOut: npcLaidOut).length;
  if (shown.isNotEmpty && tab == shown.first) {
    for (final NpcTab hidden in hiddenTabsInUse(doc, group: group, laidOut: npcLaidOut)) {
      if (!shown.contains(hidden)) count += advancedInUse(hidden, doc, group: group, laidOut: npcLaidOut).length;
    }
  }
  return count;
}

