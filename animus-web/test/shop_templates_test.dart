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
  for (final ShopTemplate template in ShopTemplate.values) {
    test('should make a valid shop from the ${template.name} template', () {
      final JsonMap shop = shopFromTemplate(template, 'town:stall', title: template == ShopTemplate.money ? '&eStall' : null);
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
}
