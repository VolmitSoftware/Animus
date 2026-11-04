import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('modules_audit',Path(__file__).with_name('audit-modules.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


def valid_report():
    return {'schemaVersion':1,'runner':'ServerMultiplexor','scope':m.SCOPE,'status':'pass',
            'checks':[{'id':x,'status':'passed',**({'disableMillis':3} if n==10 else {})} for n,x in enumerate(m.CHECKS)],
            'phases':[{'phase':x} for x in m.PHASES], 'stops':[{'phase':x,'serverPortClosed':True} for x in m.PHASES],
            'serverPortClosed':True,'remainingDefinitions':[],'remainingCheckpoints':[],'logFailures':[]}


class ModuleAuditTest(unittest.TestCase):
    def test_complete_structure(self):m.structure(valid_report())
    def test_missing_reordered_or_failed_checks(self):
        for mutate in [lambda r:r['checks'].pop(),lambda r:r['checks'].reverse(),lambda r:r['checks'][3].update(status='failed')]:
            r=valid_report();mutate(r)
            with self.assertRaises(m.base.AuditError):m.structure(r)
    def test_incomplete_restart_is_not_success(self):
        for key in ['phases','stops']:
            r=valid_report();r[key].pop()
            with self.assertRaises(m.base.AuditError):m.structure(r)
    def test_emergency_cleanup_or_still_open_port_rejected(self):
        for update in [{'offlineCleanup':['npcs/qa/test.json']},{'serverPortClosed':False},{'runningPhase':'prepare'}, {'remainingCheckpoints':['orphan']}]:
            r=valid_report();r.update(update)
            with self.assertRaises(m.base.AuditError):m.structure(r)
    def test_disable_bound_type_and_value(self):
        for value in [True,-1,2000,1.5,None]:
            r=valid_report();r['checks'][10]['disableMillis']=value
            with self.assertRaises(m.base.AuditError):m.structure(r)
    def test_latest_attempt_never_falls_back_to_pass(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);old=p/'modules-1-9';old.mkdir();(old/'result.json').write_text(json.dumps(valid_report()))
            latest=p/'modules-2-1';latest.mkdir()
            attempts,selected=m.latest_attempt(p);self.assertEqual(selected,latest/'result.json');self.assertFalse(selected.exists())
            failed=valid_report();failed['status']='failed';selected.write_text(json.dumps(failed))
            with self.assertRaises(m.base.AuditError):m.structure(m.read(m.latest_attempt(p)[1]))
    def test_exact_round_names_and_no_cross_round_fallback(self):
        self.assertEqual(m.base.target_name('paper','26.3',10),'animus-r10-paper-26-3')
        self.assertEqual(m.base.target_name('folia','26.2',10,'import'),'animus-r10-import-folia-26-2')
        self.assertEqual(m.base.target_name('spigot','26.1.2',kind='modules'),'animus-modules-spigot-26-1-2')
        for r in [0,-1,True,'10',1000001]:
            with self.assertRaises(m.base.AuditError):m.base.target_name('paper','26.3',r)
        with self.assertRaises(m.base.AuditError):m.base.target_name('../paper','26.3',10)
    def test_reviewed_source_hash_must_match_frozen_and_archive_and_current(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);repo=p/'repo';folder=p/'attempt';name='qa/test.py';(repo/'qa').mkdir(parents=True);(folder/'sources/qa').mkdir(parents=True)
            (repo/name).write_text('original');(folder/'sources'/name).write_text('original')
            audit=m.ModuleAuditor(p,repo,p);audit.frozen={name:m.digest(repo/name)};r={'sources':dict(audit.frozen)}
            audit.sources(folder,r)
            (folder/'sources'/name).write_text('changed')
            with self.assertRaises(m.base.AuditError):audit.sources(folder,r)
            r['sources'][name]='0'*64
            with self.assertRaises(m.base.AuditError):audit.sources(folder,r)
    def check_logs(self,text,filename='prepare-server.log'):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);(p/filename).write_text(text+'\n');m.ModuleAuditor(p,p,p).module_logs(p)
    def test_only_exact_expected_info_failures_allowed(self):
        for suffix in ['qabroken [qabroken.jar] | FAILED | enable-failed: IllegalStateException','qanamespace [qanamespace.jar] | FAILED | enable-failed: IllegalArgumentException']:
            self.check_logs('[12:00:00] [Server thread/INFO]: [] '+suffix+'\x1b[m')
    def test_changed_module_exception_level_or_stack_is_rejected(self):
        row='[12:00:00] [Server thread/INFO]: [] qabroken [qabroken.jar] | FAILED | enable-failed: IllegalStateException'
        for bad in [row.replace('qabroken [','qabase ['),row.replace('IllegalStateException','IllegalArgumentException'),row.replace('IllegalStateException','NoClassDefFoundError'),row.replace('/INFO','/ERROR'),row+'\njava.lang.IllegalStateException: surprise',row+'\n\tat fixture.Handler.run(Handler.java:1)']:
            with self.assertRaises(m.base.AuditError):self.check_logs(bad)
        with self.assertRaises(m.base.AuditError):self.check_logs(row,'disabled-start-server.log')
    def test_contention_requires_identical_immediate_successful_retry(self):
        command=['gameplay','prepare','test'];failed={'command':command,'exitCode':1,'stdout':'','stderr':'[ERROR] Bad state: Another network command is running. Retry after it finishes.\n'}
        ok={'command':command,'exitCode':0,'stdout':'ok','stderr':''}
        self.assertEqual(len(m.base.managed_commands([failed,ok])),1)
        for rows in [[failed],[failed,{**ok,'command':['unrelated']}],[{**failed,'stderr':'java.lang.RuntimeException\n'},ok],[failed,{**failed,'stderr':'other error'},ok]]:
            with self.assertRaises(m.base.AuditError):m.base.managed_commands(rows)

if __name__=='__main__':unittest.main()
