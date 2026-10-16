"""Pure analysis worker: the parent owns publication and the attempt identity."""
from app.analysis.issue_agent import IssueAnalysisAgent
from app.contracts.event import ClassroomEventRecord
from app.llm.provider import OpenAICompatProvider


class _BoundedProvider(OpenAICompatProvider):
    def chat(self, messages, temperature=0.7):
        return self.chat_isolated(messages, temperature)


def analyze(config, usage_path, values):
    provider = _BoundedProvider(config['api_key'], config['base_url'], config['model'])
    provider.bind_usage(usage_path, values['session_id'])
    values['events'] = [ClassroomEventRecord.model_validate(event) for event in values['events']]
    return IssueAnalysisAgent(provider).analyze(**values).model_dump(by_alias=True)
