import importlib.util
from pathlib import Path
import pytest
spec=importlib.util.spec_from_file_location('health_status',Path(__file__).resolve().parents[1]/'health-status.py')
health=importlib.util.module_from_spec(spec)
spec.loader.exec_module(health)

@pytest.mark.parametrize('body,expected',[
    ({'status':'UP'},True),
    ({'status':'up','model_configured':False},False),
    ({'status':'up','ready':False},False),
    ({'status':'up','provider':'real','llm_configured':False},False),
    ({'status':'up','provider':'mock','llm_configured':False},True),
    ([],False),
])
def test_listener_does_not_imply_readiness(body,expected):
    assert health.ready(body) is expected
