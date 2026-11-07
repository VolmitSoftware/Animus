#!/usr/bin/env python3
"""Strict immutable audit of nine explicit-build trusted-module/data workflows."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import sys
import zipfile
import multiplexor_workspace as mw

spec = importlib.util.spec_from_file_location('module_import_audit', Path(__file__).with_name('audit-import-applications.py'))
imports = importlib.util.module_from_spec(spec); spec.loader.exec_module(imports)
base = imports.base
require, read, digest = base.require, base.read_json, base.digest
SCOPE = 'trusted startup Java modules and dormant definition API; no gameplay or behavior interpreter'
PHASES = ['prepare', 'restart-and-disable', 'disabled-start']
CHECKS = ['startup-rejections-dependencies-and-parent-api-identity', 'failed-and-foreign-namespace-registrations-not-published',
          'typed-dispatch-linkage-privilege-and-json-argument-gates', 'api-token-conflicts-with-administrator-edit',
          'api-validated-dormant-apply-revision-and-unknown-config-preservation', 'invalid-identity-and-activation-drafts-preserve-exact-bytes',
          'binary-replacement-and-file-reload-do-not-reload-java', 'deadline-suspends-module-and-retains-unfinished-handler',
          'managed-stop-runs-reverse-module-and-resource-cleanup', 'restart-loads-new-binary-and-preserves-accepted-definition',
          'actual-plugin-disable-revokes-pending-work-and-releases-archives', 'disabled-setting-skips-all-binary-initialization-and-retains-config']
SOURCES = ['qa/multiplexor/modules.py', 'qa/multiplexor/managed-runtime.py', 'qa/modules/build-fixtures.py',
           'qa/modules/fixtures/src/example/animus/qa/ApiModule.java', 'examples/modules/reference/build.py',
           'examples/modules/reference/src/example/animus/reference/ReferenceModule.java', 'qa/modules/build-plugin-fixture.py',
           'qa/modules/fixture/plugin.yml', 'qa/modules/fixture/src/art/arcane/animus/qa/ModuleFixture.java']


def latest_attempt(directory):
    attempts = sorted((p for p in directory.glob('modules-*') if p.is_dir() and re.fullmatch(r'modules-[0-9]+-[0-9]+', p.name)),
                      key=lambda p: tuple(map(int, p.name.split('-')[1:])))
    return attempts, attempts[-1] / 'result.json' if attempts else None


def structure(report):
    require(report.get('schemaVersion') == 1 and report.get('runner') == 'ServerMultiplexor', 'Wrong runner/schema')
    require(report.get('scope') == SCOPE and report.get('status') == 'pass', 'Complete passing module scope required')
    require([c.get('id') for c in report.get('checks', [])] == CHECKS and all(c.get('status') == 'passed' for c in report['checks']), 'Exact 12 ordered passing module checks required')
    require([p.get('phase') for p in report.get('phases', [])] == PHASES and [p.get('phase') for p in report.get('stops', [])] == PHASES, 'Three complete ordered starts/stops required')
    require(report.get('serverPortClosed') is True and all(s.get('serverPortClosed') is True for s in report['stops']), 'Every owned stop must close its port')
    require(report.get('remainingDefinitions') == [] and report.get('remainingCheckpoints') == [] and report.get('logFailures') == [], 'Incomplete cleanup/log checks')
    require(not any(report.get(k) for k in ['error', 'commandCleanupError', 'offlineCleanup', 'artifactError', 'runningPhase', 'cleanupError']), 'Failure/emergency cleanup is recorded')
    millis = report['checks'][10].get('disableMillis')
    require(type(millis) is int and 0 <= millis < 2000, 'Actual disable deadline failed')


class ModuleAuditor(imports.ImportAuditor):
    def bind_sources(self, manifest_path, status):
        result = super().bind_sources(manifest_path, status)
        manifest = read(manifest_path)
        self.frozen = {name: manifest['Animus']['files'][name] for name in SOURCES}
        artifacts = [a for j in read(status)['runs'][0]['jobs'] if j['project'] == 'animus'
                     for a in j.get('artifacts', []) if a.get('role') == 'api']
        require(len(artifacts) == 1, 'Selected build must contain exactly one standalone API artifact')
        self.api = artifacts[0]; self.file(self.api['file'], self.api['sha256'], 'standalone-api-artifact')
        return result

    def fixtures(self, folder, data, report):
        for name in ['modules-build', 'reference-build', 'observer-build']: self.file(folder / (name + '.json'), kind='fixture-build')
        modules, reference, observer = [read(folder / (name + '.json')) for name in ['modules-build', 'reference-build', 'observer-build']]
        self.file(modules['apiJar'], self.api['sha256'], 'module-compile-api')
        self.file(reference['apiJar'], self.api['sha256'], 'reference-compile-api')
        require(modules['status'] == 'built' and modules['apiSha256'] == reference['apiSha256'] == report['apiSha256'] == self.api['sha256'], 'Module/public API binding mismatch')
        require(modules['builderSha256'] == self.frozen['qa/modules/build-fixtures.py'] and modules['apiFixtureSourceSha256'] == self.frozen[SOURCES[3]], 'Module fixture source mismatch')
        require(reference['javaRelease'] == 25 and reference['compileClasspath'] == [reference['apiJar']], 'External module must compile against API and JDK only')
        for path, expected in reference['sources'].items(): require(self.frozen['examples/modules/reference/' + path] == expected, 'Reference build source mismatch')
        self.file(reference['artifact'], reference['sha256'], 'original-reference-jar')
        require(observer['pluginSha256'] == self.artifact_hash and observer['sources'] == {k: self.frozen[k] for k in SOURCES[-3:]}, 'Observer is not bound to artifact/frozen sources')
        self.file(observer['spigotApi'], observer['spigotApiSha256'], 'observer-compile-api')
        expected = {m['file']: m['sha256'] for m in modules['modules']}
        ref_name = Path(reference['artifact']).name; expected[ref_name] = reference['sha256']
        require(len(modules['modules']) == 15 and len(expected) == 16 and report['initialModuleHashes'] == expected, 'Initial module fixture set mismatch')
        expected[ref_name] = modules['replacement']['sha256']
        require(report['finalModuleHashes'] == expected, 'Unexpected live binary replacement')
        directory = data / 'modules'
        require({p.name for p in directory.glob('*.jar')} == set(expected), 'Deployed module set differs')
        for name, h in expected.items():
            require(Path(name).name == name, 'Unsafe module filename'); self.file(directory / name, h, 'deployed-module')
            descriptor = modules['replacement']['manifest'] if name == ref_name else next(m['manifest'] for m in modules['modules'] if m['file'] == name)
            with zipfile.ZipFile(directory / name) as jar: require(json.loads(jar.read('animus-module.json')) == descriptor, 'Module descriptor differs from fixture receipt')
        self.file(data / 'modules/qa-missing-config.json', report['retainedConfigSha256'], 'preserved-missing-module-config')
        require(read(data / 'modules/qa-missing-config.json') == {'missing:module': {'retain': folder.name.removeprefix('modules-')}}, 'Missing module config changed')
        return modules, reference, observer

    def module_logs(self, folder):
        allowed = re.compile(r'^\[[0-9:]+\] \[(?:Server thread|Folia Region Scheduler Thread #[0-9]+)/INFO\]: \[\] (?:qabroken \[qabroken\.jar\] \| FAILED \| enable-failed: IllegalStateException|qanamespace \[qanamespace\.jar\] \| FAILED \| enable-failed: IllegalArgumentException)$')
        for path in folder.glob('*-server.log'):
            self.file(path, kind='server-log')
            for line in path.read_text().splitlines():
                normalized = re.sub(r'\x1b\[[0-9;]*m', '', line)
                if base.BAD_LOG.search(line) or re.search(r'\b\w+(?:Error|Exception)\b|^\s+at [\w.$]+\(', normalized):
                    require(path.name == 'prepare-server.log' and allowed.fullmatch(normalized), 'Unexpected module log diagnostic: ' + line)

    def receipts(self, folder, data, report, modules):
        logs = {}
        for phase, stopped in zip(report['phases'], report['stops']):
            path = folder / (phase['phase'] + '-server.log')
            require(Path(stopped['log']) == path and stopped['serverPortClosed'] is True, 'Phase log/stop mismatch')
            self.file(path, stopped['logSha256'], 'module-phase-log'); logs[phase['phase']] = path.read_text()
            require(phase['ready'] in logs[phase['phase']] and 'Done (' in phase['ready'], 'Startup receipt absent from phase log')
        self.module_logs(folder)
        require(Path(report['finalServerLog']) == folder / 'final-server.log', 'Final log escaped evidence')
        self.file(folder / 'final-server.log', report['finalServerLogSha256'], 'final-server-log')
        require((folder / 'final-server.log').read_bytes() == (folder / 'disabled-start-server.log').read_bytes(), 'Final server log differs from last phase')
        offsets = {p: 0 for p in PHASES}
        rows = report['commands']; require(rows, 'No console commands')
        for row in rows:
            phase = row['phase']; require(phase in PHASES, 'Unknown command phase')
            text = logs[phase]; output = row['output']; receipt = row['receipt']
            require(output and receipt and receipt in output, 'Receipt absent from command output')
            start = text.find(output, offsets[phase]); require(start >= 0, 'Command output absent/out of order in archived log')
            offsets[phase] = start + len(output)
        def find(phase, command):
            found = [r for r in rows if r['phase'] == phase and r['command'] == command]
            require(found, 'Missing command: ' + command); return found
        def action(key, phase='prepare', arguments='{}', statuses=None):
            found = find(phase, f'animus modules action {key} {report["npcId"]} {arguments}')
            values = []
            for row in found:
                match = re.search(r'Module ' + re.escape(key) + r': ([A-Z_]+)(?:; result (.*))?', re.sub(r'\x1b\[[0-9;]*m', '', row['receipt']))
                require(match is not None, 'Invalid module receipt')
                values.append((match[1], json.loads(match[2]) if match[2] else None))
            if statuses is not None: require([v[0] for v in values] == statuses, 'Module result status differs: ' + key)
            return values
        listing = find('prepare', 'animus modules list')[0]['output']
        require('Module list: 16;' in listing, 'Incomplete startup inventory')
        for item in modules['modules']:
            identity = item['manifest']['id']; prefix = '' if identity == 'qabundled' else identity + ' '
            require(prefix + '[' + item['file'] + '] | ' + item['expectedStartup'] + ' |' in listing, 'Wrong startup state: ' + identity)
            if identity in ('qabroken', 'qanamespace'):
                expected_failure = 'IllegalStateException' if identity == 'qabroken' else 'IllegalArgumentException'
                require(identity + ' [' + item['file'] + '] | FAILED | enable-failed: ' + expected_failure in listing, 'Synthetic startup failure reason changed')
            if item['expectedStartup'] in ('REJECTED', 'UNAVAILABLE'): require('Animus module QA initialized ' + identity not in logs['prepare'], 'Rejected module initialized')
        catalog = find('prepare', 'animus modules capabilities')[0]['output']
        require('Module capabilities: 13; built-in: 19;' in catalog and 'qabroken:partial' not in catalog and 'reference:stolen' not in catalog, 'Partial/foreign registry publication')
        require(action('reference:hello', statuses=['OK', 'OK']) == [('OK', {'message': 'Hello from a trusted external module'})] * 2, 'Binary reloaded before restart')
        require(action('reference:hello', 'restart-and-disable', statuses=['OK'])[0][1] == {'message': 'Replacement reference after restart'}, 'Replacement absent after restart')
        require(action('qalink:linked', statuses=['OK'])[0][1] == {'linked': True}, 'Dependency linkage missing')
        for value in ['false', 'true']:
            r = find('prepare', f'animus modules condition qaapi:{value} {report["npcId"]} {{}}')[0]
            require(f'Module qaapi:{value}: OK; result {value}' in r['receipt'], 'Typed condition mismatch')
        action('qaapi:privileged', statuses=['UNAVAILABLE']); action('reference:hello', arguments='[]', statuses=['INVALID']); action('qabroken:partial', statuses=['UNAVAILABLE'])
        require(action('qaapi:apply_cached', statuses=['OK'])[0][1]['status'] == 'CONFLICT', 'Optimistic conflict not observed')
        for key, expected in [('invalid','INVALID'),('identity','INVALID'),('activate','UNAVAILABLE')]:
            require(action('qaapi:' + key, statuses=['OK'])[0][1]['status'] == expected, 'Invalid draft was accepted')
        action('qawait:hold', statuses=['TIMED_OUT', 'UNAVAILABLE'])
        require('handlersOutstanding=1' in logs['prepare'] and 'timedOutHandlers=1' in logs['prepare'], 'Timed-out raw handler was not retained')
        require(logs['prepare'].index('disabled qalink') < logs['prepare'].index('disabled qabase') and 'disabled qawait' in logs['prepare'] and
                logs['prepare'].index('resource qaapi second') < logs['prepare'].index('resource qaapi first'), 'Reverse shutdown/resource order missing')
        for name, key in [('before-restart-npc.json', 'definition'), ('before-restart-checkpoint.json', 'checkpoint')]:
            self.file(folder / name, imports.text_hash(report['persistedBeforeRestart'][key]), 'restart-snapshot')
        content = report['persistedBeforeRestart']['definition']; document = json.loads(content)
        pointer = json.loads(report['persistedBeforeRestart']['checkpoint']); source_path = f'npcs/qa/{report["npcId"]}.json'
        require(document['id'] == report['npcId'] and document['uuid'] == report['npcUuid'] and document['revision'] == report['initialRevision'] + 2 == 4
                and document['name'] == 'Module Edited' and document['spawned'] is False and document['modules'] == {'missing:module': {'retained': True}}, 'Persisted accepted definition mismatch')
        require(pointer['schemaVersion'] == 1 and pointer['path'] == source_path and pointer['sourceHash'] == imports.text_hash(content), 'Checkpoint mismatch')
        obj = data / 'state/objects/npc-definitions' / (pointer['objectHash'] + '.json')
        self.file(obj, pointer['objectHash'], 'checkpoint-object'); require(read(obj) == document, 'Checkpoint payload differs')
        edited = action('qaapi:edit', statuses=['OK'])[0][1]; inspected = action('qaapi:inspect', 'restart-and-disable', statuses=['OK'])[0][1]
        require(edited == inspected and edited['revision'] == document['revision'] and edited['sourceSha256'] == imports.text_hash(content) and edited['uuid'] == report['npcUuid'], 'Restart/API token mismatch')
        cached = action('qaapi:cache', statuses=['OK'])[0][1]
        require(cached['revision'] == report['initialRevision'] and cached['uuid'] == report['npcUuid'], 'Cached stale token mismatch')
        disabled = find('restart-and-disable', 'moduleqa disable ' + report['npcUuid'])[0]['receipt']
        require('enabled=false admission-closed=true' in disabled and int(re.search(r'duration-ms=(\d+)', disabled)[1]) == report['checks'][10]['disableMillis'], 'Official disable receipt mismatch')
        require('Module QA post-disable action=UNAVAILABLE' in logs['restart-and-disable'] and
                'state=STOPPED admission-closed=true admitted=0 retained=0 released=true' in logs['restart-and-disable'], 'Late work/archive cleanup incomplete')
        require('Modules DISABLED | startup=0 | capabilities=0 | built-in=19' in logs['disabled-start'] and 'Module list: 0; page 1' in logs['disabled-start']
                and 'Animus module QA initialized ' not in logs['disabled-start'], 'Disabled setting initialized modules')
        # Reconstruct every actual persisted transition; invalid drafts cannot hide a mutation.
        journals = []
        for path in (data / 'backups').glob('*.json'):
            journal = self.journal(data, path.stem)
            if source_path in journal: journals.append(journal)
        require(len(journals) == 5, 'Unexpected definition mutations during module workflow')
        remaining = list(journals); current = None; transitions = []
        while remaining:
            choices = [j for j in remaining if j[source_path][0] == current]
            require(len(choices) == 1, 'Broken/ambiguous durable mutation chain')
            selected = choices[0]; remaining.remove(selected); current = selected[source_path][1]; transitions.append(current)
        require(current is None and transitions[-2] == content, 'Final deletion/restart chain mismatch')
        definitions = [json.loads(v) for v in transitions[:-1]]
        require([d['revision'] for d in definitions] == [1,2,3,4] and [d['name'] for d in definitions] == ['Module Before','Module Before','Module Concurrent','Module Edited'], 'API/admin mutation order differs')
        require(imports.text_hash(transitions[1]) == cached['sourceSha256'], 'Cached token not bound to actual bytes')
        return len(rows)

    def target(self, path, project, version):
        self.file(path, kind='module-attempt'); report = read(path); structure(report); folder = path.parent
        name = base.target_name(project, version, kind='modules')
        require(folder.parent.name == name, 'Module evidence target directory mismatch')
        require(report['pluginSha256'] == self.artifact_hash, 'Wrong module artifact')
        instance = self.deployed(report, project, version, name); data = instance / 'plugins/Animus'
        require(report['deployedPlugins'] == report['plugins'] and report['finalServerSha256'] == report['serverSha256'], 'Deployment changed')
        reference_path = mw.evidence_root(self.workspace) / base.target_name(project, version) / 'runtime.json'
        self.file(reference_path, kind='pinned-server-reference'); pinned = read(reference_path)
        require((report['serverBuild'],report['serverChannel'],report['serverSha256']) == (pinned['build'],pinned['channel'],pinned['serverSha256']), 'Exact server release mismatch')
        self.sources(folder, report); modules, reference, observer = self.fixtures(folder, data, report)
        require(report['plugins'] == sorted([{'file': self.artifact_name, 'sha256': self.artifact_hash}, {'file': 'AnimusModuleFixture.jar', 'sha256': observer['fixtureSha256']}], key=lambda p:p['file']), 'Unexpected installed plugin set')
        command_count = self.receipts(folder, data, report, modules)
        self.file(folder / 'multiplexor-commands.json', kind='runner-transcript'); commands = read(folder / 'multiplexor-commands.json')
        retries = base.managed_commands(commands)
        require(sum(c['command'] == ['runtime','start',name,'--no-console'] for c in commands) == 3, 'Missing managed starts')
        require(sum(c['command'] == ['runtime','stop',name,'--graceful'] for c in commands) == 5, 'Missing managed stops')
        require(any(c['command'] == ['projects','deploy',self.run_id,'--to',name,'--target','animus:bukkit','--json'] for c in commands), 'Missing selected build deployment')
        absent = self.absent(data, 'qa', report['npcId'])
        for relative in ['npcs','state/npc-definitions/last-valid/npcs']:
            root = data / relative; require(not root.is_symlink() and not list(root.rglob('*.json')), 'Unclean definition/checkpoint tree')
        self.file(data / 'config.toml', kind='final-plugin-config')
        require((data/'config.toml').read_text() == 'packetProofEnabled = false\n[npcs]\nenabled = false\n[modules]\nenabled = false\nmaximumInvocations = 8\ninvocationTimeoutMillis = 1500\ndeliveryBudgetMillis = 250\n', 'Final module/runtime setting differs')
        return {'project':project,'minecraftVersion':version,'report':str(path),'port':report['port'],'checks':12,'commands':command_count,
                'managedLockRetries':retries,'phases':PHASES,'serverBuild':report['serverBuild'],'serverChannel':report['serverChannel'],'serverSha256':report['serverSha256'],'absentNow':absent}


def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--workspace',type=Path,default=mw.default_workspace());p.add_argument('--repo',type=Path,default=Path(__file__).resolve().parents[2])
    for name in ['build-status','source-snapshot','output']:p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--artifact-sha256',required=True);p.add_argument('--instance-prefix',choices=['animus-modules'],required=True)
    a=p.parse_args();require(not base.lexists(a.output),'Choose a new immutable audit directory');a.output.mkdir(parents=True)
    audit=ModuleAuditor(a.workspace.resolve(),a.repo.resolve(),Path('/unused-native-codecs'))
    result={'schemaVersion':1,'scope':SCOPE,'instancePrefix':a.instance_prefix,'status':'failed','startedAt':datetime.now(timezone.utc).isoformat(),'targets':[],'attempts':[],'errors':[],'pending':[],
            'limitations':['Trusted Java modules and dormant definition API only; no native gameplay, behavior interpreter or Java sandbox claim.','Observed restart/shutdown and optimistic conflicts do not establish power-loss durability.']}
    caught=(base.AuditError,OSError,ValueError,KeyError,TypeError,IndexError,AttributeError,zipfile.BadZipFile)
    try:
        for source in [Path(__file__),Path(imports.__file__),Path(base.__file__)]:
            archive=a.output/'auditor-sources'/source.name;archive.parent.mkdir(exist_ok=True);shutil.copy2(source,archive);audit.file(archive,kind='archived-auditor')
        result['build']=audit.build(a.build_status.resolve(),a.artifact_sha256);result['sourceSnapshot']=audit.bind_sources(a.source_snapshot.resolve(),a.build_status.resolve())
        result['apiArtifact']=audit.api
        for project in ['paper','spigot','folia']:
            for version in base.VERSIONS:
                folder=mw.evidence_root(audit.workspace)/base.target_name(project,version,kind='modules');attempts,selected=latest_attempt(folder)
                for attempt in attempts:
                    f=attempt/'result.json';entry={'report':str(f),'status':'pending'}
                    if f.exists():audit.file(f,kind='preserved-module-attempt');entry.update({k:read(f).get(k) for k in ['status','buildRun','error']})
                    result['attempts'].append(entry)
                if selected is None or not selected.exists():result['pending'].append({'project':project,'version':version});continue
                try:result['targets'].append(audit.target(selected,project,version))
                except caught as error:result['errors'].append({'project':project,'version':version,'reason':str(error)})
        for path,record in audit.files.items():
            require(digest(path)==record['sha256'],'Evidence changed during audit: '+path)
            if 'linkTarget' in record:require(os.readlink(path)==record['linkTarget'] and str(Path(path).resolve())==record['resolvedPath'],'Server link changed')
            else:require(not Path(path).is_symlink(),'Evidence became symlink')
        for target in result['targets']:
            base.closed_port(target['port'])
            for path in target['absentNow']:require(not base.lexists(path),'Owned source reappeared')
        result['status']='failed' if result['errors'] else 'passed' if len(result['targets'])==9 else 'partial'
    except caught as error:result['errors'].append({'target':'matrix','reason':str(error)})
    result['files']=audit.files;result['completedAt']=datetime.now(timezone.utc).isoformat();result['counts']={'targetsVerified':len(result['targets']),'groupedModuleDataChecks':sum(t['checks'] for t in result['targets']),'failedAttemptsPreserved':sum(t['status']=='failed' for t in result['attempts'])}
    output=a.output/'audit.json'
    with output.open('x') as stream:json.dump(result,stream,indent=2);stream.write('\n');stream.flush();os.fsync(stream.fileno())
    for f in a.output.rglob('*'):os.chmod(f,0o555 if f.is_dir() else 0o444)
    os.chmod(a.output,0o555)
    print(json.dumps({'status':result['status'],'audit':str(output.resolve()),'sha256':digest(output),'counts':result['counts'],'errors':result['errors']}))
    return 0 if result['status']=='passed' else 2 if result['status']=='partial' else 1

if __name__=='__main__':sys.exit(main())
