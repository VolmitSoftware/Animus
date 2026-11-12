/// Which NPCs open the shop: the NPCs and groups whose `animus:shop` module
/// names it, each with its window style and, for a villager body, the
/// profession and level the trading screen title shows; and an NPC picker
/// that attaches the shop in one undoable change.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_text.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../state/editor_store.dart';
import '../conversation/conversation_attach.dart' show ConversationCommit;
import '../info_tip.dart';
import '../offline/offline_fields.dart' show ConfirmRow;
import 'gui_fields.dart';
import 'shop_offer_editor.dart' show CountStepper;

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
  const ShopLinks({required this.shop, required this.documents, required this.onCommit, this.onOpen, super.key});

  final String shop;
  final List<AnimusDocument> documents;
  final ConversationCommit onCommit;
  final void Function(DocumentKind kind, String id)? onOpen;

  @override
  State<ShopLinks> createState() => _ShopLinksState();
}

class _ShopLinksState extends State<ShopLinks> {
  String? _pick;
  bool _busy = false;

  /// Asking before the attach replaces the picked NPC's shop.
  bool _confirming = false;

  static String _key(AnimusDocument document) => '${document.kind.wireName}:${document.id}';

  static String _name(AnimusDocument document) {
    final String title = guiPlainText(document.title);
    return '${document.kind == DocumentKind.group ? 'Group ' : ''}$title${title == document.id ? '' : ' (${document.id})'}';
  }

  Future<void> _commit(AnimusDocument document, JsonMap? Function(JsonMap module) change, String label) async {
    setState(() => _busy = true);
    try {
      await component.onCommit(<DocumentChange>[
        DocumentChange.update(document.kind, document.id, (Object? latest) => withShopModule(document.kind, asMap(latest), change(shopModuleIn(document.kind, latest)))),
      ], label);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _attach(AnimusDocument document) => _commit(document, (JsonMap module) => <String, Object?>{...module, 'shop': component.shop}, 'attach ${component.shop} to ${document.id}');

  @override
  Widget build(BuildContext context) {
    final List<AnimusDocument> users = shopUsers(component.documents, component.shop);
    final List<AnimusDocument> others = <AnimusDocument>[
      for (final AnimusDocument document in component.documents)
        if ((document.kind == DocumentKind.npc || document.kind == DocumentKind.group) && document.json is Map<String, Object?> && !users.contains(document)) document,
    ];
    final Map<String, AnimusDocument> byKey = <String, AnimusDocument>{for (final AnimusDocument document in others) _key(document): document};
    final AnimusDocument? picked = _pick == null ? null : byKey[_pick];
    final String? replaces = picked == null ? null : asString(shopModuleOf(picked)['shop']);
    return dom.div(classes: 'animus-shop-links', <Widget>[
      dom.h3(classes: 'animus-shop-panel-title', <Widget>[
        withTip(Component.text(users.isEmpty ? 'Used by' : 'Used by (${users.length})'), 'NPCs and groups whose shop module opens this shop on click.'),
      ]),
      if (users.isEmpty)
        const dom.p(classes: 'animus-empty-line', <Widget>[Component.text('No NPC opens it yet.')])
      else
        dom.ul(classes: 'animus-shop-users', <Widget>[for (final AnimusDocument document in users) _user(document)]),
      if (others.isNotEmpty && _confirming && picked != null && replaces != null)
        ConfirmRow(
          question: 'Replace $replaces on ${_name(picked)}?',
          detail: 'It opens ${component.shop} instead.',
          confirmLabel: 'Replace',
          onConfirm: () async {
            setState(() => _confirming = false);
            await _attach(picked);
            if (mounted) setState(() => _pick = null);
          },
          onCancel: () => setState(() => _confirming = false),
        )
      else if (others.isNotEmpty)
        dom.div(classes: 'animus-row animus-shop-attach', <Widget>[
          GuiSelect(
            value: picked == null ? null : _pick,
            options: byKey.keys.toList(),
            labels: <String, String>{for (final MapEntry<String, AnimusDocument> one in byKey.entries) one.key: _name(one.value)},
            noneLabel: 'Attach to NPC...',
            hiddenLabel: 'NPC to attach this shop to',
            onChanged: (String? key) => setState(() {
              _pick = key;
              _confirming = false;
            }),
          ),
          Button.primary(
            label: 'Attach',
            size: ButtonSize.small,
            disabled: picked == null || _busy,
            onPressed: () async {
              final AnimusDocument? target = picked;
              if (target == null) return;
              if (replaces != null) {
                setState(() => _confirming = true);
                return;
              }
              await _attach(target);
              if (mounted) setState(() => _pick = null);
            },
          ),
          if (replaces != null) dom.span(classes: 'animus-gui-help', <Widget>[Component.text('Replaces $replaces.')]),
        ]),
    ]);
  }

  Widget _user(AnimusDocument document) {
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

    return dom.li(attributes: <String, String>{'data-user': _key(document)}, <Widget>[
      dom.div(classes: 'animus-row', <Widget>[
        dom.strong(<Widget>[Component.text(_name(document))]),
        if (component.onOpen != null)
          Button.ghost(label: 'Open', size: ButtonSize.small, attributes: <String, String>{'aria-label': 'Open ${document.id}'}, onPressed: () => component.onOpen!(document.kind, document.id)),
        GuiIconButton(icon: ArcaneIcon.x(size: IconSize.sm), label: 'Detach from ${document.id}', onPressed: () => _commit(document, (JsonMap _) => null, 'detach ${component.shop} from ${document.id}')),
      ]),
      GuiRow(
        label: 'Window',
        forId: '$fieldId-style',
        tip: "Replaces the shop's own style for this NPC.",
        child: GuiSelect(
          id: '$fieldId-style',
          hiddenLabel: 'Window for ${document.id}',
          value: asString(module['style']),
          options: const <String>['native', 'chest'],
          labels: const <String, String>{'native': 'Trading screen', 'chest': 'Chest window'},
          allowNone: true,
          noneLabel: "The shop's style",
          onChanged: (String? style) => put('style', style),
        ),
      ),
      if (villager) ...<Widget>[
        GuiRow(
          label: 'Profession',
          forId: '$fieldId-profession',
          tip: 'Shown in the trading screen title of this villager. An untitled shop takes its name.',
          child: GuiSelect(
            id: '$fieldId-profession',
            hiddenLabel: 'Profession of ${document.id}',
            value: asString(look['profession']),
            options: villagerProfessions,
            labels: <String, String>{for (final String id in villagerProfessions) id: id.substring(10).replaceAll('_', ' ')},
            allowNone: true,
            noneLabel: 'none',
            onChanged: (String? profession) => putLook('profession', profession),
          ),
        ),
        GuiRow(
          label: 'Level',
          tip: 'Novice to Master, added to the title as vanilla does.',
          child: dom.div(classes: 'animus-row', <Widget>[
            CountStepper(value: asInt(look['level']), label: 'Villager level', max: 5, onChanged: (int? level) => putLook('level', level == null || level == 1 ? null : level)),
            dom.span(classes: 'animus-muted', <Widget>[Component.text(villagerLevels[((asInt(look['level']) ?? 1) - 1).clamp(0, 4)])]),
          ]),
        ),
      ],
    ]);
  }
}
