"""Read-only assertions shared by managed import acceptance scenarios.

These check persisted public synthetic data, never production/private samples.
No DocumentStore is opened concurrently with the running plugin.
"""
import hashlib
import json
import re
import uuid
from pathlib import Path

HASH=re.compile(r'[a-f0-9]{64}')

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def read(path):
    return json.loads(Path(path).read_text())

def snapshot(data):
    data=Path(data)
    result={}
    for prefix in ['npcs','state/npc-definitions/last-valid/npcs']:
        for path in sorted((data/prefix).rglob('*.json')):
            assert not path.is_symlink(),'QA refuses linked definition/recovery data'
            result[str(path.relative_to(data))]=path.read_text()
    return result

def assert_snapshot(data,expected):
    assert snapshot(data)==expected,'Definition or recovery-pointer bytes changed unexpectedly'

def definitions(data):
    return {str(path.relative_to(data)):read(path) for path in sorted((Path(data)/'npcs').rglob('*.json'))}

def checkpoint(data,path):
    data=Path(data);document=read(data/path)
    pointer=read(data/'state/npc-definitions/last-valid'/path)
    assert pointer['schemaVersion']==1 and pointer['path']==path
    assert pointer['sourceHash']==sha(data/path)
    obj=data/'state/objects/npc-definitions'/f'{pointer["objectHash"]}.json'
    assert sha(obj)==pointer['objectHash'] and read(obj)==document
    return pointer

def dormant(document):
    assert isinstance(document['spawned'],bool)
    combat=document.get('modules',{}).get('animus:combat')
    if combat:
        assert combat['enabled'] is True and 'reviewAccepted' not in combat

def application(data,import_id,application_id):
    assert HASH.fullmatch(import_id) and HASH.fullmatch(application_id)
    data=Path(data);path=data/'imports'/import_id/'applications'/application_id/'plan.json'
    assert sha(path)==application_id
    plan=read(path)
    assert plan['schemaVersion']==1 and plan['importId']==import_id
    candidates={}
    for target,descriptor in plan['documents'].items():
        assert re.fullmatch(r'(npcs/[a-z0-9_-]+/[a-z0-9_-]+|skins/[a-f0-9]{64})\.json',target)
        obj=data/'state/objects/import-applications'/f'{descriptor["objectHash"]}.json'
        assert sha(obj)==descriptor['objectHash']
        wrapped=read(obj);assert wrapped['schemaVersion']==1
        candidates[target]=wrapped['document']
        if target.startswith('npcs/'):
            definition=json.loads(wrapped['document']);dormant(definition)
            assert target==f'npcs/{definition["group"]}/{definition["id"]}.json'
    assert sum(path.startswith('npcs/') for path in candidates)==plan['records']
    return plan,candidates

def installed(data,import_id,application_id):
    data=Path(data);plan,candidates=application(data,import_id,application_id)
    for relative,content in candidates.items():
        assert (data/relative).read_text()==content,relative+' differs from reviewed candidate'
        if relative.startswith('npcs/'):checkpoint(data,relative)
    receipt=read(data/'imports'/import_id/'applications'/application_id/'receipt.json')
    assert receipt['state']=='installed' and receipt['importId']==import_id
    assert receipt['applicationId']==application_id and receipt['mode']==plan['mode']
    assert receipt['records']==plan['records']
    return plan,candidates

def duplicate(data,import_id,application_id,label):
    data=Path(data);plan,candidates=installed(data,import_id,application_id)
    assert plan['mode']=='DUPLICATE' and plan['copyLabel']==label
    source_report=read(data/'imports'/import_id/'report.json')
    copied={}
    for legacy,source_uuid in source_report['idMap'].items():
        digest=hashlib.md5(f'animus:import-copy:v1:{import_id}:{label}:{source_uuid}'.encode()).digest()
        copied_uuid=str(uuid.UUID(bytes=digest,version=3))
        assert plan['uuidMap'][source_uuid]==copied_uuid and copied_uuid!=source_uuid
        relative=f'npcs/copy-{label}/citizens-{legacy}-{label}.json'
        target=json.loads(candidates[relative]);source=read(data/'imports'/import_id/f'npcs/world/citizens-{legacy}.json')
        assert target['uuid']==copied_uuid and target['revision']==1
        assert target['migration']['copy']=={'label':label,'sourceUuid':source_uuid,'sourceId':source['id']}
        # A copy guards the copy of its guarded NPC; every other preserved module value stays byte-for-byte.
        expected=json.loads(json.dumps(source['modules']))
        guard=expected.get('animus:combat',{}).get('guard')
        if isinstance(guard,dict) and 'npc' in guard and any(r['status']=='resolved' and r['source']=='/traits/sentinel/guardedNPC' for r in source['migration'].get('references',[])):
            guard['npc']=plan['uuidMap'][guard['npc']]
        assert target['modules']==expected,'Preserved module data must not be guessed or rewritten'
        for before,after in zip(source['migration']['references'],target['migration']['references']):
            assert after['original']==before['original'] and after['source']==before['source']
            if before['status']=='resolved':
                assert after['npcUuid']==plan['uuidMap'][before['npcUuid']]
                assert after['npcId']==before['npcId']+'-'+label
            else:assert after==before
        copied[relative]=target
    assert len(copied)==plan['records']
    return copied

def original_archive(data,import_id,source_bytes):
    data=Path(data);source=json.loads(source_bytes)
    assert (data/'imports'/import_id/'source.yml').read_bytes()==source_bytes
    report=read(data/'imports'/import_id/'report.json')
    assert report['sourceSha256']==hashlib.sha256(source_bytes).hexdigest()
    for legacy,record in source['npc'].items():assert read(data/'imports'/import_id/'records'/f'{legacy}.json')==record
    return report
