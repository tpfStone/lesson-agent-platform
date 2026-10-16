"""OpenAI-compatible LLM Provider。

配置通过 .env 读取（不写死模型、不把 key 写进代码）:
    API_KEY / BASE_URL / MODEL
"""
from __future__ import annotations

import os
import math
import uuid
from pathlib import Path

from .base import BaseLLMProvider, LLMError
from .mock import MockProvider
from .metering import UsageLedger
from app.isolated_worker import WorkerCancelled, WorkerFailed, WorkerTimeout, run_isolated


class ModelRequestUncertain(RuntimeError):
    """A possibly billed request must terminate this execution, never retry it."""


def positive_timeout(name: str, default: float) -> float:
    value = float(os.getenv(name, str(default)))
    if not math.isfinite(value) or value <= 0:
        raise ValueError(f'{name}_MUST_BE_POSITIVE')
    return value


def request_model(config, messages, temperature):
    """Worker boundary: credentials travel via stdin, never command lines/files."""
    import requests
    try:
        response = requests.post(config['base_url'].rstrip('/') + '/chat/completions',
            headers={'Authorization': 'Bearer ' + config['api_key']},
            json={'model': config['model'], 'messages': messages, 'temperature': temperature},
            timeout=(5, config['timeout']), allow_redirects=False)
        try:
            body = response.json()
        except ValueError:
            body = None
        return {'status_code': response.status_code, 'request_id': response.headers.get('x-request-id'),
                'body': body}
    except requests.RequestException as error:
        return {'transport_error': type(error).__name__}


class OpenAICompatProvider(BaseLLMProvider):
    name = "openai-compatible"

    def __init__(self, api_key: str, base_url: str, model: str):
        self.api_key = api_key
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.ledger = UsageLedger(Path(__file__).resolve().parents[2] / 'runtime/model_usage.jsonl', model=model)

    def bind_usage(self, path, task_id):
        self.ledger = UsageLedger(path, task_id, self.model)

    def worker_config(self):
        return {'api_key': self.api_key, 'base_url': self.base_url, 'model': self.model,
                'timeout': positive_timeout('F3_MODEL_TIMEOUT_SECONDS', 60)}

    def chat_isolated(self, messages: list[dict], temperature: float = 0.7, *, cancelled=None) -> str:
        """Formal service calls are hard-bounded independently of socket activity."""
        import requests
        config = self.worker_config()
        record = self.ledger.start(self.ledger.begin_call())
        attempt = self.ledger.path.parent / 'workers' / ('model-' + uuid.uuid4().hex)
        try:
            result = run_isolated('app.llm.provider:request_model', [config, messages, temperature], {},
                                  attempt, config['timeout'], cancelled=cancelled)
        except WorkerTimeout as error:
            self.ledger.finish(record, status='HARD_TIMEOUT', error=error)
            raise ModelRequestUncertain('MODEL_HARD_TIMEOUT') from error
        except WorkerCancelled as error:
            self.ledger.finish(record, status='CANCELLED_UNKNOWN', error=error)
            raise ModelRequestUncertain('MODEL_CANCELLED') from error
        except WorkerFailed as error:
            self.ledger.finish(record, status='WORKER_UNKNOWN', error=error)
            raise ModelRequestUncertain('MODEL_WORKER_FAILED') from error
        if result.get('transport_error'):
            self.ledger.finish(record, status='TRANSPORT_ERROR')
            raise ModelRequestUncertain('MODEL_TRANSPORT_UNKNOWN')
        response = requests.Response()
        response.status_code = result['status_code']
        if result.get('request_id'):
            response.headers['x-request-id'] = result['request_id']
        body = result.get('body')
        if not 200 <= response.status_code < 300:
            self.ledger.finish(record, status='HTTP_ERROR', response=response, body=body)
            raise LLMError(f'LLM HTTP status {response.status_code}')
        try:
            content = body['choices'][0]['message']['content']
            if not isinstance(content, str):
                raise ValueError('non-text response')
        except (KeyError, IndexError, ValueError, TypeError) as error:
            self.ledger.finish(record, status='RESPONSE_ERROR', response=response, body=body, error=error)
            raise LLMError('Unexpected LLM response') from error
        self.ledger.finish(record, status='SUCCEEDED', response=response, body=body)
        return content

    def chat(self, messages: list[dict], temperature: float = 0.7) -> str:
        import requests  # 延迟导入，未安装 requests 时 mock 模式仍可用

        url = f"{self.base_url}/chat/completions"
        headers = {"Authorization": f"Bearer {self.api_key}"}
        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": temperature,
        }
        record = self.ledger.start(self.ledger.begin_call())
        resp = None
        data = None
        try:
            resp = requests.post(url, headers=headers, json=payload,
                                 timeout=(5, float(os.getenv('F3_MODEL_TIMEOUT_SECONDS', '60'))))
            try:
                data = resp.json()
            except ValueError:
                # Preserve HTTP status failures even when their body is not JSON.
                if resp.ok:
                    raise
            resp.raise_for_status()
            content = data["choices"][0]["message"]["content"]
        except (KeyError, IndexError, ValueError, TypeError) as e:
            self.ledger.finish(record, status='RESPONSE_ERROR', response=resp, body=data, error=e)
            raise LLMError(f"Unexpected LLM response: {e}") from e
        except requests.RequestException as e:
            self.ledger.finish(record, status='HTTP_ERROR' if resp is not None else 'TRANSPORT_ERROR',
                               response=resp, body=data, error=e)
            raise LLMError(f"LLM request failed: {e}") from e
        self.ledger.finish(record, status='SUCCEEDED', response=resp, body=data)
        return content


def create_llm() -> BaseLLMProvider:
    """根据环境变量创建 provider；API 不可用时回退到 MockProvider。"""
    try:
        from dotenv import load_dotenv
        load_dotenv()
    except ImportError:
        pass

    api_key = os.getenv("API_KEY", "").strip()
    base_url = os.getenv("BASE_URL", "https://api.openai.com/v1").strip()
    model = os.getenv("MODEL", "gpt-4o-mini").strip()

    if not api_key:
        print("[SYSTEM] 未配置 API_KEY，使用 MockProvider（离线模式）运行。")
        return MockProvider()

    provider = OpenAICompatProvider(api_key, base_url, model)
    print(f"[SYSTEM] LLM Provider: {provider.name} | base_url={base_url} | model={model}")
    return provider
