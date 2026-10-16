"""Imported only by the real-process lifecycle tests."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time


def spawn_descendant(marker):
    options = {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}
    child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'], close_fds=True, **options)
    Path(marker).write_text(json.dumps({'worker': os.getpid(), 'child': child.pid}), encoding='utf-8')
    time.sleep(60)


def finish_after_marker(marker):
    Path(marker).write_text('done', encoding='utf-8')
    return {'finished': True}
