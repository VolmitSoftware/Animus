import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/gui/gui_validation.dart';
import 'package:animus_editor/logic/gui/shop_board.dart';
import 'package:animus_editor/logic/gui/shop_checks.dart';
import 'package:animus_editor/logic/gui/shop_templates.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

void main() {
  const ChestShopSeed steve = ChestShopSeed(owner: '00000000-0000-4000-8000-000000000002', ownerName: 'Steve', world: 'world', x: 10, y: 64, z: -3, kind: 'selling');

  for (final ShopTemplate template in ShopTemplate.values) {
    test('should make a valid shop from the ${template.name} template', () {
      final JsonMap shop = shopFromTemplate(template, 'town:stall', title: template == ShopTemplate.money ? '&eStall' : null, chest: steve);
      final List<Diagnostic> errors = <Diagnostic>[
        ...SharedSchemas.embedded.validate(DocumentKind.shop, shop),
        ...validateGui(DocumentKind.shop, shop),
        ...shopOfferChecks(shop),
      ].where((Diagnostic d) => d.severity == DiagnosticSeverity.error).toList();
      expect(errors.map((Diagnostic d) => '${d.path} ${d.message}'), isEmpty);
      expect(shop['id'], 'town:stall');
    });
  }

  test('should fit each template to its purpose', () {
    expect(boardOffers(shopFromTemplate(ShopTemplate.blank, 'a:b')), isEmpty);
    final JsonMap villager = shopFromTemplate(ShopTemplate.villager, 'a:b');
    expect(villager['style'], 'native');
    expect(boardOffers(villager).every((BoardOffer o) => o.type == 'trade'), isTrue);
    expect(shopStyleNotes(villager, 'native'), isEmpty, reason: 'villager trades show exactly on the trading screen');
    expect(boardOffers(shopFromTemplate(ShopTemplate.money, 'a:b')).map((BoardOffer o) => o.type).toSet(), <String>{'buy', 'sell'});
    expect(boardOffers(shopFromTemplate(ShopTemplate.command, 'a:b')).single.type, 'command');
    expect(shopFromTemplate(ShopTemplate.money, 'a:b', title: ' &eStall ')['title'], '&eStall');
  });

  test('should make a chest shop as the game makes one: owner, chest and kind, trades only', () {
    expect(ShopTemplate.values.map((ShopTemplate t) => t.label), contains('Chest shop'));
    final JsonMap shop = shopFromTemplate(ShopTemplate.chest, 'players:stall', chest: steve);
    expect(shop['kind'], 'selling');
    expect(shop['owner'], <String, Object?>{'uuid': '00000000-0000-4000-8000-000000000002', 'name': 'Steve'});
    expect(shop['containers'], <Object?>[<String, Object?>{'world': 'world', 'x': 10, 'y': 64, 'z': -3}]);
    expect(shop['modes'], <Object?>['trade']);
    expect(shop['style'], 'native');
    expect(shop['title'], "Steve's shop");
    expect(boardOffers(shop), isEmpty);
    final JsonMap book = shopFromTemplate(ShopTemplate.chest, 'players:books', title: 'Library', chest: const ChestShopSeed(owner: '00000000-0000-4000-8000-000000000002', world: 'world', x: 0, y: 70, z: 0, kind: 'book'));
    expect(book['kind'], 'book');
    expect(book['title'], 'Library');
    expect(asMap(book['owner']).containsKey('name'), isFalse);
  });
}
