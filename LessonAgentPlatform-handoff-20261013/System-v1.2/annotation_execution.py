"""Platform annotation boundary; only the API parent publishes task state."""
from copy import deepcopy
import os
from pathlib import Path
from uuid import uuid4

from isolated_worker import run_isolated


def execute_annotation_worker(base_dir, config, filename, **kwargs):
    timeout = os.environ.get('F1_ANNOTATION_HARD_TIMEOUT_SECONDS',
                             config.get('execution', {}).get('hard_timeout_seconds', 900))
    worker_config = deepcopy(config)
    canonical_output = Path(config['output']['dir']).resolve()
    attempt = canonical_output.parent / 'attempts' / uuid4().hex
    worker_config['output']['dir'] = str(attempt / 'output')
    # Provider usage survives even when an unfinished attempt cannot publish a result.
    worker_config['_usage_ledger_path'] = str(canonical_output / 'model_usage.jsonl')
    return run_isolated('service:run_annotation', [base_dir, worker_config, filename], kwargs, attempt, timeout)
