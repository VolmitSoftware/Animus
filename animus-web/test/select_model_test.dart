import 'package:animus_editor/logic/select/select_model.dart';
import 'package:test/test.dart';

const List<SelectOption> fruit = <SelectOption>[
  SelectOption(value: 'apple', label: 'Apple'),
  SelectOption(value: 'apricot', label: 'Apricot', description: 'stone fruit'),
  SelectOption(value: 'banana', label: 'Banana', disabled: true),
  SelectOption(value: 'blueberry', label: 'Blueberry', keywords: <String>['berry']),
  SelectOption(value: 'cherry', label: 'Cherry'),
];

SelectStep press(SelectState state, String key, {String? value, int actions = 0, bool inSearch = false, int now = 0, bool alt = false}) =>
    selectKey(state, SelectRows(filterOptions(fruit, state.query), actions), key, value: value, inSearch: inSearch, now: now, alt: alt);

void main() {
  group('filterOptions', () {
    test('matches label, value, description and keywords, ignoring case', () {
      expect(filterOptions(fruit, 'AP').map((SelectOption option) => option.value), <String>['apple', 'apricot']);
      expect(filterOptions(fruit, 'stone').single.value, 'apricot');
      expect(filterOptions(fruit, 'berry').single.value, 'blueberry');
    });

    test('needs every word', () {
      expect(filterOptions(fruit, 'stone apricot').single.value, 'apricot');
      expect(filterOptions(fruit, 'stone cherry'), isEmpty);
    });

    test('an empty query keeps every option', () {
      expect(filterOptions(fruit, '   '), fruit);
    });

    test('keeps groups together in first-seen order, loose options first', () {
      const List<SelectOption> mixed = <SelectOption>[
        SelectOption(value: 'a', label: 'a', group: 'Two'),
        SelectOption(value: 'b', label: 'b', group: 'One'),
        SelectOption(value: 'c', label: 'c'),
        SelectOption(value: 'd', label: 'd', group: 'Two'),
      ];
      expect(filterOptions(mixed, '').map((SelectOption option) => option.value), <String>['c', 'a', 'd', 'b']);
    });

    test('shows the search only past the threshold', () {
      expect(searchable(searchThreshold), isFalse);
      expect(searchable(searchThreshold + 1), isTrue);
    });
  });

  group('selectKey when closed', () {
    test('ArrowDown, Enter and Space open on the current value', () {
      for (final String key in <String>['ArrowDown', 'Enter', ' ']) {
        final SelectStep step = press(const SelectState(), key, value: 'cherry');
        expect(step.state.open, isTrue, reason: key);
        expect(step.state.active, 4, reason: key);
      }
    });

    test('ArrowDown with no value opens on the first enabled option', () {
      expect(press(const SelectState(), 'ArrowDown').state.active, 0);
    });

    test('ArrowUp with no value opens on the last option', () {
      expect(press(const SelectState(), 'ArrowUp').state.active, 4);
    });

    test('Home and End open on the first and last option', () {
      expect(press(const SelectState(), 'Home').state.active, 0);
      expect(press(const SelectState(), 'End').state.active, 4);
    });

    test('a letter opens on the next option starting with it', () {
      final SelectStep step = press(const SelectState(), 'c', now: 1000);
      expect(step.state.open, isTrue);
      expect(step.state.active, 4);
    });

    test('other keys are left to the page', () {
      expect(press(const SelectState(), 'Tab').handled, isFalse);
      expect(press(const SelectState(), 'Escape').handled, isFalse);
    });
  });

  group('selectKey when open', () {
    const SelectState open = SelectState(open: true, active: 1);

    test('arrows move and skip disabled options without wrapping', () {
      expect(press(open, 'ArrowDown').state.active, 3, reason: 'banana is disabled');
      expect(press(const SelectState(open: true, active: 4), 'ArrowDown').state.active, 4);
      expect(press(const SelectState(open: true, active: 3), 'ArrowUp').state.active, 1);
      expect(press(const SelectState(open: true, active: 0), 'ArrowUp').state.active, 0);
    });

    test('Home and End go to the ends, footer actions included', () {
      expect(press(open, 'End', actions: 2).state.active, 6);
      expect(press(open, 'Home', actions: 2).state.active, 0);
    });

    test('Page Down and Page Up move ten rows and stop at the ends', () {
      expect(press(const SelectState(open: true, active: 0), 'PageDown').state.active, 4);
      expect(press(const SelectState(open: true, active: 4), 'PageUp').state.active, 0);
    });

    test('Enter and Space commit the active option and close', () {
      for (final String key in <String>['Enter', ' ']) {
        final SelectStep step = press(open, key);
        expect(step.effect, SelectEffect.commit, reason: key);
        expect(step.state.open, isFalse, reason: key);
      }
    });

    test('Enter on a footer row runs the action', () {
      expect(press(const SelectState(open: true, active: 5), 'Enter', actions: 2).effect, SelectEffect.action);
    });

    test('Enter on nothing active keeps the list open', () {
      final SelectStep step = press(const SelectState(open: true), 'Enter');
      expect(step.effect, SelectEffect.none);
      expect(step.state.open, isTrue);
    });

    test('Escape closes and returns focus; Tab closes and lets focus move', () {
      expect(press(open, 'Escape').effect, SelectEffect.close);
      final SelectStep tab = press(open, 'Tab');
      expect(tab.effect, SelectEffect.dismiss);
      expect(tab.handled, isFalse);
      expect(tab.state.open, isFalse);
    });

    test('Alt+ArrowUp commits, as on a native select', () {
      expect(press(open, 'ArrowUp', alt: true).effect, SelectEffect.commit);
    });

    test('type-ahead builds a prefix within the time window', () {
      final SelectStep b = press(const SelectState(open: true, active: 0), 'b', now: 1000);
      expect(b.state.active, 3, reason: 'banana is disabled, blueberry is next');
      final SelectStep bl = press(b.state, 'l', now: 1200);
      expect(bl.state.typed, 'bl');
      expect(bl.state.active, 3);
      final SelectStep late = press(bl.state, 'a', now: 5000);
      expect(late.state.typed, 'a');
      expect(late.state.active, 0);
    });

    test('repeating one letter cycles through its options', () {
      final SelectStep first = press(const SelectState(open: true, active: 0), 'a', now: 1000);
      expect(first.state.active, 1);
      final SelectStep second = press(first.state, 'a', now: 1100);
      expect(second.state.active, 0);
    });

    test('Space inside a type-ahead word types rather than commits', () {
      final SelectStep b = press(const SelectState(open: true, active: 0), 'b', now: 1000);
      expect(press(b.state, ' ', now: 1100).effect, SelectEffect.none);
    });

    test('in the search field, letters, Space, Home and End stay with the text', () {
      for (final String key in <String>['a', ' ', 'Home', 'End']) {
        expect(press(open, key, inSearch: true).handled, isFalse, reason: key);
      }
      expect(press(open, 'ArrowDown', inSearch: true).state.active, 3);
    });
  });

  group('searchChanged', () {
    test('moves to the first match', () {
      final SelectState next = searchChanged(const SelectState(open: true, active: 0), fruit, 'cher', 0);
      expect(next.query, 'cher');
      expect(next.active, 0);
      expect(filterOptions(fruit, next.query)[next.active].value, 'cherry');
    });

    test('moves to the first footer row when nothing matches', () {
      final SelectState next = searchChanged(const SelectState(open: true, active: 2), fruit, 'kiwi', 2);
      expect(next.active, 0);
      expect(SelectRows(filterOptions(fruit, next.query), 2).enabled(next.active), isTrue);
    });
  });
}
