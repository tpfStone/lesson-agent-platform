"""Single-process durable submission ledger. Running work is never silently rescored."""
import hashlib
import json
import threading
from pathlib import Path
from uuid import uuid4
from datetime import datetime, timezone

_lock = threading.RLock()


def atomic_json(path, value):
    path = Path(path)
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

    def recover(self):
        queued = []
        with _lock:
            for path in self.root.glob('*/state.json'):
                state = json.loads(path.read_text(encoding='utf-8'))
                source = path.parent / 'input.json'
                if state.get('status') == 'QUEUED' and source.exists():
                    queued.append((state['runId'], json.loads(source.read_text(encoding='utf-8'))))
                elif state.get('status') in ('QUEUED', 'RUNNING'):
                    state.update(status='INTERRUPTED', error='SERVICE_RESTART_UNRECOVERABLE')
                    atomic_json(path, state)
        return queued
