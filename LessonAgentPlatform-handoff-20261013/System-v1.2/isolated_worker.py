"""One-shot workers contained before executing application code.

The supervisor owns the Windows Job / Linux process group and watches a pipe
owned by the API parent. Payloads travel through pipes, never command arguments
or request files (configuration can contain credentials).
"""
from __future__ import annotations

import importlib
import json
import math
import os
from pathlib import Path
import signal
import subprocess
import sys
import threading
import time

from platform_task_store import atomic_json


class WorkerFailed(RuntimeError):
    pass


class WorkerTimeout(WorkerFailed):
    pass


def _options():
    return {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}


def _cleanup_abandoned_group(attempt):
    """Parent fallback when a Linux supervisor cannot execute its own finally."""
    if not sys.platform.startswith('linux'):
        return
    metadata = attempt / 'worker.json'
    if not metadata.exists():
        return  # Payload was never released to an application worker.
    group = json.loads(metadata.read_text(encoding='utf-8'))['pid']
    try: os.killpg(group, signal.SIGKILL)
    except ProcessLookupError: return
    deadline = time.monotonic() + 5
    while True:
        try: os.killpg(group, 0)
        except ProcessLookupError: return
        if time.monotonic() >= deadline:
            raise WorkerFailed('WORKER_GROUP_CLEANUP_UNCONFIRMED')
        time.sleep(.025)


def run_isolated(target, args, kwargs, attempt_dir, timeout_seconds):
    """Run a trusted internal module:function with a non-disableable wall deadline."""
    timeout_seconds = float(timeout_seconds)
    if not math.isfinite(timeout_seconds) or timeout_seconds <= 0:
        raise ValueError('WORKER_TIMEOUT_MUST_BE_POSITIVE')
    attempt = Path(attempt_dir).resolve()
    attempt.mkdir(parents=True, exist_ok=False)
    payload = json.dumps({'target': target, 'args': args, 'kwargs': kwargs}, ensure_ascii=False).encode('utf-8') + b'\n'
    with (attempt / 'worker.log').open('wb') as log:
        supervisor = subprocess.Popen(
            [sys.executable, str(Path(__file__).resolve()), '--supervise', str(attempt), str(timeout_seconds)],
            stdin=subprocess.PIPE, stdout=log, stderr=log, close_fds=True, **_options())
        try:
            supervisor.stdin.write(payload)
            supervisor.stdin.flush()
            # Keep stdin open: EOF means this parent is gone, not that payload is complete.
            supervisor.wait(timeout=timeout_seconds + 15)
        except subprocess.TimeoutExpired as error:
            supervisor.stdin.close()
            try:
                supervisor.wait(timeout=10)
            except subprocess.TimeoutExpired:
                supervisor.kill()
                supervisor.wait(timeout=5)
            raise WorkerFailed('WORKER_SUPERVISOR_UNRESPONSIVE') from error
        finally:
            if not supervisor.stdin.closed:
                supervisor.stdin.close()
            if supervisor.poll() is None:
                # Also handles interrupted API threads / failed pipe writes.
                try: supervisor.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    supervisor.kill()
                    supervisor.wait(timeout=5)
            # Windows kernel Job closure covers abrupt supervisor termination.
            # Linux needs this surviving parent to clean the recorded group.
            status_path = attempt / 'supervision.json'
            if not status_path.exists():
                _cleanup_abandoned_group(attempt)
    status_path = attempt / 'supervision.json'
    status = json.loads(status_path.read_text(encoding='utf-8')) if status_path.exists() else {}
    if status.get('status') == 'TIMEOUT':
        raise WorkerTimeout('F1_HARD_TIMEOUT')
    if supervisor.returncode != 0 or status.get('status') != 'SUCCESS':
        raise WorkerFailed('WORKER_FAILED:' + status.get('status', 'SUPERVISOR_EXITED'))
    outcome = json.loads((attempt / 'outcome.json').read_text(encoding='utf-8'))
    if not outcome.get('ok'):
        raise WorkerFailed('WORKER_FAILED:' + outcome.get('errorType', 'UNKNOWN'))
    return outcome['value']


def _supervise(attempt, timeout):
    parent_gone = threading.Event()
    payload = sys.stdin.buffer.readline()
    if not payload:
        return 1
    def watch_parent():
        # Raw descriptor reads avoid holding BufferedReader's lock when this
        # daemon is still waiting during normal interpreter shutdown.
        while os.read(sys.stdin.fileno(), 1024): pass
        parent_gone.set()
    threading.Thread(target=watch_parent, daemon=True).start()
    worker = None
    job = None
    status = 'FAILED'
    try:
        if os.name == 'nt':
            import win32job
            job = win32job.CreateJobObject(None, '')
            limits = win32job.QueryInformationJobObject(job, win32job.JobObjectExtendedLimitInformation)
            limits['BasicLimitInformation']['LimitFlags'] = win32job.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            win32job.SetInformationJobObject(job, win32job.JobObjectExtendedLimitInformation, limits)
        elif sys.platform.startswith('linux'):
            # Adopt orphaned grandchildren so group cleanup can reap them instead
            # of relying on the host/container's PID 1 to reap zombies promptly.
            import ctypes
            if ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) != 0:
                raise OSError('WORKER_SUBREAPER_UNAVAILABLE')
        else:
            raise OSError('WORKER_CONTAINMENT_UNSUPPORTED')
        if parent_gone.is_set():
            status = 'PARENT_EXITED'
            return 1
        worker = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '--invoke', str(attempt)],
                                  stdin=subprocess.PIPE, close_fds=True,
                                  start_new_session=os.name != 'nt', **_options())
        atomic_json(attempt / 'worker.json', {'pid': worker.pid})
        if job is not None:
            import win32api, win32con
            handle = win32api.OpenProcess(win32con.PROCESS_SET_QUOTA | win32con.PROCESS_TERMINATE, False, worker.pid)
            try: win32job.AssignProcessToJobObject(job, handle)
            finally: handle.Close()
        # Child has been blocked reading stdin until the containment is in place.
        deadline = time.monotonic() + timeout
        worker.stdin.write(payload)
        worker.stdin.close()
        while True:
            if parent_gone.is_set():
                status = 'PARENT_EXITED'
                break
            if time.monotonic() >= deadline:
                status = 'TIMEOUT'
                break
            if worker.poll() is not None:
                status = 'SUCCESS' if worker.returncode == 0 else 'FAILED'
                break
            parent_gone.wait(.025)
    except BaseException:
        status = 'CONTAINMENT_FAILED'
    finally:
        try:
            if worker is not None:
                if job is not None:
                    win32job.TerminateJobObject(job, 1)
                    # If assignment failed the blocked child is not yet in the Job.
                    if worker.poll() is None: worker.kill()
                    worker.wait(timeout=5)
                    deadline = time.monotonic() + 5
                    while win32job.QueryInformationJobObject(job, win32job.JobObjectBasicAccountingInformation)['ActiveProcesses']:
                        if time.monotonic() >= deadline: raise RuntimeError('JOB_NOT_EMPTY')
                        time.sleep(.025)
                else:
                    try: os.killpg(worker.pid, signal.SIGKILL)
                    except ProcessLookupError: pass
                    worker.wait(timeout=5)
                    deadline = time.monotonic() + 5
                    while True:
                        try:
                            while os.waitpid(-1, os.WNOHANG)[0]: pass
                        except ChildProcessError: pass
                        try: os.killpg(worker.pid, 0)
                        except ProcessLookupError: break
                        if time.monotonic() >= deadline: raise RuntimeError('PROCESS_GROUP_NOT_EMPTY')
                        time.sleep(.025)
        except BaseException:
            status = 'CLEANUP_FAILED'
        finally:
            if job is not None: job.Close()
        atomic_json(attempt / 'supervision.json', {'status': status})
    return 0 if status == 'SUCCESS' else 1


def _invoke(attempt):
    try:
        payload = json.loads(sys.stdin.buffer.readline())
        module, function = payload['target'].split(':', 1)
        value = getattr(importlib.import_module(module), function)(*payload['args'], **payload['kwargs'])
        atomic_json(attempt / 'outcome.json', {'ok': True, 'value': value})
        return 0
    except BaseException as error:
        # No exception text: model SDK errors can include secrets or prompt text.
        atomic_json(attempt / 'outcome.json', {'ok': False, 'errorType': type(error).__name__})
        return 1


if __name__ == '__main__':
    directory = Path(sys.argv[2])
    sys.exit(_supervise(directory, float(sys.argv[3])) if sys.argv[1] == '--supervise' else _invoke(directory))
