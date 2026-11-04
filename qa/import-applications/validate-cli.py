#!/usr/bin/env python3
"""Exercise the installed converter with public synthetic data and archived receipts.

Each invocation is a real CLI process. No server is started; all output stays in
an ignored, unique .qa directory. Validation also checks the observed filesystem
tree to ensure the read-only command does not create or recover data.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
API = runpy.run_path(str(Path(__file__).with_name('assertions.py')))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--distribution', type=Path, default=ROOT/'converter/build/install/converter')
    args = parser.parse_args()
    distribution = args.distribution.resolve()
    launcher = distribution/'bin/converter'
    output = ROOT/'.qa/import-cli-validation'/f'{time.time_ns()}-{os.getpid()}'
    output.mkdir(parents=True)
    data = output/'data'
    sha = API['sha']
    inputs = [launcher, *sorted((distribution/'lib').glob('*.jar'))]
    assert launcher.is_file() and len(inputs) > 2
    report = {'schemaVersion': 1, 'scope': 'Installed converter CLI; public synthetic data; no server verification',
              'distribution': str(distribution), 'distributionHashes': {str(p.relative_to(distribution)): sha(p) for p in inputs},
              'commands': [], 'checks': [], 'status': 'running'}
    sources = [Path(__file__).resolve(), Path(__file__).with_name('assertions.py'),
               *sorted((Path(__file__).parent/'fixtures').glob('*.json'))]
    report['sources'] = {str(p.relative_to(ROOT)): sha(p) for p in sources}
    for source in sources:
        dest = output/'sources'/source.relative_to(ROOT)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, dest)
    env = {**os.environ, 'JAVA_HOME': '/opt/homebrew/opt/openjdk@25'}

    def save():
        (output/'validation.json').write_text(json.dumps(report, indent=2)+'\n')

    def cli(*arguments, expected=0, pattern=None):
        index = len(report['commands'])+1
        result = subprocess.run([str(launcher), *map(str, arguments)], text=True, capture_output=True, env=env, timeout=30)
        log = output/f'command-{index:03}.log'
        log.write_text(result.stdout+result.stderr)
        report['commands'].append({'arguments': list(map(str, arguments)), 'exitCode': result.returncode,
                                   'log': str(log), 'logSha256': sha(log)})
        save()
        assert result.returncode == expected, f'{arguments}: {result.stdout}{result.stderr}'
        text = result.stdout+result.stderr
        if pattern: assert re.search(pattern, text), f'Missing {pattern}: {text}'
        return text

    def passed(identifier):
        report['checks'].append({'id': identifier, 'status': 'passed'})
        save()

    def tree(directory):
        if not directory.exists(): return None
        return {str(p.relative_to(directory)): (sha(p), p.stat().st_mtime_ns) if p.is_file() else 'directory'
                for p in sorted(directory.rglob('*'))}

    def preview(kind):
        source = ROOT/'qa/import-applications/fixtures'/f'{kind}.json'
        text = cli('preview', source, data, 'qa_legacy=world', pattern=r'Preview [a-f0-9]{64} \(converter v2\)')
        identity = re.search(r'Preview ([a-f0-9]{64})', text)[1]
        summary = API['original_archive'](data, identity, source.read_bytes())
        assert summary['records'] == summary['valid'] == (3 if kind == 'baseline' else 4)
        assert summary['skins'] == 1
        return identity

    def stage(identity, mode='reject', label=None):
        text = cli('stage', data, identity, mode, *([label] if label else []), pattern=r'Application [a-f0-9]{64}')
        app = re.search(r'Application ([a-f0-9]{64})', text)[1]
        plan, _ = API['application'](data, identity, app)
        assert f'Mode: {mode}; records: {plan["records"]}; replacements: {plan["replacements"]}' in text
        return app, plan

    def apply(identity, app):
        text = cli('apply-plan', data, identity, app, pattern=r'Import transaction: (?:[a-f0-9-]{36}|already-applied)\. Definitions installed dormant\.')
        API['installed'](data, identity, app)
        return re.search(r'Import transaction: ([a-f0-9-]{36}|already-applied)', text)[1]

    def rollback(transaction):
        cli('rollback', data, transaction, pattern=r'Rollback transaction: [a-f0-9-]{36}')

    save()
    try:
        empty = output/'empty'
        empty.mkdir()
        before = tree(empty)
        cli('validate', empty, pattern='Valid NPC definitions: 0')
        assert tree(empty) == before
        missing = output/'missing'
        cli('validate', missing, expected=1)
        assert not missing.exists()
        cli('validate', empty, 'extra', expected=1, pattern='Incorrect argument count')
        assert tree(empty) == before
        passed('read-only-empty-missing-and-invalid-count-validation')

        baseline = preview('baseline')
        assert preview('baseline') == baseline
        passed('preview-counts-identical-identity-and-exact-source-archive')
        for arguments, pattern in [
            ([], 'Usage:'), (['preview', empty], 'requires input and output'),
            (['validate', data, 'extra'], 'Incorrect argument count'),
            (['apply', data], 'Incorrect argument count'),
            (['apply', data, baseline, 'extra'], 'Incorrect argument count'),
            (['apply-plan', data, baseline], 'Incorrect argument count'),
            (['apply-plan', data, baseline, 'a'*64, 'extra'], 'Incorrect argument count'),
            (['rollback', data], 'Incorrect argument count'),
            (['stage', data], 'stage <data-dir>'),
            (['stage', data, baseline, 'reject', 'qa', 'extra'], 'stage <data-dir>'),
            (['stage', data, baseline, 'unknown'], 'mode'),
            (['stage', data, baseline, 'duplicate'], 'label'),
            (['stage', data, baseline, 'duplicate', '../bad'], 'label'),
            (['stage', data, baseline, 'reject', 'qa'], 'label'),
            (['stage', data, '../bad'], 'import'),
        ]:
            cli(*arguments, expected=1, pattern='(?i)'+pattern)
            assert not API['snapshot'](data)
        passed('invalid-cli-counts-modes-labels-and-identities-reject')

        base_app, plan = stage(baseline)
        assert plan['records'] == 3 and plan['replacements'] == 0
        assert stage(baseline)[0] == base_app and not API['snapshot'](data)
        passed('stage-is-content-addressed-and-does-not-install')
        base_tx = apply(baseline, base_app)
        base_snapshot = API['snapshot'](data)
        assert apply(baseline, base_app) == 'already-applied'
        assert len(API['definitions'](data)) == 3
        passed('explicit-apply-dormant-checkpoints-and-exact-repeat')

        before = tree(data)
        cli('validate', data, pattern='Valid NPC definitions: 3')
        assert tree(data) == before
        record = data/'npcs/citizens/citizens-900001.json'
        original = record.read_bytes()
        invalid = json.loads(original); invalid['position']['x'] = 'bad'
        record.write_text(json.dumps(invalid, indent=2)+'\n')
        before = tree(data)
        cli('validate', data, expected=1, pattern=r'\$\.position\.x')
        assert tree(data) == before
        record.write_bytes(original)
        pending = data/'state/pending.json'
        pending.write_text('{"synthetic":"must not be recovered by validate"}\n')
        before = tree(data)
        cli('validate', data, expected=1, pattern='Pending transaction requires recovery')
        assert tree(data) == before
        pending.unlink()
        passed('read-only-valid-invalid-and-pending-validation-keeps-exact-tree')

        copy_app, _ = stage(baseline, 'duplicate', 'qa')
        copy_tx = apply(baseline, copy_app)
        assert len(API['duplicate'](data, baseline, copy_app, 'qa')) == 3
        assert apply(baseline, copy_app) == 'already-applied'
        with_copy = API['snapshot'](data)
        passed('duplicate-identities-resolved-references-and-opaque-preservation')

        updated = preview('updated')
        cli('stage', data, updated, expected=1, pattern='NPC collision:')
        API['assert_snapshot'](data, with_copy)
        stale, plan = stage(updated, 'replace')
        assert plan['records'] == 4 and plan['replacements'] == 3
        changed = json.loads(original); changed['name'] = 'CLI Concurrent'; changed['revision'] += 1
        record.write_text(json.dumps(changed, indent=2)+'\n')
        changed_snapshot = API['snapshot'](data)
        cli('apply-plan', data, updated, stale, expected=1, pattern='Application no longer matches current definitions|Conflict:')
        API['assert_snapshot'](data, changed_snapshot)
        assert not (data/'npcs/citizens/citizens-900004.json').exists()
        cli('apply-plan', data, baseline, base_app, expected=1, pattern='Already-applied application has changed or missing output')
        API['assert_snapshot'](data, changed_snapshot)
        passed('collision-stale-review-and-modified-repeat-reject-without-partial-write')

        replacement, plan = stage(updated, 'replace')
        assert replacement != stale and plan['replacements'] == 3
        prior = API['definitions'](data)
        replacement_tx = apply(updated, replacement)
        for path, definition in API['definitions'](data).items():
            API['checkpoint'](data, path)
            if path.startswith('npcs/citizens/'):
                assert definition['revision'] == (prior[path]['revision']+1 if path in prior else 1)
        assert len(API['definitions'](data)) == 7
        assert apply(updated, replacement) == 'already-applied'
        passed('reviewed-replacement-increments-revisions-and-installs-additions')
        rollback(replacement_tx)
        API['assert_snapshot'](data, changed_snapshot)
        reapplied = apply(updated, replacement)
        assert reapplied not in [replacement_tx, 'already-applied']
        rollback(reapplied)
        API['assert_snapshot'](data, changed_snapshot)
        passed('rollback-and-reapply-across-independent-cli-processes')

        record.write_bytes(original)
        API['assert_snapshot'](data, with_copy)
        rollback(copy_tx)
        API['assert_snapshot'](data, base_snapshot)
        rollback(base_tx)
        assert not API['snapshot'](data)
        legacy = cli('apply', data, baseline, pattern=r'Import transaction: [a-f0-9-]{36}')
        legacy_tx = re.search(r'Import transaction: ([a-f0-9-]{36})', legacy)[1]
        cli('apply', data, baseline, pattern='already-applied')
        rollback(legacy_tx)
        assert not API['snapshot'](data)
        cli('validate', data, pattern='Valid NPC definitions: 0')
        passed('independent-copy-base-and-legacy-rollbacks-leave-no-definitions-or-checkpoints')
        assert report['distributionHashes'] == {str(p.relative_to(distribution)): sha(p) for p in inputs}, 'Distribution changed during CLI validation'
        report['status'] = 'passed'
    except Exception as error:
        report['status'] = 'failed'
        report['error'] = str(error)
        raise
    finally:
        save()
        print(json.dumps({'status': report['status'], 'checks': len(report['checks']), 'commands': len(report['commands']), 'evidence': str(output/'validation.json')}))


if __name__ == '__main__':
    main()
