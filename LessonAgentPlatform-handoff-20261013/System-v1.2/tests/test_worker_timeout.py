"""Real worker processes: no network or model calls."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

import pytest

from isolated_worker import run_isolated, WorkerTimeout, WorkerFailed


FIXTURE = '''
import json, os, subprocess, sys, time
from pathlib import Path
def task(directory, mode):
    root = Path(directory)
    if mode == 'success': return {'worker_pid': os.getpid(), 'value': 'completed'}
    child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(120)'])
    (root/'pids.json').write_text(json.dumps([os.getpid(),child.pid]))
    (root/'model_usage.jsonl').write_text('{"event":"request_started"}\u005cn')
    if mode == 'fast': os._exit(7)
    time.sleep(30)
    (root/'late-result').write_text('must not publish')
    return {'value': 'late'}
'''


@pytest.fixture
def worker_runtime(tmp_path, monkeypatch):
    (tmp_path/'controlled_worker.py').write_text(FIXTURE, encoding='utf-8')
    monkeypatch.setenv('PYTHONPATH', str(tmp_path) + os.pathsep + str(Path(__file__).resolve().parents[1]))
    return tmp_path


def alive(pid):
    if os.name == 'nt':
        import win32api, win32con, win32process
        try:
            handle = win32api.OpenProcess(win32con.PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
        except Exception:
            return False
        try: return win32process.GetExitCodeProcess(handle) == 259
        finally: handle.Close()
    try: os.kill(pid, 0); return True
    except ProcessLookupError: return False


def assert_stopped(root):
    pids=json.loads((root/'pids.json').read_text())
    deadline=time.monotonic()+8
    while any(alive(pid) for pid in pids) and time.monotonic()<deadline: time.sleep(.05)
    assert not any(alive(pid) for pid in pids), pids
    assert not (root/'late-result').exists()


def test_result_comes_from_a_separate_successful_worker(worker_runtime):
    value=run_isolated('controlled_worker:task', [str(worker_runtime),'success'], {}, worker_runtime/'attempt', 10)
    assert value['value']=='completed'
    assert value['worker_pid']!=os.getpid()


@pytest.mark.parametrize('mode,error', [('block',WorkerTimeout),('fast',WorkerFailed)])
def test_timeout_or_launcher_exit_kills_children_and_preserves_unknown_usage(worker_runtime,mode,error):
    started=time.monotonic()
    with pytest.raises(error):
        run_isolated('controlled_worker:task', [str(worker_runtime),mode], {}, worker_runtime/'attempt', 1.5)
    assert time.monotonic()-started<10
    assert_stopped(worker_runtime)
    assert json.loads((worker_runtime/'model_usage.jsonl').read_text())=={'event':'request_started'}


def test_parent_process_death_stops_worker_and_prevents_late_result(worker_runtime):
    code = ('from isolated_worker import run_isolated; run_isolated("controlled_worker:task",'
            + repr([str(worker_runtime),'block']) + ',{},' + repr(str(worker_runtime/'attempt')) + ',60)')
    parent=subprocess.Popen([sys.executable,'-c',code],cwd=Path(__file__).resolve().parents[1])
    try:
        deadline=time.monotonic()+10
        while not (worker_runtime/'pids.json').exists() and time.monotonic()<deadline: time.sleep(.05)
        assert (worker_runtime/'pids.json').exists()
        parent.kill(); parent.wait(timeout=5)
        assert_stopped(worker_runtime)
        assert not (worker_runtime/'attempt/outcome.json').exists()
    finally:
        if parent.poll() is None: parent.kill(); parent.wait(timeout=5)


def test_supervisor_crash_also_cleans_its_worker_group(worker_runtime):
    # Record the supervisor PID at the process-launch dependency boundary.
    code = '''
import json
from pathlib import Path
import isolated_worker
original = isolated_worker.subprocess.Popen
def launch(*args, **kwargs):
    process = original(*args, **kwargs)
    Path(ROOT, 'supervisor.pid').write_text(str(process.pid))
    return process
isolated_worker.subprocess.Popen = launch
isolated_worker.run_isolated('controlled_worker:task',[ROOT,'block'],{},Path(ROOT,'attempt'),60)
'''.replace('ROOT', repr(str(worker_runtime)))
    parent=subprocess.Popen([sys.executable,'-c',code],cwd=Path(__file__).resolve().parents[1])
    try:
        deadline=time.monotonic()+10
        while not (worker_runtime/'pids.json').exists() and time.monotonic()<deadline: time.sleep(.05)
        assert (worker_runtime/'pids.json').exists()
        supervisor=int((worker_runtime/'supervisor.pid').read_text())
        if os.name == 'nt':
            import win32api, win32con
            handle=win32api.OpenProcess(win32con.PROCESS_TERMINATE,False,supervisor)
            try: win32api.TerminateProcess(handle,99)
            finally: handle.Close()
        else:
            import signal
            os.kill(supervisor,signal.SIGKILL)
        parent.wait(timeout=15)
        assert parent.returncode != 0
        assert_stopped(worker_runtime)
        assert not (worker_runtime/'attempt/outcome.json').exists()
    finally:
        if parent.poll() is None: parent.kill(); parent.wait(timeout=5)
        if os.name != 'nt' and (worker_runtime/'pids.json').exists():
            import signal
            pid=json.loads((worker_runtime/'pids.json').read_text())[0]
            try: os.killpg(pid,signal.SIGKILL)
            except ProcessLookupError: pass
