"""Negative checks for evidence parsing and exact fixture-file ownership."""
import copy
import json
from pathlib import Path
import runpy
import tempfile
import unittest

WRAPPER=runpy.run_path(str(Path(__file__).resolve().parents[1]/'multiplexor/behaviors.py'))
Scenario=WRAPPER['Scenario']

class HarnessTests(unittest.TestCase):
    def basic(self):
        tags=['start','bound','true','false','loop','loop','loop','branch-a','branch-b','after']
        events=[]
        for tag in tags:
            args={'tag':tag}
            if tag=='bound':args.update(longValue=9223372036854775807,decimalValue=1.25,booleanValue=True,stringValue='Garde Étoile')
            if tag in ('true','false'):events.append({'npc':'fixture','kind':'condition','arguments':{'result':tag=='true'}})
            events.append({'npc':'fixture','kind':'record','arguments':args})
        for i,event in enumerate(events):event['sequence']=i+1
        return {'dropped':0,'pending':[],'events':events}
    def test_real_typed_sequence_and_both_parallel_orders(self):
        source=self.basic();WRAPPER['basic_events'](source,'fixture')
        source['events'][9]['arguments'],source['events'][10]['arguments']=source['events'][10]['arguments'],source['events'][9]['arguments']
        WRAPPER['basic_events'](source,'fixture')
    def test_integer_is_not_a_boolean(self):
        source=self.basic();source['events'][1]['arguments']['booleanValue']=1
        with self.assertRaises(AssertionError):WRAPPER['basic_events'](source,'fixture')
    def test_record_string_coercion_and_cross_viewer_rejected(self):
        for mutation in ('long','npc','join','drop','condition'):
            source=self.basic()
            if mutation=='long':source['events'][1]['arguments']['longValue']='9223372036854775807'
            elif mutation=='npc':source['events'][0]['npc']='other'
            elif mutation=='join':source['events'][-1]['arguments']['tag']='branch-a'
            elif mutation=='drop':source['dropped']=1
            else:source['events'][2]['arguments']['result']=False
            with self.subTest(mutation=mutation),self.assertRaises(AssertionError):WRAPPER['basic_events'](source,'fixture')
    def test_ansi_json_and_missing_status_are_not_coerced(self):
        self.assertEqual({'x':False},WRAPPER['json_receipt']('\x1b[0mQA {"x":false}\x1b[m','QA '))
        self.assertEqual(4,WRAPPER['counter']('Status[callsInFlight=4, retiredCalls=4]','callsInFlight'))
        with self.assertRaises(AssertionError):WRAPPER['counter']('Status[active=0]','callsInFlight')
        with self.assertRaises(json.JSONDecodeError):WRAPPER['json_receipt']('QA {"x":false …','QA ')
    def test_execution_exact_identity_and_numeric_fields(self):
        line='[INFO]: Behavior execution=00000000-0000-0000-0000-000000000001 | state=CANCELLED | npc=example | npc-uuid=00000000-0000-0000-0000-000000000002 | npc-revision=3 | behavior=qabehavior:hold | behavior-revision=2 | source-hash='+'a'*64+' | steps=4 | reason=source-changed'
        value=WRAPPER['execution_receipt'](line);self.assertEqual(3,value['npc-revision']);self.assertEqual('source-changed',value['reason'])
    def bare(self,directory):
        scenario=Scenario.__new__(Scenario);scenario.data=directory;scenario.ownership_ready=True;scenario.owned_files={};scenario.save=lambda:None
        return scenario
    def test_preexisting_data_is_never_claimed(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp).resolve();scenario=self.bare(root);source=root/'other.json';source.write_text('original')
            with self.assertRaises(AssertionError):scenario.owned_write(source,b'replacement')
            self.assertEqual('original',source.read_text());self.assertEqual({},scenario.owned_files)
            with self.assertRaises(AssertionError):scenario.owned_delete(source)
            self.assertTrue(source.exists())
    def test_owned_new_file_can_change_and_be_deleted(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp).resolve();scenario=self.bare(root);source=root/'owned.json'
            scenario.owned_write(source,b'first');scenario.owned_write(source,b'second')
            self.assertEqual(2,len(scenario.owned_files['owned.json']['hashes']));scenario.owned_delete(source);self.assertFalse(source.exists())
    def test_symlink_ancestor_refused_before_write_or_cleanup(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp).resolve();outside=root/'outside';outside.mkdir();data=root/'data';data.mkdir();(data/'alias').symlink_to(outside,target_is_directory=True)
            scenario=self.bare(data);source=data/'alias/owned.json'
            with self.assertRaises(AssertionError):scenario.owned_write(source,b'bad')
            self.assertFalse((outside/'owned.json').exists())
            scenario.owned_files['alias/owned.json']={'hashes':[]};(outside/'owned.json').write_text('untouched')
            with self.assertRaises(AssertionError):scenario.owned_delete(source)
            self.assertEqual('untouched',(outside/'owned.json').read_text())
    def test_rejected_or_nonadvancing_scan_is_not_accepted(self):
        for receipt in ('Behaviors reload: REJECTED | generation=4','Behaviors reload: APPLIED | generation=4'):
            scenario=Scenario.__new__(Scenario);scenario.catalog_generation=4;scenario.command=lambda *_:receipt
            with self.assertRaises(AssertionError):scenario.reload()
        scenario.command=lambda *_:'Behaviors reload: APPLIED | generation=5';scenario.reload();self.assertEqual(5,scenario.catalog_generation)

if __name__=='__main__':unittest.main()
