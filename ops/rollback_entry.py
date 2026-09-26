#!/usr/bin/env python3
"""Restricted automation entrypoint for a compatible code-only rollback."""
import os
import re
import subprocess
import sys

if __name__=='__main__':
    if os.geteuid()!=0 or len(sys.argv)!=2:
        raise SystemExit('Usage: unimeow-rollback RELEASE_ID (root)')
    target=sys.argv[1]
    if not re.fullmatch(r'(?:baseline-[0-9]{8}|v[0-9]+\.[0-9]+\.[0-9]+(?:-[a-zA-Z0-9][a-zA-Z0-9.-]{0,40})?)',target):
        raise SystemExit('Invalid rollback target')
    unit='unimeow-rollback-'+target.replace('.','-')
    result=subprocess.run(['systemd-run','--unit='+unit,'--collect','--wait','--property=Type=exec',
                           '/usr/bin/python3','-u','/srv/unimeow/operations/rollback.py',target])
    subprocess.run(['journalctl','-u',unit,'--no-pager','-n','250'])
    raise SystemExit(result.returncode)
