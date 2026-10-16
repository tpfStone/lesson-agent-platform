"""
LLM Provider 抽象层
支持 DeepSeek / OpenAI 切换

API Key 解析优先级（2026-09-21 交付版改造，便于非技术用户使用）：
  1. 本地文件 data/.api_key —— Web「设置」页填写并保存，重启后依然有效
  2. config.yaml 的 api_keys 中直接填写明文 key
  3. 环境变量 DEEPSEEK_API_KEY / OPENAI_API_KEY
"""

import json
import os
from abc import ABC, abstractmethod
from contextvars import ContextVar
from pathlib import Path
from openai import OpenAI, DefaultHttpxClient
from usage_meter import UsageLedger


class MeteredHTTPClient(DefaultHttpxClient):
    """Observe every SDK attempt, including its automatic timeout/status retries."""
    def __init__(self, ledger):
        super().__init__()
        self.ledger = ledger
        self.active_call = ContextVar('f1_model_call', default=None)

    def send(self, request, **kwargs):
        active = self.active_call.get()
        call = active['call'] if active else self.ledger.begin_call()
        index = active['attempt'] if active else 0
        if active:
            active['attempt'] += 1
        record = self.ledger.start(call, sdk_retry_index=index)
        try:
            response = super().send(request, **kwargs)
        except Exception as error:
            self.ledger.finish(record, status='TRANSPORT_ERROR', error=error)
            raise
        try:
            body = response.json()
        except (ValueError, RuntimeError):
            body = None
        self.ledger.finish(record, status='SUCCEEDED' if response.is_success else 'HTTP_ERROR',
                           response=response, body=body)
        return response

# 本地保存的 Key 文件（与 config.yaml 同级的 data/ 目录下，随项目走）
LOCAL_KEY_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", ".api_key")
_ENV_MAP = {"deepseek": "DEEPSEEK_API_KEY", "openai": "OPENAI_API_KEY"}


def _norm(provider_name: str) -> str:
    """'DeepSeek' → 'deepseek'"""
    return (provider_name or "").strip().lower()


def load_local_key(provider_name: str = "deepseek") -> str:
    """读取 Web 设置页保存到本地的 API Key"""
    try:
        if os.path.exists(LOCAL_KEY_PATH):
            with open(LOCAL_KEY_PATH, encoding="utf-8") as f:
                data = json.load(f)
            return str(data.get(_norm(provider_name), "") or "").strip()
    except Exception:
        pass
    return ""


def save_local_key(key: str, provider_name: str = "deepseek") -> str:
    """保存 API Key 到本地文件（与已有其他 provider 的 key 合并），返回文件路径"""
    data = {}
    if os.path.exists(LOCAL_KEY_PATH):
        try:
            with open(LOCAL_KEY_PATH, encoding="utf-8") as f:
                data = json.load(f) or {}
        except Exception:
            data = {}
    data[_norm(provider_name)] = (key or "").strip()
    os.makedirs(os.path.dirname(LOCAL_KEY_PATH), exist_ok=True)
    with open(LOCAL_KEY_PATH, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    try:
        os.chmod(LOCAL_KEY_PATH, 0o600)   # 尽量限制权限（Windows 上为尽力而为）
    except Exception:
        pass
    return LOCAL_KEY_PATH


def mask_key(k: str) -> str:
    """脱敏显示：只露最后 4 位"""
    k = (k or "").strip()
    if not k:
        return ""
    return f"…{k[-4:]}" if len(k) > 4 else "…" + "*" * len(k)


def get_key_status(key_config: str = "", provider_name: str = "deepseek") -> dict:
    """
    查询 Key 配置状态，供界面显示。
    返回 {"configured": bool, "source": 来源说明, "masked": 脱敏 key}
    """
    p = _norm(provider_name)
    local = load_local_key(p)
    if local:
        return {"configured": True, "source": "本地保存（设置页填写，data/.api_key）",
                "masked": mask_key(local)}
    if key_config and not str(key_config).startswith("${"):
        return {"configured": True, "source": "config.yaml（api_keys）", "masked": mask_key(key_config)}
    env = os.environ.get(_ENV_MAP.get(p, ""), "")
    if env:
        return {"configured": True, "source": f"环境变量 {_ENV_MAP.get(p, '')}", "masked": mask_key(env)}
    return {"configured": False, "source": "", "masked": ""}


def resolve_api_key(key_config, env_var, provider_name):
    """解析 API Key：本地文件 → config.yaml → 环境变量"""
    local = load_local_key(provider_name)
    if local:
        return local
    if key_config and not str(key_config).startswith("${"):
        return key_config
    env_key = os.environ.get(env_var, "")
    if not env_key:
        raise ValueError(
            f"未找到 {provider_name} API Key。\n"
            f"推荐做法：启动 Web 界面，在「⚙️ 设置」页填入 Key 并点击保存（会存到 data/.api_key）；\n"
            f"也可以在 config.yaml 的 api_keys 中直接填写，或设置环境变量 {env_var}。"
        )
    return env_key


class BaseProvider(ABC):
    def _complete(self, system_prompt, user_prompt, *, json_mode=False, **kwargs):
        call = self.ledger.begin_call()
        token = self.http_client.active_call.set({'call': call, 'attempt': 0})
        try:
            resp = self.client.chat.completions.create(
                model=self.model,
                messages=[{'role': 'system', 'content': system_prompt},
                          {'role': 'user', 'content': user_prompt}],
                temperature=kwargs.get('temperature', self.temperature),
                max_tokens=kwargs.get('max_tokens', self.max_tokens),
                **({'response_format': {'type': 'json_object'}} if json_mode else {}),
            )
            content = resp.choices[0].message.content
        except Exception as error:
            self.ledger.write({**call, 'event': 'call_finished', 'status': 'FAILED',
                               'error_type': type(error).__name__})
            raise
        else:
            self.ledger.write({**call, 'event': 'call_finished', 'status': 'SUCCEEDED'})
            return content
        finally:
            self.http_client.active_call.reset(token)

    @abstractmethod
    def chat(self, system_prompt: str, user_prompt: str, **kwargs) -> str:
        pass

    @abstractmethod
    def chat_json(self, system_prompt: str, user_prompt: str, **kwargs) -> str:
        pass


class DeepSeekProvider(BaseProvider):
    def __init__(self, api_key=None, model="deepseek-v4-pro",
                 temperature=0.1, max_tokens=16384, usage_path=None, task_id=None):
        self.api_key = resolve_api_key(api_key, "DEEPSEEK_API_KEY", "DeepSeek")
        self.model = model
        self.temperature = temperature
        self.max_tokens = max_tokens
        self.ledger = UsageLedger(usage_path or Path(__file__).parent / 'output/model_usage.jsonl', task_id, model)
        self.http_client = MeteredHTTPClient(self.ledger)
        self.client = OpenAI(
            api_key=self.api_key,
            base_url="https://api.deepseek.com",
            http_client=self.http_client,
        )

    def chat(self, system_prompt, user_prompt, **kwargs):
        return self._complete(system_prompt, user_prompt, **kwargs)

    def chat_json(self, system_prompt, user_prompt, **kwargs):
        return self._complete(system_prompt, user_prompt, json_mode=True, **kwargs)


class OpenAIProvider(BaseProvider):
    """OpenAI 预留接口"""
    def __init__(self, api_key=None, model="gpt-4o",
                 temperature=0.1, max_tokens=16384, usage_path=None, task_id=None):
        self.api_key = resolve_api_key(api_key, "OPENAI_API_KEY", "OpenAI")
        self.model = model
        self.temperature = temperature
        self.max_tokens = max_tokens
        self.ledger = UsageLedger(usage_path or Path(__file__).parent / 'output/model_usage.jsonl', task_id, model)
        self.http_client = MeteredHTTPClient(self.ledger)
        self.client = OpenAI(api_key=self.api_key, http_client=self.http_client)

    def chat(self, system_prompt, user_prompt, **kwargs):
        return self._complete(system_prompt, user_prompt, **kwargs)

    def chat_json(self, system_prompt, user_prompt, **kwargs):
        return self._complete(system_prompt, user_prompt, json_mode=True, **kwargs)


def create_provider(provider_name, config):
    """工厂方法"""
    llm = config.get("llm", {})
    keys = config.get("api_keys", {})
    default_ledger = Path(config.get('output', {}).get('dir', Path(__file__).parent / 'output')) / 'model_usage.jsonl'
    usage = {'usage_path': Path(config.get('_usage_ledger_path') or default_ledger),
             'task_id': config.get('_run_id')}

    if provider_name == "deepseek":
        return DeepSeekProvider(
            api_key=keys.get("deepseek"),
            model=llm.get("model", "deepseek-v4-pro"),
            temperature=llm.get("temperature", 0.1),
            max_tokens=llm.get("max_tokens", 16384),
            **usage,
        )
    elif provider_name == "openai":
        return OpenAIProvider(
            api_key=keys.get("openai"),
            model=llm.get("model", "gpt-4o"),
            temperature=llm.get("temperature", 0.1),
            max_tokens=llm.get("max_tokens", 16384),
            **usage,
        )
    raise ValueError(f"不支持的 Provider: {provider_name}")
