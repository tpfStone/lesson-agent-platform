import json
import sys
from pathlib import Path

import pytest
import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_f3_demo as runner


class Reply:
    ok = True
    status_code = 200
    text = ''

    def __init__(self, data):
        self.data = data

    def json(self):
        return self.data


def clock(monkeypatch):
    now = [0.0]
    monkeypatch.setattr(runner.time, 'monotonic', lambda: now[0])
    monkeypatch.setattr(runner.time, 'sleep', lambda seconds: now.__setitem__(0, now[0] + seconds))
    return now


def test_new_config_has_no_forced_classroom_limit(tmp_path):
    path = tmp_path / 'config.json'
    data = dict(lessonId='lesson', versionId='v0', f3Url='http://local',
                materials=[dict(materialId='r', teachingScript='text')])
    path.write_text(json.dumps(data))
    assert runner.load_config(path)['maxWallClockSeconds'] is None
    data['maxWallClockSeconds'] = 90
    path.write_text(json.dumps(data))
    assert runner.load_config(path)['maxWallClockSeconds'] == 90


def test_healthy_paused_session_outlives_old_runner_deadline(monkeypatch):
    now = clock(monkeypatch)
    class Session:
        def get(self, *args, **kwargs):
            return Reply({'status': 'PAUSED' if now[0] < 4000 else 'COMPLETED'})
        def post(self, *args, **kwargs):
            pytest.fail('A healthy paused classroom must not be ended by the runner clock')
    assert runner.poll_completed(Session(), 'http://local', 's', 10)['status'] == 'COMPLETED'


def test_transport_outage_is_bounded_without_a_classroom_cap(monkeypatch):
    now = clock(monkeypatch)
    ended = []
    class Session:
        def get(self, *args, **kwargs):
            raise requests.Timeout('offline')
        def post(self, *args, **kwargs):
            ended.append(args[0])
            return Reply({})
    with pytest.raises(RuntimeError, match='F3'):
        runner.poll_completed(Session(), 'http://local', 's', None)
    assert 0 < now[0] <= 180
    assert len(ended) == 1


def test_automatic_analysis_wait_is_read_only_and_preserves_failed_status(monkeypatch):
    now = clock(monkeypatch)
    class Session:
        def get(self, *args, **kwargs):
            return Reply({'analysisStatus': 'PENDING' if now[0] < 6 else 'FAILED',
                          'artifacts': [] if now[0] < 6 else ['issue_analysis.json']})
        def post(self, *args, **kwargs):
            pytest.fail('Runner must not submit or retry paid analysis after an unknown attempt')
    runner.analyze_session(Session(), 'http://local', 's')
    assert now[0] == 6
