#!/usr/bin/env python3
"""Pin current Animus and VolmLib Git-visible sources before a Multiplexor build.
Ignored build output, private QA fixtures and service-account files are never read.
The snapshot is a local source copy, not a commit; Multiplexor captures it again per run.
"""
import argparse, hashlib, json, shutil, subprocess
from pathlib import Path

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--animus',type=Path,default=Path.cwd())
parser.add_argument('--volmlib',type=Path)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--manifest',type=Path,required=True)
args=parser.parse_args()
args.animus=args.animus.resolve()
projects=[args.animus,(args.volmlib or args.animus.parent/'VolmLib').resolve()]
if args.output.exists():parser.error('Use a new snapshot directory; existing snapshots are preserved')
manifest={}
for project in projects:
    # A worktree checkout keeps the canonical project directory name in the capture.
    label='Animus' if project==args.animus else project.name
    source=args.output/label
    names=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=project).decode().split('\0')
    hashes={}
    for name in sorted(set(names)-{''}):
        path=Path(name)
        if any(part in ('.qa','.git','build','node_modules','__pycache__') for part in path.parts):continue
        if path.name.lower().startswith(('service-account','.env')):continue
        origin=project/path
        if not origin.is_file():continue
        if origin.is_symlink() and not origin.resolve().is_relative_to(project):raise ValueError(f'External source link: {path}')
        target=source/path
        target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(origin,target)
        hashes[name]=hashlib.sha256(target.read_bytes()).hexdigest()
    head=subprocess.run(['git','rev-parse','HEAD'],cwd=project,text=True,capture_output=True)
    manifest[label]={'sourcePath':str(project),'gitHead':head.stdout.strip() if head.returncode==0 else None,'files':hashes,'snapshotSha256':hashlib.sha256(json.dumps(hashes,sort_keys=True).encode()).hexdigest()}
    print(label,len(hashes),manifest[label]['snapshotSha256'])
# Avoid Git resolving ignored QA copies against the containing development checkout.
subprocess.run(['git','init','-q',str(args.output)],check=True)
args.manifest.parent.mkdir(parents=True,exist_ok=True)
args.manifest.write_text(json.dumps(manifest,indent=2)+'\n')
