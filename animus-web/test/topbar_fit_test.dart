// The top bar's switcher name: a long server name (a MOTD) is cut with an
// ellipsis, and each measuring pass that still sticks out cuts more.
import 'package:animus_editor/ui/name_fit.dart';
import 'package:test/test.dart';

void main() {
  const String motd = 'Dobrodosli na strezniku nekega zelo dolgega imena, ki se ne konca';

  test('a name that fits is whole and a long one ends in an ellipsis', () {
    expect(shortenName('Survival', 40), 'Survival');
    final String cut = shortenName(motd, 20);
    expect(cut.runes.length, 20);
    expect(cut.endsWith('…'), isTrue);
    expect(shortenName('${'x' * 10} ${'y' * 30}', 12), '${'x' * 10}…');
  });

  test('a name is cut by characters, never inside a surrogate pair', () {
    final String flags = '\u{1F1F8}\u{1F1EE}' * 30;
    expect(shortenName(flags, 9).runes.length, lessThanOrEqualTo(9));
  });

  test('each pass cuts by the overflow, at least three characters, down to the floor', () {
    expect(shorterBudget(40, 70), 30);
    expect(shorterBudget(40, 2), 37);
    expect(shorterBudget(10, 500), contextNameFloor);
    expect(shorterBudget(contextNameFloor, 50), isNull);
    int? budget = contextNameLimit;
    int passes = 0;
    while (budget != null && budget > contextNameFloor) {
      budget = shorterBudget(budget, 120);
      passes++;
    }
    expect(passes, lessThanOrEqualTo(3));
  });
}
