import 'package:animus_editor/logic/npc/click_answers.dart';
import 'package:test/test.dart';

void main() {
  Map<String, Object?> behavior(List<Map<String, Object?>> triggers) => <String, Object?>{'triggers': triggers};
  Map<String, Object?> click([String? action, String? consume]) => <String, Object?>{'id': 'click', 'event': 'interaction', 'action': ?action, 'consume': ?consume};

  final Map<String, Map<String, Object?>> behaviors = <String, Map<String, Object?>>{
    'town:greeter': behavior(<Map<String, Object?>>[click()]),
    'town:patrol': behavior(<Map<String, Object?>>[click('any'), <String, Object?>{'id': 'near', 'event': 'proximity'}]),
    'town:boxer': behavior(<Map<String, Object?>>[click('attack')]),
    'town:gate': behavior(<Map<String, Object?>>[click('interact', 'true')]),
    'town:waves': behavior(<Map<String, Object?>>[<String, Object?>{'id': 'near', 'event': 'proximity'}]),
  };
  String nameOf(String id) => <String, String>{'town:greeter': 'Greeter', 'town:patrol': 'Patrol', 'town:boxer': 'Boxer', 'town:gate': 'Gate', 'town:waves': 'Waves'}[id] ?? id;

  ClickAnswers answers(Map<String, Object?> npc, NpcClick which, {Set<String> quests = const <String>{}}) =>
      clickAnswers(npc, which, behaviors: behaviors, behaviorName: nameOf, shopName: (String id) => 'Bakery', questNames: quests);

  test('should say nothing when one thing answers a click', () {
    final ClickAnswers one = answers(<String, Object?>{'behaviors': <String>['town:greeter']}, NpcClick.right);
    expect(one.answering, <String>['Greeter']);
    expect(one.line, isNull);
  });

  test('should list every behavior and the shop that answer a right-click, behaviors first', () {
    final Map<String, Object?> npc = <String, Object?>{
      'behaviors': <String>['town:greeter', 'town:patrol', 'town:boxer', 'town:waves'],
      'modules': <String, Object?>{'animus:shop': <String, Object?>{'shop': 'town:bakery'}},
    };
    final ClickAnswers right = answers(npc, NpcClick.right);
    expect(right.answering, <String>['Greeter', 'Patrol', 'Bakery']);
    expect(right.line, 'Right-click starts Greeter and Patrol, then opens Bakery.');
    final ClickAnswers left = answers(npc, NpcClick.left);
    expect(left.line, 'Left-click starts Patrol and Boxer.');
  });

  test('should name the opener that wins and the ones that never get the click', () {
    final Map<String, Object?> npc = <String, Object?>{
      'modules': <String, Object?>{
        'animus:shop': <String, Object?>{'shop': 'town:bakery', 'openOn': 'both'},
        'animus:gui': <String, Object?>{'open': <String, Object?>{'kind': 'menu', 'id': 'town:menu'}},
        'animus:riding': <String, Object?>{'enabled': true},
        'animus:seat': <String, Object?>{'click': 'right'},
      },
    };
    final ClickAnswers right = answers(npc, NpcClick.right);
    expect(right.answering, <String>['Bakery', 'the GUI', 'riding', 'the seat']);
    expect(right.line, 'Right-click opens Bakery. The GUI, riding and the seat never get it.');
    expect(right.conflict, isTrue);
  });

  test('should put hiring and quests before the shop, as the server asks them', () {
    final Map<String, Object?> npc = <String, Object?>{
      'modules': <String, Object?>{
        'animus:hire': <String, Object?>{'price': 10},
        'animus:shop': <String, Object?>{'shop': 'town:bakery'},
      },
    };
    expect(answers(npc, NpcClick.right).line, 'Right-click opens hiring. Bakery never gets it.');
    final ClickAnswers quest = answers(<String, Object?>{'modules': <String, Object?>{'animus:shop': <String, Object?>{'shop': 'town:bakery'}}}, NpcClick.right, quests: <String>{'Lost cat'});
    expect(quest.line, 'Right-click opens Lost cat when it has something for the player, else Bakery.');
    expect(quest.conflict, isFalse, reason: 'a quest gives the click back when it has nothing to offer');
  });

  test('should say a consuming behavior can keep the click from the rest', () {
    final Map<String, Object?> npc = <String, Object?>{
      'behaviors': <String>['town:gate'],
      'modules': <String, Object?>{'animus:shop': <String, Object?>{'shop': 'town:bakery'}},
    };
    expect(answers(npc, NpcClick.right).line, 'Right-click starts Gate, then opens Bakery. Gate keeps the click when its consume check holds.');
  });

  test('should leave a seat on sneak right-click out of a plain right-click', () {
    final Map<String, Object?> npc = <String, Object?>{
      'behaviors': <String>['town:greeter'],
      'modules': <String, Object?>{'animus:seat': <String, Object?>{'click': 'sneak-right'}},
    };
    expect(answers(npc, NpcClick.right).answering, <String>['Greeter']);
  });

  test('should skip a turned off module and riding without its switch', () {
    final Map<String, Object?> npc = <String, Object?>{
      'behaviors': <String>['town:greeter'],
      'modules': <String, Object?>{
        'animus:hire': <String, Object?>{'enabled': false},
        'animus:riding': <String, Object?>{'speed': 2},
        'animus:seat': <String, Object?>{'enabled': false},
      },
    };
    expect(answers(npc, NpcClick.right).answering, <String>['Greeter']);
  });

  test('should name a behavior this workspace lacks by its id and count it', () {
    final ClickAnswers right = answers(<String, Object?>{'behaviors': <String>['town:greeter', 'other:unknown']}, NpcClick.right);
    expect(right.answering, <String>['Greeter']);
  });

  test('should count a quest that gives, takes in or talks at the NPC', () {
    final Map<String, Object?> quest = <String, Object?>{
      'givers': <String>['baker'],
      'turnIn': 'mayor',
      'stages': <Object?>[
        <String, Object?>{'objectives': <Object?>[<String, Object?>{'id': 'ask', 'type': 'talk', 'npc': 'guard'}]},
      ],
    };
    expect(questAsks(quest, 'baker'), isTrue);
    expect(questAsks(quest, 'mayor'), isTrue);
    expect(questAsks(quest, 'guard'), isTrue);
    expect(questAsks(quest, 'smith'), isFalse);
  });
}
