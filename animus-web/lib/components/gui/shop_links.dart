/// Which NPCs open the shop: the NPCs and trait groups whose `animus:shop`
/// module names it, each a link to its page with Detach in its menu, and
/// in Advanced its window and, for a villager body, the profession and
/// level the trading screen title shows. A searchable NPC picker attaches
/// the shop in one undoable change.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_text.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../state/editor_store.dart';
import '../conversation/conversation_attach.dart' show ConversationCommit;
import '../../ui/confirm_dialog.dart';
import '../../ui/mode.dart';
import '../../ui/menu.dart';
import 'gui_rows.dart';
import 'shop_offer_editor.dart' show CountStepper;
import '../select/animus_select.dart';
import '../../ui/field.dart';
import '../../ui/stack.dart';

const String shopModuleKey = 'animus:shop';

/// Vanilla villager professions, for a villager body's trading screen title.
const List<String> villagerProfessions = <String>[
  'minecraft:none',
  'minecraft:armorer',
  'minecraft:butcher',
  'minecraft:cartographer',
  'minecraft:cleric',
  'minecraft:farmer',
  'minecraft:fisherman',
  'minecraft:fletcher',
  'minecraft:leatherworker',
  'minecraft:librarian',
  'minecraft:mason',
  'minecraft:nitwit',
  'minecraft:shepherd',
  'minecraft:toolsmith',
  'minecraft:weaponsmith',
];

/// Level names as the trading screen title shows them.
const List<String> villagerLevels = <String>['Novice', 'Apprentice', 'Journeyman', 'Expert', 'Master'];

/// Whether an NPC definition has a villager body, the only one that shows
/// a profession and level.
bool isVillagerBody(JsonMap npc) {
  final String type = asString(npc['type']) ?? '';
  return type == 'minecraft:villager' || type == 'villager';
}

/// The `animus:shop` module of an NPC's [json], or of a group's `values`.
JsonMap shopModuleIn(DocumentKind kind, Object? json) {
  final JsonMap holder = kind == DocumentKind.group ? asMap(asMap(json)['values']) : asMap(json);
  return asMap(asMap(holder['modules'])[shopModuleKey]);
}

JsonMap shopModuleOf(AnimusDocument document) => shopModuleIn(document.kind, document.json);

/// [json] of an NPC or group with its shop module replaced by [module], or
/// removed when null.
JsonMap withShopModule(DocumentKind kind, JsonMap json, JsonMap? module) {
  JsonMap put(JsonMap holder) {
    final JsonMap modules = <String, Object?>{...asMap(holder['modules'])};
    if (module == null) {
      modules.remove(shopModuleKey);
    } else {
      modules[shopModuleKey] = module;
    }
    final JsonMap out = <String, Object?>{...holder, 'modules': modules};
    if (modules.isEmpty) out.remove('modules');
    return out;
  }

  if (kind == DocumentKind.group) return <String, Object?>{...json, 'values': put(asMap(json['values']))};
  return put(json);
}

/// NPCs and groups whose shop module opens [shop].
List<AnimusDocument> shopUsers(Iterable<AnimusDocument> documents, String shop) => <AnimusDocument>[
      for (final AnimusDocument document in documents)
        if ((document.kind == DocumentKind.npc || document.kind == DocumentKind.group) && shopModuleOf(document)['shop'] == shop) document,
    ];

class ShopLinks extends StatefulWidget {
  const ShopLinks({required this.shop, required this.documents, this.onCommit, this.onOpen, super.key});

  final String shop;
  final List<AnimusDocument> documents;

  /// Writes the NPC documents; null shows the list without changing it.
  final ConversationCommit? onCommit;
  final void Function(DocumentKind kind, String id)? onOpen;

  @override
  State<ShopLinks> createState() => _ShopLinksState();
}

class _ShopLinksState extends State<ShopLinks> {
  String? _open;
  bool _busy = false;

  static String _key(AnimusDocument document) => '${document.kind.wireName}:${document.id}';

  static String _name(AnimusDocument document) {
    final String title = guiPlainText(document.title);
    return '${document.kind == DocumentKind.group ? 'Trait group ' : ''}${title.isEmpty ? document.id : title}';
  }

  Future<void> _commit(AnimusDocument document, JsonMap? Function(JsonMap module) change, String label) async {
    final ConversationCommit? commit = component.onCommit;
    if (commit == null) return;
    setState(() => _busy = true);
    try {
      await commit(<DocumentChange>[
        DocumentChange.update(document.kind, document.id, (Object? latest) => withShopModule(document.kind, asMap(latest), change(shopModuleIn(document.kind, latest)))),
      ], label);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _attach(AnimusDocument document) async {
    final String? replaces = asString(shopModuleOf(document)['shop']);
    if (replaces != null &&
        !await askToConfirm(title: 'Replace $replaces on ${_name(document)}?', message: 'It opens ${component.shop} instead.', confirmLabel: 'Replace')) {
      return;
    }
    await _commit(document, (JsonMap module) => <String, Object?>{...module, 'shop': component.shop}, 'attach ${component.shop} to ${document.id}');
  }

  Future<void> _detach(AnimusDocument document) async {
    if (!await askToConfirm(title: 'Detach from ${_name(document)}?', message: 'The NPC stops opening this shop.', confirmLabel: 'Detach', destructive: true)) return;
    await _commit(document, (JsonMap _) => null, 'detach ${component.shop} from ${document.id}');
  }

  @override
  Widget build(BuildContext context) {
    final bool advanced = ModeScope.advanced(context);
    final List<AnimusDocument> users = shopUsers(component.documents, component.shop);
    final List<AnimusDocument> others = <AnimusDocument>[
      for (final AnimusDocument document in component.documents)
        if ((document.kind == DocumentKind.npc || (advanced && document.kind == DocumentKind.group)) && document.json is Map<String, Object?> && !users.contains(document)) document,
    ];
    final Map<String, AnimusDocument> byKey = <String, AnimusDocument>{for (final AnimusDocument document in others) _key(document): document};
    return Field(
      label: 'Opened by',
      tip: 'NPCs that open this shop when clicked.',
      child: stack(<Widget>[
        if (users.isEmpty)
          mutedText('No NPC opens it yet.')
        else
          OrderedRows(label: 'Opened by', rows: <OrderedRow>[for (final AnimusDocument document in users) _user(document, advanced)]),
        if (component.onCommit != null && byKey.isNotEmpty)
          AnimusSelect.values(
            value: null,
            values: byKey.keys.toList(),
            labels: <String, String>{for (final MapEntry<String, AnimusDocument> one in byKey.entries) one.key: _name(one.value)},
            details: <String, String>{
              for (final MapEntry<String, AnimusDocument> one in byKey.entries)
                if (asString(shopModuleOf(one.value)['shop']) case final String other) one.key: 'Opens $other',
            },
            noneLabel: 'Add NPC',
            hiddenLabel: 'NPC that opens this shop',
            disabled: _busy,
            onChanged: (String? key) {
              final AnimusDocument? picked = key == null ? null : byKey[key];
              if (picked != null) _attach(picked);
            },
          ),
      ]),
    );
  }

  OrderedRow _user(AnimusDocument document, bool advanced) {
    final String key = _key(document);
    final bool open = advanced && _open == key;
    final void Function()? opener = component.onOpen == null ? null : () => component.onOpen!(document.kind, document.id);
    return OrderedRow(
      key: key,
      name: _name(document),
      open: open,
      attributes: <String, String>{'data-user': key},
      line: Button.ghost(label: _name(document), size: ButtonSize.sm, onPressed: opener, attributes: <String, String>{'aria-label': 'Open ${_name(document)}'}),
      entries: <AppMenuEntry>[
        AppMenuEntry('Open', opener),
        if (advanced) AppMenuEntry(open ? 'Hide window settings' : 'Window settings', () => setState(() => _open = open ? null : key)),
        if (component.onCommit != null) ...<AppMenuEntry>[
          const AppMenuEntry.separator(),
          AppMenuEntry('Detach', () => _detach(document), destructive: true),
        ],
      ],
      body: open ? _settings(document) : null,
    );
  }

  Widget _settings(AnimusDocument document) {
    final JsonMap module = shopModuleOf(document);
    final JsonMap json = asMap(document.json);
    final bool villager = document.kind == DocumentKind.npc && isVillagerBody(json);
    final JsonMap look = asMap(module['villager']);
    final String fieldId = 'animus-shop-user-${document.id.replaceAll(RegExp(r'[^A-Za-z0-9_-]+'), '-')}';
    void put(String key, Object? value) => _commit(document, (JsonMap now) {
          final JsonMap next = <String, Object?>{...now};
          if (value == null) {
            next.remove(key);
          } else {
            next[key] = value;
          }
          return next;
        }, 'edit ${document.id} shop');
    void putLook(String key, Object? value) {
      final JsonMap next = <String, Object?>{...look};
      if (value == null) {
        next.remove(key);
      } else {
        next[key] = value;
      }
      put('villager', next.isEmpty ? null : next);
    }

    return stack(spacing: 16, <Widget>[
      Field(
        label: 'Opens as',
        forId: '$fieldId-style',
        tip: "Replaces the shop's own window for this NPC.",
        child: AnimusSelect.values(
          id: '$fieldId-style',
          value: asString(module['style']),
          values: const <String>['native', 'chest'],
          labels: const <String, String>{'native': 'Trading screen', 'chest': 'Chest window'},
          allowNone: true,
          noneLabel: "The shop's window",
          onChanged: (String? style) => put('style', style),
        ),
      ),
      if (villager) ...<Widget>[
        Field(
          label: 'Profession',
          forId: '$fieldId-profession',
          tip: 'Shown in the trading screen title of this villager. An untitled shop takes its name.',
          child: AnimusSelect.values(
            id: '$fieldId-profession',
            value: asString(look['profession']),
            values: villagerProfessions,
            labels: <String, String>{for (final String id in villagerProfessions) id: id.substring(10).replaceAll('_', ' ')},
            allowNone: true,
            noneLabel: 'None',
            onChanged: (String? profession) => putLook('profession', profession),
          ),
        ),
        Field(
          label: 'Level',
          help: villagerLevels[((asInt(look['level']) ?? 1) - 1).clamp(0, 4)],
          child: CountStepper(value: asInt(look['level']), label: 'Villager level', max: 5, onChanged: (int? level) => putLook('level', level == null || level == 1 ? null : level)),
        ),
      ],
    ]);
  }
}
