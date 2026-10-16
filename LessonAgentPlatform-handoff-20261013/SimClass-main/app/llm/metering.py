"""Append-only model accounting; never store prompts, credentials or error bodies.

A started request without a finished row is UNKNOWN, not zero usage. The ledger
counts provider/SDK HTTP attempts, not redirects or supplier-side internal work.
"""
from contextlib import contextmanager
from contextvars import ContextVar
from datetime import datetime, timezone
from functools import wraps
import json
import logging
import os
from pathlib import Path
import threading
import uuid

_OPERATION = ContextVar("model_usage_operation", default=None)
_WRITE_LOCK = threading.Lock()
_TOKEN_FIELDS = {
    "prompt_tokens", "completion_tokens", "total_tokens",
    "input_tokens", "output_tokens", "prompt_cache_hit_tokens", "prompt_cache_miss_tokens",
}
_DETAIL_FIELDS = {"cached_tokens", "audio_tokens", "reasoning_tokens",
                  "accepted_prediction_tokens", "rejected_prediction_tokens"}


def safe_usage(body):
    usage = body.get("usage") if isinstance(body, dict) else None
    if not isinstance(usage, dict):
        return None
    result = {key: value for key, value in usage.items()
              if key in _TOKEN_FIELDS and type(value) is int and value >= 0}
    for key in ("prompt_tokens_details", "completion_tokens_details",
                "input_tokens_details", "output_tokens_details"):
        details = usage.get(key)
        if isinstance(details, dict):
            values = {name: value for name, value in details.items()
                      if name in _DETAIL_FIELDS and type(value) is int and value >= 0}
            if values:
                result[key] = values
    return result or None


@contextmanager
def usage_operation(name):
    parent = _OPERATION.get()
    scope = {"operation": name, "operation_id": uuid.uuid4().hex,
             "parent_operation_id": parent["operation_id"] if parent else None,
             "attempt": 0, "last_call_id": None}
    token = _OPERATION.set(scope)
    try:
        yield
    finally:
        _OPERATION.reset(token)


def metered_operation(name):
    def decorate(function):
        @wraps(function)
        def wrapped(*args, **kwargs):
            with usage_operation(name):
                return function(*args, **kwargs)
        return wrapped
    return decorate


class UsageLedger:
    def __init__(self, path, task_id=None, model=None):
        self.path = Path(path)
        self.task_id = task_id
        self.model = model
        self.write_failed = False

    def write(self, row):
        row = {"schema_version": 1, "timestamp": datetime.now(timezone.utc).isoformat(),
               "task_id": self.task_id, "model": self.model, **row}
        try:
            with _WRITE_LOCK:
                self.path.parent.mkdir(parents=True, exist_ok=True)
                with self.path.open("a", encoding="utf-8") as stream:
                    stream.write(json.dumps(row, ensure_ascii=False) + "\n")
                    stream.flush()
                    os.fsync(stream.fileno())
        except OSError as error:
            # Never cause another billable request because the accounting disk failed.
            self.write_failed = True
            logging.getLogger(__name__).error("MODEL_USAGE_WRITE_FAILED: %s", type(error).__name__)

    def begin_call(self):
        scope = _OPERATION.get()
        call_id = uuid.uuid4().hex
        if scope is None:
            return {"call_id": call_id, "operation": "unscoped", "operation_id": call_id,
                    "parent_operation_id": None, "application_attempt": 1, "retry_of_call_id": None}
        scope["attempt"] += 1
        call = {"call_id": call_id, "operation": scope["operation"],
                "operation_id": scope["operation_id"], "parent_operation_id": scope["parent_operation_id"],
                "application_attempt": scope["attempt"], "retry_of_call_id": scope["last_call_id"]}
        scope["last_call_id"] = call_id
        return call

    def start(self, call, sdk_retry_index=0):
        request = {**call, "request_id": uuid.uuid4().hex, "sdk_retry_index": sdk_retry_index}
        self.write({**request, "event": "request_started"})
        return request

    def finish(self, request, *, status, response=None, body=None, error=None):
        self.write({**request, "event": "request_finished", "status": status,
                    "http_status": response.status_code if response is not None else None,
                    "provider_request_id": response.headers.get("x-request-id") if response is not None else None,
                    "usage": safe_usage(body), "error_type": type(error).__name__ if error else None})
