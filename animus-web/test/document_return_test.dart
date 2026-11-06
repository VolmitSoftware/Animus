import 'package:animus_editor/components/select/picker_scope.dart';
import 'package:animus_editor/logic/select/select_model.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/state/document_return.dart';
import 'package:animus_editor/state/workspace_route.dart';
import 'package:test/test.dart';

void main() {
  const DocumentRoute npc = DocumentRoute('ws', DocumentKind.npc, 'town/baker');

  group('DocumentReturn', () {
    test('a trip goes to the prefilled create step and back with the new id', () {
      final DocumentReturn trip = DocumentReturn();
      final NewDocumentRoute create = trip.begin(workspaceId: 'ws', origin: npc, field: 'Rig', kind: DocumentKind.rig, name: ' Golem ', folder: 'town');
      expect(create, const NewDocumentRoute.prefilled('ws', DocumentKind.rig, folder: 'town', name: 'Golem'));
      expect(trip.returnsFor('ws', DocumentKind.rig), isTrue);
      expect(trip.complete('ws', DocumentKind.rig, 'town:golem'), npc);
      expect(trip.returnsFor('ws', DocumentKind.rig), isFalse);
      expect(trip.claim(npc, 'Rig', DocumentKind.rig), 'town:golem');
      expect(trip.claim(npc, 'Rig', DocumentKind.rig), isNull, reason: 'claimed once');
    });

    test('only the dropdown that asked takes the id', () {
      final DocumentReturn trip = DocumentReturn();
      trip.begin(workspaceId: 'ws', origin: npc, field: 'Rig', kind: DocumentKind.rig);
      trip.complete('ws', DocumentKind.rig, 'town:golem');
      expect(trip.claim(npc, 'Other', DocumentKind.rig), isNull);
      expect(trip.claim(const DocumentRoute('ws', DocumentKind.npc, 'town/smith'), 'Rig', DocumentKind.rig), isNull);
      expect(trip.claim(npc, 'Rig', DocumentKind.skin), isNull);
      expect(trip.claim(npc, 'Rig', DocumentKind.rig), 'town:golem');
    });

    test('creating another kind or in another workspace opens it as usual', () {
      final DocumentReturn trip = DocumentReturn();
      trip.begin(workspaceId: 'ws', origin: npc, field: 'Rig', kind: DocumentKind.rig);
      expect(trip.complete('ws', DocumentKind.route, 'patrol'), isNull);
      expect(trip.complete('other', DocumentKind.rig, 'town:golem'), isNull);
      expect(trip.complete('ws', DocumentKind.rig, 'town:golem'), npc);
    });

    test('leaving the create step forgets the trip; leaving another page does not', () {
      final DocumentReturn trip = DocumentReturn();
      final NewDocumentRoute create = trip.begin(workspaceId: 'ws', origin: npc, field: 'Rig', kind: DocumentKind.rig, name: 'golem');
      trip.abandon(const NewDocumentRoute('ws', DocumentKind.animation));
      expect(trip.returnsFor('ws', DocumentKind.rig), isTrue);
      trip.abandon(create);
      expect(trip.returnsFor('ws', DocumentKind.rig), isFalse);
      expect(trip.complete('ws', DocumentKind.rig, 'local:golem'), isNull);
    });
  });

  group('folderFor', () {
    test('a namespaced document from an NPC goes in the NPC folder\'s first part', () {
      expect(folderFor(DocumentKind.npc, 'town/market/baker', DocumentKind.rig), 'town');
      expect(folderFor(DocumentKind.npc, 'baker', DocumentKind.rig), isNull);
    });

    test('a namespaced document keeps the namespace', () {
      expect(folderFor(DocumentKind.behavior, 'town:welcome', DocumentKind.shop), 'town');
      expect(folderFor(DocumentKind.npc, 'town/baker', DocumentKind.npc), 'town');
    });

    test('kinds without folders get none', () {
      expect(folderFor(DocumentKind.npc, 'town/baker', DocumentKind.route), isNull);
      expect(folderFor(DocumentKind.route, 'patrol', DocumentKind.rig), isNull);
    });

    test('an animation made from a rig goes under that rig', () {
      expect(folderFor(DocumentKind.rig, 'town:golem', DocumentKind.animation), 'town:golem');
      expect(folderFor(DocumentKind.animation, 'town:golem/walk', DocumentKind.animation), 'town:golem');
      expect(folderFor(DocumentKind.npc, 'town/baker', DocumentKind.animation), isNull);
    });
  });

  group('routes', () {
    test('a prefilled create step round-trips through the hash', () {
      const NewDocumentRoute route = NewDocumentRoute.prefilled('ws 1', DocumentKind.rig, folder: 'town', name: 'Big golem');
      expect(route.format(), '#/workspace/ws%201/new/rig?folder=town&name=Big%20golem');
      expect(EditorRoute.parse(route.format()), route);
      expect(EditorRoute.parse('#/workspace/ws/new/npc?folder=town/market'), const NewDocumentRoute.inFolder('ws', DocumentKind.npc, 'town/market'));
    });

    test('a kind list round-trips and Manage picks the right list', () {
      expect(EditorRoute.parse(const KindListRoute('ws', DocumentKind.textAnimation).format()), const KindListRoute('ws', DocumentKind.textAnimation));
      expect(listRouteFor('ws', DocumentKind.rig), const KindListRoute('ws', DocumentKind.rig));
      expect(listRouteFor('ws', DocumentKind.npc), const NpcListRoute('ws'));
      expect(listRouteFor('ws', DocumentKind.recording), const RecordingListRoute('ws'));
    });
  });

  group('footer rows', () {
    test('name the kind', () {
      expect(referenceActions(DocumentKind.rig).map((SelectAction action) => action.label), <String>['+ New rig', 'Manage rigs']);
      expect(referenceActions(DocumentKind.inventory).first.label, '+ New chest GUI');
      expect(referenceActions(DocumentKind.textAnimation).first.label, '+ New text animation');
      expect(referenceActions(DocumentKind.behavior).first.label, '+ New behavior');
    });

    test('recordings say to record in game instead of New', () {
      expect(referenceActions(DocumentKind.recording).first.id, recordInGameActionId);
      expect(referenceActions(DocumentKind.recording).first.hint, contains('/animus record start'));
      expect(referenceActions(DocumentKind.recording).map((SelectAction action) => action.id), isNot(contains(newActionId)));
    });
  });
}
