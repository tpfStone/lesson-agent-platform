from concurrent.futures import ThreadPoolExecutor
import threading
from app.providers.defaults import MockSuggestionProvider
from test_workflow import create, target


def test_slow_generation_does_not_block_other_session_read(client, monkeypatch):
    a, _ = create(client)
    b, _ = create(client)
    entered, release = threading.Event(), threading.Event()
    original = MockSuggestionProvider.generate
    def slow(self, *args, **kwargs):
        entered.set()
        assert release.wait(5)
        return original(self, *args, **kwargs)
    monkeypatch.setattr(MockSuggestionProvider, 'generate', slow)
    with ThreadPoolExecutor(max_workers=2) as pool:
        pending = pool.submit(client.post, f"/api/sessions/{a['session']['id']}/suggestions", json=target(a))
        assert entered.wait(2)
        try:
            read = pool.submit(client.get, f"/api/sessions/{b['session']['id']}/current-state")
            assert read.result(timeout=1).status_code == 200
        finally:
            release.set()
        assert pending.result().status_code == 200
