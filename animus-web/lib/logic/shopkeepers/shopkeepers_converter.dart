/// The browser port of core's `ShopkeepersImporter.convert`: a Shopkeepers save.yml (with its optional config.yml and
/// the optional Citizens saves.yml) becomes the same staged `imports/shopkeepers/<importId>/` tree the server writes,
/// byte for byte. Nothing leaves the browser.
///
/// Shared fixtures under schema/fixtures/shopkeepers prove the two agree (core `ShopkeepersGoldenTest`, animus-web
/// `shopkeepers_converter_test.dart`). YAML is read with the Citizens converter's SnakeYAML emulation and its known gaps.
library;

import 'dart:collection';
import 'dart:convert';
import 'dart:typed_data';

import '../data_folder_export.dart';

import '../citizens/citizens_converter.dart' show importOutputHash;
import '../citizens/java_json.dart';
import '../citizens/java_lang.dart';
import '../citizens/snake_yaml.dart';
import '../npc/npc_paths.dart' show npcSchemaVersion;
import 'shopkeepers_items.dart';

/// Must equal `ShopkeepersImporter.VERSION`; the shared golden fixtures fail when they drift.
const int shopkeepersImporterVersion = 3;

const String _namespace = 'shopkeepers';
const int _maximumShopkeepers = 10000;
const int _perPage = 45;
const int _maximumStack = 64;
const int _maximumPages = 64;
const int _maximumContainers = 8;
const int _shopSchemaVersion = 1;
const String _shopModule = 'animus:shop';
const String _notice =
    'Data conversion only. Shops install under shops/shopkeepers/ and open on their NPCs. Try each shop in game before relying on it.';

const Set<String> _handled = <String>{
  'type', 'uniqueId', 'name', 'world', 'x', 'y', 'z', 'yaw', 'open', 'object', 'tradePerm', 'recipes', 'offers', //
  'owner uuid', 'owner', 'containers', 'chestx', 'chesty', 'chestz', 'snapshots',
};
const Set<String> _ownership = <String>{'notifyOnTrades', 'forHire', 'hirecost', 'members', 'expiration', 'expirationNotifications', 'ownedSince'};
const Set<String> _playerTypes = <String>{'sell', 'buy', 'trade', 'book'};

final RegExp _shopkeeperId = RegExp(r'^(?:0|[1-9][0-9]{0,9})$');
final RegExp _offerKey = RegExp(r'^[0-9]{1,9}$');
final RegExp _permission = RegExp(r'^(?:[A-Za-z0-9_-]+(\.[A-Za-z0-9_*-]+)*)$');
final RegExp _entity = RegExp(r'^(?:[a-z0-9_]+:[a-z0-9_/]+)$');

/// Thrown where the server converter throws `IOException`: the save, config or Citizens save is not usable at all.
class ShopkeepersImportException implements Exception {
  const ShopkeepersImportException(this.message);

  final String message;

  @override
  String toString() => message;
}

/// A converted save: [files] maps each path under `imports/shopkeepers/<id>/` to its text, in path order.
class ShopkeepersPreview {
  const ShopkeepersPreview(this.id, this.files, this.summary, this.source, this.config);

  final String id;
  final Map<String, String> files;
  final JObject summary;
  final String source;
  final String? config;

  int _count(JObject from, String key) => (from.get(key)! as JInt).value.toInt();
  int get shops => _count(summary, 'shops');
  int get npcs => _count(summary, 'npcs');
  int get issueCount => (summary.get('issues')! as JArray).length;
  JObject get _offers => summary.object('offers')!;
  JObject get _links => summary.object('links')!;
  int get tradeOffers => _count(_offers, 'trade');
  int get staticOffers => _count(_offers, 'static');
  int get skippedOffers => _count(_offers, 'skipped');
  int get linksFound => _count(_links, 'inCitizensSave');
  int get linksMissing => _count(_links, 'notInCitizensSave');
  int get linksUnchecked => _count(_links, 'unchecked');

  /// The files to unzip into the data folder: the save (and config) where the server looks for them, and the staged tree.
  Map<String, Uint8List> bundle() {
    final String? configText = config;
    return <String, Uint8List>{
      'imports/shopkeepers/save.yml': javaUtf8(source),
      if (configText != null) 'imports/shopkeepers/config.yml': javaUtf8(configText),
      for (final MapEntry<String, String> file in files.entries) 'imports/shopkeepers/$id/${file.key}': javaUtf8(file.value),
    };
  }

  /// `ImportDigest.outputHash` over the staged tree.
  String outputHash() => importOutputHash(<String, Uint8List>{
        for (final MapEntry<String, String> file in files.entries) file.key: javaUtf8(file.value),
      });
}

/// The data-folder bundle as a zip with entries in path order, stamped with [modified] (the export time by default).
Uint8List shopkeepersBundleZip(ShopkeepersPreview preview, {DateTime? modified}) => zipFiles(preview.bundle(), modified: modified);

/// Decodes a source file as the server reads it: strict UTF-8 of at most 16 MiB.
String decodeShopkeepersSource(Uint8List bytes, String label) {
  if (bytes.length > maxDocumentBytes) throw ShopkeepersImportException('$label exceeds $maxDocumentBytes bytes');
  try {
    return utf8.decode(bytes);
  } on FormatException {
    throw ShopkeepersImportException('$label is not valid UTF-8');
  }
}

/// `ShopkeepersImporter.id`.
String shopkeepersImportId(String save, String? config, String? citizens) => sha256Hex('animus-shopkeepers-v$shopkeepersImporterVersion\n'
    '${sha256Hex(save)}\n${config == null ? 'missing' : sha256Hex(config)}\n${citizens == null ? 'missing' : sha256Hex(citizens)}\n');

JObject _parse(String content) {
  try {
    return parseCitizensYaml(content);
  } on CitizensImportException catch (failure) {
    throw ShopkeepersImportException(failure.message);
  }
}

/// Per-field classification for one shopkeeper, as JSON-pointer paths. A later classification of a path wins.
class _Report {
  final SplayTreeMap<String, String> fields = SplayTreeMap<String, String>();
  final JArray notes = JArray();

  void field(String path, String status, [String? note]) {
    fields[path] = status;
    if (note != null && note.isNotEmpty) notes.addString('$path: $note');
  }

  void note(String note) => notes.addString(note);

  JObject json() {
    final JObject json = JObject();
    json.addString('fieldPathFormat', 'json-pointer');
    final JObject byPath = JObject();
    fields.forEach(byPath.addString);
    json.add('fields', byPath);
    json.add('notes', notes.deepCopy());
    final JObject counts = JObject();
    for (final String status in fields.values) {
      final JElement? current = counts.get(status);
      counts.addInt(status, current is JInt ? current.value.toInt() + 1 : 1);
    }
    json.add('counts', counts);
    return json;
  }
}

const String _converted = 'CONVERTED';
const String _changed = 'CONVERTED_WITH_CHANGES';
const String _review = 'REQUIRES_REVIEW';
const String _preserved = 'UNSUPPORTED_PRESERVED';

class _Currency {
  const _Currency(this.low, this.high, this.highValue, this.highMinimum, this.log, this.summary);

  final ShopkeepersItem? low;
  final ShopkeepersItem? high;
  final int highValue;
  final int highMinimum;
  final bool log;
  final JObject summary;
}

/// Running counts across the whole save.
class _Totals {
  final List<int> items = <int>[0, 0, 0];
  final SplayTreeMap<String, int> dataVersions = SplayTreeMap<String, int>();
}

/// `ShopkeepersImporter.convert`. Throws [ShopkeepersImportException] where Java throws `IOException`.
ShopkeepersPreview convertShopkeepers(String save, {String? config, String? citizens}) {
  final JObject root = _parse(save);
  final String id = shopkeepersImportId(save, config, citizens);
  final Map<String, String> files = <String, String>{'source.yml': save};
  if (config != null) files['config.yml'] = config;
  final JArray issues = JArray();
  final _Currency currency = _currencyOf(config == null ? null : _parse(config), issues);
  final Map<String, String> citizensIds = <String, String>{};
  final Map<String, List<String>> citizensUuids = <String, List<String>>{};
  if (citizens != null) {
    final JObject saves = _parse(citizens);
    final JObject npcs = saves.object('npc') ?? JObject();
    for (final MapEntry<String, JElement> npc in npcs.entries) {
      if (!_shopkeeperId.hasMatch(npc.key) || int.parse(npc.key) > 2147483647 || npc.value is! JObject) continue;
      final JElement? uuid = (npc.value as JObject).get('uuid');
      final String value = uuid is JPrimitive ? uuid.asString : nameUuid('animus:citizens:${npc.key}');
      citizensIds[npc.key] = value;
      citizensUuids.putIfAbsent(value, () => <String>[]).add(npc.key);
    }
  }
  final SplayTreeMap<String, int> types = SplayTreeMap<String, int>();
  final SplayTreeMap<String, int> objects = SplayTreeMap<String, int>();
  final _Totals totals = _Totals();
  final List<int> offers = <int>[0, 0, 0, 0];
  final List<int> links = <int>[0, 0, 0];
  final JArray bindings = JArray();
  final JObject shopMap = JObject();
  final JObject manifest = JObject();
  int shops = 0;
  int npcCount = 0;
  for (final MapEntry<String, JElement> entry in root.entries) {
    final String key = entry.key;
    if (key == 'data-version') continue;
    if (!_shopkeeperId.hasMatch(key) || int.parse(key) > 2147483647) {
      issues.addString('$key: not a shopkeeper id; kept in source.yml');
      continue;
    }
    if (entry.value is! JObject) {
      issues.addString('$key: not a shopkeeper mapping; kept in source.yml');
      continue;
    }
    if (shops >= _maximumShopkeepers) throw const ShopkeepersImportException('More than $_maximumShopkeepers shopkeepers');
    final int number = int.parse(key);
    final JObject record = entry.value as JObject;
    final _Report report = _Report();
    String type = javaLower(_string(record, 'type') ?? '');
    if (type == 'player') {
      type = 'sell';
      report.field('/type', _changed, 'the old player type is a selling shop');
    } else {
      report.field('/type', _converted);
    }
    if (type != 'admin' && !_playerTypes.contains(type)) {
      issues.addString('$key: shop type ${type.isEmpty ? '(none)' : type} is not converted; kept in source.yml');
      continue;
    }
    final JObject object = record.object('object') ?? JObject();
    final String objectType = javaLower(_string(object, 'type') ?? 'virtual');
    types[type] = (types[type] ?? 0) + 1;
    objects[objectType] = (objects[objectType] ?? 0) + 1;
    final String shopId = '$_namespace:shop-$number';
    final String shopPath = 'shops/shopkeepers/shop-$number.json';
    final JObject shop = JObject();
    shop.addInt('schemaVersion', _shopSchemaVersion);
    shop.addInt('revision', 1);
    shop.addString('id', shopId);
    final String? rawName = _string(record, 'name');
    final String name = rawName == null ? '' : javaStrip(rawName);
    report.field('/name', _converted);
    final String? rawOwner = _string(record, 'owner');
    final String? ownerName = rawOwner != null && !javaIsBlank(rawOwner) ? rawOwner : null;
    final String title = name.isNotEmpty ? name : (ownerName != null ? "$ownerName's shop" : 'Shopkeeper $number');
    shop.addString('title', title.length > 256 ? title.substring(0, 256) : title);
    if (type == 'admin' && record.has('tradePerm')) {
      final String? rawNode = _string(record, 'tradePerm');
      final String? node = rawNode == null ? null : javaTrim(rawNode);
      if (node != null && node.length <= 128 && _permission.hasMatch(node)) {
        shop.addString('permission', node);
        report.field('/tradePerm', _converted);
      } else {
        report.field('/tradePerm', _review, 'not a permission node Animus accepts; the shop is open to everyone; set permission by hand');
      }
    }
    shop.addString('style', 'native');
    shop.add('modes', JArray(<JElement>[const JString('trade')]));
    shop.addString('defaultMode', 'trade');
    shop.addString('match', 'strict');
    if (currency.log) shop.addBool('log', true);
    final JElement? openValue = record.get('open');
    final bool open = openValue is! JBool || openValue.value;
    if (record.has('open')) {
      report.field('/open', open ? _converted : _changed,
          open ? null : 'the shop was closed; its offers show but do not trade until you change them to trade entries');
    }
    final bool player = _playerTypes.contains(type);
    bool stocked = false;
    if (player) {
      final String? ownerText = _string(record, 'owner uuid');
      final String? owner = ownerText != null && canonicalUuid(ownerText) ? ownerText : null;
      final JArray containers = _containers(record, report);
      if (owner != null) {
        final JObject owned = JObject();
        owned.addString('uuid', owner);
        if (ownerName != null) owned.addString('name', ownerName);
        shop.add('owner', owned);
        report.field('/owner uuid', _converted);
        if (record.has('owner')) report.field('/owner', _converted);
      } else {
        report.field('/owner uuid', _review, "no valid owner; the shop's offers show but do not trade");
      }
      if (owner != null && containers.isNotEmpty) {
        shop.add('containers', containers);
        stocked = true;
      } else if (owner != null) {
        report.note('the shop has no container; its offers show but do not trade until one is added');
      }
    }
    final List<JObject> entries = <JObject>[];
    final String list = type == 'admin' ? 'recipes' : 'offers';
    final JObject source = record.object(list) ?? JObject();
    final JElement? listValue = record.get(list);
    if (listValue != null && listValue is! JObject && listValue is! JNull) {
      report.field('/$list', _preserved, 'not a mapping of offers; kept in source.yml');
    }
    final bool tradable = open && (!player || stocked);
    final Set<String> entryIds = <String>{};
    int position = 0;
    for (final MapEntry<String, JElement> offer in source.entries) {
      offers[0]++;
      position++;
      final String path = '/$list/${_pointer(offer.key)}';
      if (entries.length >= _perPage * _maximumPages) {
        offers[3]++;
        report.field(path, _preserved, 'more than ${_perPage * _maximumPages} offers');
        continue;
      }
      String entryId = _offerKey.hasMatch(offer.key) ? 'offer-${offer.key}' : 'offer-n$position';
      for (int suffix = 2; entryIds.contains(entryId); suffix++) {
        entryId = 'offer-n$position-$suffix';
      }
      entryIds.add(entryId);
      final JObject? converted = _offer(type, entryId, offer.value, path, currency, tradable, report, totals);
      if (converted == null) {
        offers[3]++;
        continue;
      }
      if (converted.get('type')!.asPrimitive.asString == 'trade') {
        offers[1]++;
      } else {
        offers[2]++;
      }
      entries.add(converted);
    }
    final JArray pages = JArray();
    for (int start = 0; start < (entries.isEmpty ? 1 : entries.length); start += _perPage) {
      final List<JObject> chunk = entries.sublist(start < entries.length ? start : entries.length, start + _perPage < entries.length ? start + _perPage : entries.length);
      final JObject page = JObject();
      page.addString('name', 'Page ${pages.length + 1}');
      final int rows = (chunk.length + 8) ~/ 9 + 1;
      page.addInt('rows', rows < 6 ? rows : 6);
      if (chunk.isNotEmpty) {
        final JObject grid = JObject();
        for (int slot = 0; slot < chunk.length; slot++) {
          grid.add('$slot', chunk[slot]);
        }
        page.add('trade', grid);
      }
      pages.add(page);
    }
    shop.add('pages', pages);
    final JObject imported = JObject();
    imported.addString('source', 'Shopkeepers');
    imported.addInt('shopkeeper', number);
    final String? uniqueId = _string(record, 'uniqueId');
    if (uniqueId != null) imported.addString('uniqueId', uniqueId);
    imported.addString('type', type);
    final JObject location = JObject();
    for (final String axis in const <String>['world', 'x', 'y', 'z', 'yaw']) {
      if (record.has(axis)) {
        location.add(axis, record.get(axis)!.deepCopy());
        report.field('/$axis', _converted);
      }
    }
    imported.add('location', location);
    imported.add('object', object.deepCopy());
    if (!open) imported.addBool('open', false);
    for (final MapEntry<String, JElement> field in record.entries) {
      if (!_ownership.contains(field.key)) continue;
      imported.add(field.key, field.value.deepCopy());
      report.field('/${field.key}', _preserved, "kept in the shop's imported section; Animus does not use it");
    }
    shop.add('imported', imported);
    report.field('/uniqueId', _converted);
    if (record.has('snapshots')) report.field('/snapshots', _preserved, 'shopkeeper snapshots are kept in source.yml only');
    for (final String field in record.keys) {
      if (!_handled.contains(field) && !_ownership.contains(field)) report.field('/${_pointer(field)}', _preserved, 'kept in source.yml');
    }
    report.field('/object/type', _converted);
    JObject? npc;
    switch (objectType) {
      case 'citizen' || 'npc':
        final JObject binding = _binding(number, shopId, object, citizens != null, citizensIds, citizensUuids, report);
        final String state = binding.get('status')!.asPrimitive.asString;
        links[state == 'in-citizens-save' ? 0 : (state == 'unchecked' ? 2 : 1)]++;
        bindings.add(binding);
        for (final String field in object.keys) {
          if (field != 'type' && field != 'npcId') {
            report.field('/object/${_pointer(field)}', _preserved, "Citizens keeps this NPC's data; kept in the shop's imported section");
          }
        }
      case 'sign' || 'hanging-sign':
        for (final String field in object.keys) {
          if (field != 'type') report.field('/object/${_pointer(field)}', _preserved, "kept in the shop's imported section");
        }
        report.note('a sign shopkeeper gets no NPC; open the shop with /animus shop open $shopId or bind it to an NPC');
      case 'virtual':
        report.note('a virtual shopkeeper has no object; open the shop with /animus shop open $shopId');
      default:
        npc = _mob(id, number, shopId, objectType, record, object, report, issues);
        if (npc != null) npcCount++;
    }
    final String text = writeJson(shop);
    files[shopPath] = text;
    manifest.addString(shopPath, sha256Hex(text));
    final JObject mapped = JObject();
    mapped.addInt('shopkeeper', number);
    mapped.addString('document', shopPath);
    mapped.addInt('entries', entries.length);
    if (npc != null) {
      final String npcPath = 'npcs/shopkeepers/shopkeeper-$number.json';
      final String npcText = writeJson(npc);
      files[npcPath] = npcText;
      manifest.addString(npcPath, sha256Hex(npcText));
      mapped.addString('npc', npcPath);
    }
    shopMap.add(shopId, mapped);
    final JObject reportJson = report.json();
    reportJson.addInt('shopkeeper', number);
    reportJson.addString('shop', shopId);
    files['reports/$number.json'] = writeJson(reportJson);
    shops++;
  }
  final JObject summary = JObject();
  summary.addInt('schemaVersion', 1);
  summary.addInt('importerVersion', shopkeepersImporterVersion);
  summary.addString('importId', id);
  summary.addString('sourceSha256', sha256Hex(save));
  if (config != null) summary.addString('configSha256', sha256Hex(config));
  if (citizens != null) summary.addString('citizensSha256', sha256Hex(citizens));
  summary.addString('notice', _notice);
  final JElement? dataVersion = root.get('data-version');
  summary.add('dataVersion', dataVersion is JPrimitive ? dataVersion.deepCopy() : JNull.instance);
  summary.addInt('shops', shops);
  summary.addInt('npcs', npcCount);
  summary.add('types', _counts(types));
  summary.add('objects', _counts(objects));
  summary.add(
      'offers',
      JObject()
        ..addInt('total', offers[0])
        ..addInt('trade', offers[1])
        ..addInt('static', offers[2])
        ..addInt('skipped', offers[3]));
  summary.add(
      'items',
      JObject()
        ..addInt('total', totals.items[0])
        ..addInt('withComponents', totals.items[1])
        ..addInt('preservedOnly', totals.items[2])
        ..add('dataVersions', _counts(totals.dataVersions)));
  summary.add('currency', currency.summary.deepCopy());
  summary.add(
      'links',
      JObject()
        ..addInt('inCitizensSave', links[0])
        ..addInt('notInCitizensSave', links[1])
        ..addInt('unchecked', links[2]));
  summary.add('bindings', bindings);
  summary.add('shopMap', shopMap);
  summary.add('issues', issues);
  summary.add('documents', manifest);
  files['report.json'] = writeJson(summary);
  final List<String> paths = files.keys.toList()..sort();
  return ShopkeepersPreview(id, <String, String>{for (final String path in paths) path: files[path]!}, summary, save, config);
}

JObject? _offer(String type, String entryId, JElement value, String path, _Currency currency, bool tradable, _Report report, _Totals totals) {
  if (value is! JObject) {
    report.field(path, _preserved, 'not an offer mapping; kept in source.yml');
    return null;
  }
  final JObject offer = value;
  if (type == 'book') {
    report.field(path, _preserved,
        "a book offer copies a written book from the shop's chest by title; Animus trades exact items only, so it is kept in source.yml");
    return null;
  }
  final List<JObject> needs = <JObject>[];
  final List<JObject> gives = <JObject>[];
  final List<String> notes = <String>[];
  bool exact = true;
  final ShopkeepersItem icon;
  if (type == 'admin' || type == 'trade') {
    final ShopkeepersItem? result = _item(offer.get('resultItem'), totals);
    final ShopkeepersItem? first = _item(offer.get('item1'), totals);
    final ShopkeepersItem? second = _item(offer.get('item2'), totals);
    if (result == null || first == null) {
      report.field(path, _preserved, 'the offer has no result item or no first item; kept in source.yml');
      return null;
    }
    icon = result;
    for (final ShopkeepersItem need in <ShopkeepersItem>[first, ?second]) {
      needs.add(_goods(need.item, need.amount));
    }
    gives.add(_goods(icon.item, icon.amount));
    for (final ShopkeepersItem part in <ShopkeepersItem>[result, first, ?second]) {
      exact &= part.exact;
      notes.addAll(part.notes);
    }
  } else {
    final ShopkeepersItem? sold = _item(offer.get('item'), totals);
    final int? price = shopkeepersInteger(offer.get('price'));
    if (sold == null || price == null || price < 1) {
      report.field(path, _preserved, 'the offer has no item or no price of at least 1; kept in source.yml');
      return null;
    }
    final ShopkeepersItem? low = currency.low;
    if (low == null) {
      report.field(path, _preserved, 'the currency item in config.yml is not an item; kept in source.yml');
      return null;
    }
    icon = sold;
    final ShopkeepersItem? high = currency.high;
    exact = sold.exact && low.exact && (high?.exact ?? true);
    notes.addAll(sold.notes);
    if (type == 'sell') {
      int remaining = price;
      if (high != null && price > currency.highMinimum) {
        final int quotient = price ~/ currency.highValue;
        final int count = quotient < _maximumStack ? quotient : _maximumStack;
        if (count > 0) {
          needs.add(_goods(high.item, count));
          remaining -= count * currency.highValue;
        }
      }
      if (remaining > _maximumStack) {
        report.field(path, _preserved, 'price $price needs more than one stack of each currency item, which Shopkeepers also skips');
        return null;
      }
      if (remaining > 0) needs.add(_goods(low.item, remaining));
      gives.add(_goods(icon.item, icon.amount));
    } else {
      if (price > _maximumStack) {
        report.field(path, _preserved, 'price $price is more than one stack of the currency item, which Shopkeepers also skips');
        return null;
      }
      needs.add(_goods(icon.item, icon.amount));
      gives.add(_goods(low.item, price));
    }
  }
  final JObject entry = JObject();
  entry.addString('id', entryId);
  final bool trade = exact && tradable;
  entry.addString('type', trade ? 'trade' : 'static');
  entry.add('item', icon.item.toJson());
  final JArray needList = JArray(<JElement>[...needs]);
  final JArray giveList = JArray(<JElement>[...gives]);
  if (trade) {
    entry.add('needs', needList);
    entry.add('gives', giveList);
  } else {
    entry.add(
        'imported',
        JObject()
          ..addString('source', 'Shopkeepers')
          ..add('needs', needList)
          ..add('gives', giveList));
  }
  if (!exact) {
    report.field(path, _review, 'item data Animus cannot write exactly is kept in imported, so the offer shows but does not trade: ${notes.join('; ')}');
  } else if (!tradable) {
    report.field(path, _changed, 'converted as a display entry; the shop cannot trade yet');
  } else {
    report.field(path, notes.isEmpty ? _converted : _changed, notes.isEmpty ? null : notes.join('; '));
  }
  return entry;
}

ShopkeepersItem? _item(JElement? value, _Totals totals) {
  final ShopkeepersItem? item = convertShopkeepersItem(value);
  if (item == null) return null;
  totals.items[0]++;
  if (item.components) totals.items[1]++;
  if (!item.exact) totals.items[2]++;
  final int? version = item.dataVersion;
  if (version != null) totals.dataVersions['$version'] = (totals.dataVersions['$version'] ?? 0) + 1;
  return item;
}

JObject _goods(NpcItem item, int amount) => JObject()
  ..add('item', NpcItem(item.id, 1, item.components, null).toJson())
  ..addInt('amount', amount);

JArray _containers(JObject record, _Report report) {
  final JArray containers = JArray();
  final Set<String> seen = <String>{};
  final JElement? list = record.get('containers');
  if (list is JArray) {
    bool split = false;
    for (final JElement value in list.elements) {
      if (value is! JObject || containers.length >= _maximumContainers) continue;
      final String? world = _nonBlank(_string(value, 'world'));
      final int? x = shopkeepersInteger(value.get('x'));
      final int? y = shopkeepersInteger(value.get('y'));
      final int? z = shopkeepersInteger(value.get('z'));
      if (world == null || x == null || y == null || z == null) continue;
      final String? kind = _string(value, 'type');
      if (kind != null && kind != 'stock-and-earnings') split = true;
      _addContainer(containers, seen, world, x, y, z);
    }
    report.field('/containers', split ? _changed : _converted,
        split ? 'stock and earnings containers are one pool in Animus: trades take from and pay into all of them' : null);
  } else if (record.has('chestx')) {
    final String? world = _nonBlank(_string(record, 'world'));
    final int? x = shopkeepersInteger(record.get('chestx'));
    final int? y = shopkeepersInteger(record.get('chesty'));
    final int? z = shopkeepersInteger(record.get('chestz'));
    if (world != null && x != null && y != null && z != null) _addContainer(containers, seen, world, x, y, z);
    for (final String axis in const <String>['chestx', 'chesty', 'chestz']) {
      if (record.has(axis)) report.field('/$axis', _converted);
    }
  }
  return containers;
}

void _addContainer(JArray containers, Set<String> seen, String world, int x, int y, int z) {
  if (!_within(x, 30000000) || !_within(z, 30000000) || !_within(y, 2048) || !seen.add('$world\n$x\n$y\n$z')) return;
  containers.add(JObject()
    ..addString('world', world)
    ..addInt('x', x)
    ..addInt('y', y)
    ..addInt('z', z));
}

JObject _binding(int number, String shopId, JObject object, bool checked, Map<String, String> ids, Map<String, List<String>> uuids, _Report report) {
  final JObject binding = JObject();
  binding.addInt('shopkeeper', number);
  binding.addString('shop', shopId);
  final JElement? npcId = object.get('npcId');
  final String? uuid = npcId is JPrimitive && canonicalUuid(npcId.asString) ? npcId.asString : null;
  final int? numeric = npcId is JNumber ? shopkeepersInteger(npcId) : null;
  String? key;
  if (uuid != null) {
    binding.addString('citizensUuid', uuid);
    final List<String> matches = uuids[uuid] ?? const <String>[];
    if (matches.length == 1) key = matches.first;
  } else if (numeric != null && numeric >= 0) {
    binding.addInt('citizensId', numeric);
    if (ids.containsKey('$numeric')) key = '$numeric';
  } else {
    binding.addString('status', 'no-npc');
    report.field('/object/npcId', _preserved, 'the shopkeeper names no Citizens NPC; the shop opens only by command');
    return binding;
  }
  report.field('/object/npcId', _converted);
  if (!checked) {
    binding.addString('status', 'unchecked');
    return binding;
  }
  if (key == null) {
    binding.addString('status', 'not-in-citizens-save');
    report.note('the Citizens NPC is not in the Citizens save given; the binding waits for an NPC with that identity');
    return binding;
  }
  binding.addString('status', 'in-citizens-save');
  if (!binding.has('citizensId')) {
    final int parsed = int.parse(key);
    // Java's Integer.parseInt throws here; Citizens itself refuses such ids.
    if (parsed > 2147483647) throw ShopkeepersImportException('Citizens NPC id $key exceeds integer range');
    binding.addInt('citizensId', parsed);
  }
  if (!binding.has('citizensUuid')) binding.addString('citizensUuid', ids[key]!);
  binding.addString('npc', 'citizens-$key');
  return binding;
}

JObject? _mob(String importId, int number, String shopId, String objectType, JObject record, JObject object, _Report report, JArray issues) {
  final String entity = 'minecraft:${objectType.replaceAll('-', '_')}';
  if (!_entity.hasMatch(entity)) {
    issues.addString('$number: object type $objectType is not an entity; the shop has no NPC');
    return null;
  }
  final String? world = _nonBlank(_string(record, 'world'));
  final int? x = shopkeepersInteger(record.get('x'));
  final int? y = shopkeepersInteger(record.get('y'));
  final int? z = shopkeepersInteger(record.get('z'));
  if (world == null || x == null || y == null || z == null || !_within(x, 30000000) || !_within(y, 30000000) || !_within(z, 30000000)) {
    issues.addString('$number: the shopkeeper has no usable location; no NPC was created');
    return null;
  }
  final JObject definition = JObject();
  definition.addInt('schemaVersion', npcSchemaVersion);
  definition.addInt('revision', 1);
  final String? unique = _string(record, 'uniqueId');
  definition.addString('uuid', unique != null && canonicalUuid(unique) ? unique : nameUuid('animus:shopkeepers:$number'));
  definition.addString('id', 'shopkeeper-$number');
  definition.addString('group', _namespace);
  final String? rawName = _string(record, 'name');
  final String? stripped = rawName == null ? null : javaStrip(rawName);
  final bool usable = stripped != null && stripped.isNotEmpty && stripped.length <= 256 && !stripped.runes.any(javaIsIsoControl);
  definition.addString('name', usable ? stripped : 'Shopkeeper $number');
  definition.addString('type', entity);
  definition.addBool('spawned', true);
  final JObject position = JObject();
  position.addString('world', world);
  position.addDouble('x', x + 0.5);
  position.addInt('y', y);
  position.addDouble('z', z + 0.5);
  final JElement? yaw = record.get('yaw');
  final bool finite = yaw is JNumber && yaw.asDouble.isFinite && yaw.asDouble.abs() <= 30000000;
  position.add('yaw', finite ? yaw.deepCopy() : JInt.of(0));
  position.addInt('pitch', 0);
  definition.add('position', position);
  definition.add(
      'migration',
      JObject()
        ..addString('importId', importId)
        ..addString('source', 'Shopkeepers')
        ..addInt('shopkeeper', number));
  final JObject modules = JObject();
  final JObject review = object.deepCopy();
  review.remove('type');
  final JObject? villager = entity == 'minecraft:villager' ? _villager(review, report) : null;
  modules.add(_shopModule, _module(number, shopId, villager));
  if (review.isNotEmpty) {
    modules.add('shopkeepers:review', review);
    for (final String field in review.keys) {
      report.field('/object/${_pointer(field)}', _review, "mob settings are kept in the NPC's shopkeepers:review section");
    }
  }
  definition.add('modules', modules);
  report.note("a new $entity NPC stands at the shopkeeper's place");
  return definition;
}

final RegExp _profession = RegExp(r'^[a-z0-9_.-]+:[a-z0-9_/.-]+$');

/// `ShopkeepersImporter.villager`: a villager shopkeeper's profession and level, taken out of the review section.
JObject? _villager(JObject review, _Report report) {
  final JObject villager = JObject();
  final String? raw = _string(review, 'profession');
  if (raw != null) {
    final String lowered = javaLower(javaTrim(raw));
    final String profession = lowered.contains(':') ? lowered : 'minecraft:$lowered';
    if (_profession.hasMatch(profession)) {
      villager.addString('profession', profession);
      review.remove('profession');
      report.field('/object/profession', _converted);
    }
  }
  final int? level = shopkeepersInteger(review.get('villagerLevel'));
  if (level != null && level >= 1 && level <= 5) {
    villager.addInt('level', level);
    review.remove('villagerLevel');
    report.field('/object/villagerLevel', _converted);
  }
  return villager.isEmpty ? null : villager;
}

/// `ShopkeepersImporter.module`: the `animus:shop` section an imported binding writes.
JObject _module(int number, String shopId, [JObject? villager]) {
  final JObject module = JObject()
    ..addString('shop', shopId)
    ..addString('openOn', 'right');
  if (villager != null) module.add('villager', villager);
  return module
    ..add(
        'imported',
        JObject()
          ..addString('source', 'Shopkeepers')
          ..addInt('shopkeeper', number));
}

_Currency _currencyOf(JObject? config, JArray issues) {
  final JObject settings = config ?? JObject();
  final ShopkeepersItem? low = convertShopkeepersItem(settings.has('currency-item') ? settings.get('currency-item') : const JString('minecraft:emerald'));
  if (low == null) issues.addString('config.yml: currency-item is not an item; selling and buying offers are kept in source.yml');
  ShopkeepersItem? high =
      convertShopkeepersItem(settings.has('high-currency-item') ? settings.get('high-currency-item') : const JString('minecraft:emerald_block'));
  final int value = settings.has('high-currency-value') ? (shopkeepersInteger(settings.get('high-currency-value')) ?? 9) : 9;
  final int minimum = settings.has('high-currency-min-cost') ? (shopkeepersInteger(settings.get('high-currency-min-cost')) ?? 20) : 20;
  if (value <= 0) high = null;
  final String log = settings.has('trade-log-storage') ? javaUpper(shopkeepersScalar(settings.get('trade-log-storage')) ?? 'DISABLED') : 'DISABLED';
  final JObject summary = JObject();
  summary.addBool('config', config != null);
  summary.add('item', low?.item.toJson() ?? JNull.instance);
  summary.add('highItem', high?.item.toJson() ?? JNull.instance);
  summary.addInt('highValue', value);
  summary.addInt('highMinimumCost', minimum);
  summary.addString('tradeLog', javaLower(log));
  return _Currency(low, high, value, minimum, log != 'DISABLED', summary);
}

JObject _counts(SplayTreeMap<String, int> counts) {
  final JObject json = JObject();
  counts.forEach(json.addInt);
  return json;
}

String? _string(JObject source, String key) {
  final JElement? value = source.get(key);
  return value is JPrimitive ? value.asString : null;
}

String? _nonBlank(String? text) => text != null && !javaIsBlank(text) ? text : null;

bool _within(int value, int bound) => value >= -bound && value <= bound;

String _pointer(String key) => key.replaceAll('~', '~0').replaceAll('/', '~1');
