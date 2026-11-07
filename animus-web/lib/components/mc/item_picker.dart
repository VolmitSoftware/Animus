/// The one item id field: a searchable suggestion list over the item
/// registry bundled with the Minecraft pack (`registries.json`, fetched once
/// per session), the field's own suggestions first, and the item's inventory
/// icon beside it. Free text stays allowed for modded ids and expressions.
library;

import 'dart:convert';
import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/item_ids.dart';
import '../../mc/assets/mc_pack_manifest.dart';
import '../select/suggest_input.dart';
import 'mc_asset_loader.dart';
import 'mc_item_icons.dart';

/// The bundled item registry, loaded on first use; empty when the pack has none.
final class ItemRegistry {
  ItemRegistry._();

  static List<String> _ids = const <String>[];
  static Set<String> _known = const <String>{};
  static Future<List<String>>? _loading;

  static List<String> get ids => _ids;

  static bool has(String id) => _known.contains(id);

  static Future<List<String>> load() => _loading ??= _fetch().then((List<String> ids) {
    _known = ids.toSet();
    return _ids = ids;
  });

  static Future<List<String>> _fetch() async {
    try {
      final web.Response response = await web.window.fetch('${mcPackBaseUrl(McAssetLoader.version)}/registries.json'.toJS).toDart;
      if (!response.ok) return const <String>[];
      return parseItemRegistry(jsonDecode((await response.text().toDart).toDart));
    } on Object {
      return const <String>[];
    }
  }
}

class ItemPicker extends StatefulWidget {
  const ItemPicker({
    required this.value,
    required this.onChanged,
    required this.label,
    this.id,
    this.placeholder = 'minecraft:stone',
    this.preferred = const <String>[],
    this.icon = true,
    super.key,
  });

  final String value;
  final void Function(String value) onChanged;

  /// The field's accessible name.
  final String label;
  final String? id;
  final String placeholder;

  /// Ids offered before the registry, such as armour for a helmet slot.
  final List<String> preferred;

  /// False where the row draws the icon itself.
  final bool icon;

  @override
  State<ItemPicker> createState() => _ItemPickerState();
}

class _ItemPickerState extends State<ItemPicker> {
  @override
  void initState() {
    super.initState();
    McItemIcons.addListener(_refresh);
    if (ItemRegistry.ids.isEmpty) {
      ItemRegistry.load().then((List<String> _) => _refresh());
    }
  }

  @override
  void dispose() {
    McItemIcons.removeListener(_refresh);
    super.dispose();
  }

  void _refresh() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final String value = component.value;
    final String? key = McItemIcons.keyOf(value);
    final String? icon = component.icon && key != null ? McItemIcons.url(key) : null;
    // A finished id lists everything again, so a filled field still offers others.
    final bool listed = ItemRegistry.has(value);
    final bool unknown = !listed && ItemRegistry.ids.isNotEmpty && value.startsWith('minecraft:') && isItemId(value);
    return dom.div(classes: 'animus-item-picker${component.icon ? '' : ' is-bare'}', <Widget>[
      if (component.icon)
        dom.span(
          classes: 'animus-item-picker-icon',
          attributes: const <String, String>{'aria-hidden': 'true'},
          <Widget>[
            if (icon != null) dom.img(src: icon, alt: '', classes: 'animus-item-picker-img') else Component.text(value.isEmpty || key == null ? '' : '?'),
          ],
        ),
      SuggestInput(
        id: component.id,
        value: value,
        label: component.label,
        placeholder: component.placeholder,
        suggestions: itemOptions(ItemRegistry.ids, listed ? '' : value, preferred: component.preferred),
        onChanged: component.onChanged,
      ),
      if (unknown) const dom.span(classes: 'animus-gui-help animus-item-picker-note', <Widget>[Component.text('Not a 26.3 item.')]),
    ]);
  }
}
