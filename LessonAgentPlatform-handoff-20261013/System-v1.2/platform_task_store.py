"""Single-process durable submission ledger. Running work is never silently rescored."""
import hashlib
import json
import os
import threading
from pathlib import Path
from uuid import uuid4
from datetime import datetime, timezone

_lock = threading.RLock()
_active = set()


def atomic_json(path, value):
    path = Path(path)
    if os.name == 'nt':
        # The unique atomic suffix may cross MAX_PATH even when the published
        # filename fits. Extended paths avoid depending on machine-wide policy.
        absolute = str(path.resolve())
        if not absolute.startswith('\\\\?\\'):
            absolute = ('\\\\?\\UNC\\' + absolute[2:]) if absolute.startswith('\\\\') else ('\\\\?\\' + absolute)
        path = Path(absolute)
    temporary = path.with_name(path.name + '.' + uuid4().hex + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    temporary.replace(path)


class TaskStore:
    def __init__(self, root, capacity=100):
        self.root = Path(root); self.capacity = capacity
        self.root.mkdir(parents=True, exist_ok=True)

    def submit(self, key, payload, metadata=None):
        key = key or uuid4().hex
        run_id = hashlib.sha256(key.encode()).hexdigest()[:32]
        fingerprint = hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        directory = self.root / run_id
        with _lock:
            state_path = directory / 'state.json'
            if state_path.exists():
                state = json.loads(state_path.read_text(encoding='utf-8'))
                if state.get('inputFingerprint') != fingerprint: raise ValueError('REQUEST_KEY_CONFLICT')
                return state, False
            active = sum(json.loads(p.read_text(encoding='utf-8')).get('status') in ('QUEUED', 'RUNNING')
                         for p in self.root.glob('*/state.json'))
            if active >= self.capacity: raise RuntimeError('TASK_CAPACITY_EXCEEDED')
            directory.mkdir(exist_ok=True)
            atomic_json(directory / 'input.json', payload)
            state = {**(metadata or {}), 'runId': run_id, 'requestKey': key, 'inputFingerprint': fingerprint,
                     'status': 'QUEUED', 'createdAt': datetime.now(timezone.utc).isoformat()}
            atomic_json(state_path, state)
            return state, True

    def claim(self, run_id):
        """Claim queued work once within this service process, including duplicate schedules."""
        key = (str(self.root.resolve()), run_id)
        with _lock:
            path = self.root / run_id / 'state.json'
            if key in _active or not path.is_file():
                return False
            state = json.loads(path.read_text(encoding='utf-8'))
            if state.get('status') != 'QUEUED':
                return False
            state['status'] = 'RUNNING'
            atomic_json(path, state)
            _active.add(key)
            return True

    def release(self, run_id):
        with _lock:
            _active.discard((str(self.root.resolve()), run_id))

    def recover(self, resume_running=False):
        queued = []
        with _lock:
            for path in self.root.glob('*/state.json'):
                state = json.loads(path.read_text(encoding='utf-8'))
                if (str(self.root.resolve()), state.get('runId')) in _active:
                    continue
                source = path.parent / 'input.json'
                # Only batch orchestration may resume. Child model requests with
                # unknown outcomes stay interrupted and are never recharged here.
                if resume_running and state.get('status') == 'RUNNING' and source.exists():
                    state.update(status='QUEUED', recovery='RESUMING_UNSTARTED_ITEMS')
                    atomic_json(path, state)
                if state.get('status') == 'QUEUED' and source.exists():
                    queued.append((state['runId'], json.loads(source.read_text(encoding='utf-8'))))
                elif state.get('status') in ('QUEUED', 'RUNNING'):
                    state.update(status='INTERRUPTED', error='SERVICE_RESTART_UNRECOVERABLE')
                    atomic_json(path, state)
        return queued
