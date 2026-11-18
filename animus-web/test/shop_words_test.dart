import 'package:animus_editor/logic/gui/shop_words.dart';
import 'package:test/test.dart';

void main() {
  group('shop words', () {
    test('should head each offer group from the shop side, as Kind reads', () {
      // A buy grid holds what the shop sells; a sell grid what it buys.
      expect(offerGridTitles, <String, String>{'buy': 'Sells', 'sell': 'Buys', 'trade': 'Trades'});
      expect(offerKindTags['buy'], offerGridTitles['buy']);
      expect(offerKindTags['sell'], offerGridTitles['sell']);
      expect(shopKindLabels['selling'], startsWith('Sell'));
      expect(shopKindLabels['buying'], startsWith('Buy'));
    });

    test('should name every offer kind from the shop side', () {
      expect(offerKindLabels['buy'], 'Sells (player pays money)');
      expect(offerKindLabels['sell'], 'Buys (shop pays money)');
      expect(offerKindNouns['buy'], 'item to sell');
      expect(offerKindNouns['sell'], 'item to buy');
      for (final Map<String, String> words in <Map<String, String>>[offerKindLabels, offerKindTags, offerGridTitles, shopModeLabels]) {
        for (final String word in words.values) {
          expect(word, isNot(matches(RegExp(r'^(Buy|Sell|Buying|Selling)\b'))), reason: 'player-side word: $word');
        }
      }
    });

    test('should label the tabs players switch between from the shop side', () {
      expect(shopModeLabels, <String, String>{'buy': 'Sells', 'sell': 'Buys', 'trade': 'Trades'});
    });
  });
}
