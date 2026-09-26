#!/usr/bin/env python3
"""Small APP_ENV selector. Production lifecycle is owned by the Deploy workflow."""
import os
from pathlib import Path
import subprocess
import sys

root=Path(__file__).resolve().parents[1]
environment=os.environ.get('APP_ENV','dev')
if environment not in ['dev','prod']:
    raise SystemExit('APP_ENV must be dev or prod')
if environment=='prod':
    raise SystemExit('Use GitHub Actions → Deploy with an immutable release ID for production.')
env_file=Path(os.environ.get('APP_ENV_FILE',root/'.env.dev'))
if not env_file.is_file():
    raise SystemExit('Environment file does not exist')
args=sys.argv[1:] or ['up','-d','--build']
if any(x in args for x in ['-v','--volumes']) or 'prune' in args:
    raise SystemExit('Volume deletion is not supported by this launcher')
raise SystemExit(subprocess.call(['docker','compose','--env-file',str(env_file),'-f',str(root/'docker-compose.yml'),*args],cwd=root))
