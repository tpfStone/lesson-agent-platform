import json

import pytest

from app.providers.interfaces import LLMContext
from app.providers.llm import GenericLLMSuggestionProvider, ProviderError


def generate(draft, structured=True):
    class Client:
        def complete(self, context):
            return {'choices': [{'finish_reason': 'stop', 'message': {
                'content': json.dumps({'suggestions': [draft]}, ensure_ascii=False)}}]}
    current = {'editable_nodes': [{'block_id': 'p', 'kind': 'paragraph', 'text': '原文'}]} if structured else {'content': '原文'}
    context = LLMContext(system='test', user={'fragments': [{'contributor_type': 'current_section', 'content': current}]})
    return GenericLLMSuggestionProvider(Client()).generate(context)


def draft(**fields):
    return {'issue': '问题', 'reason': '原因', 'pedagogical_basis': '教师复核', 'revision': '展示内容', **fields}


def test_structured_model_must_address_existing_content_instead_of_returning_text_target():
    with pytest.raises(ProviderError):
        generate(draft(revision_mode='replace', target_text='原文'))


def test_append_must_not_claim_it_replaces_existing_text():
    with pytest.raises(ProviderError):
        generate(draft(revision_mode='append', target_text='原文'))


def test_json_newlines_and_literal_backslashes_arrive_as_raw_node_text():
    value = '第一行\n第二行 | C:\\new'
    saved = generate(draft(revision_mode='replace', edits=[{
        'block_id': 'p', 'expected_text': '原文', 'text': value}]))[0]
    assert saved.edits[0].text == value
    # The visible preview must show what will actually be applied, not a divergent summary.
    assert saved.revision == value


def test_legacy_plaintext_provider_keeps_exact_target_contract():
    saved = generate(draft(revision_mode='replace', target_text='原文'), structured=False)[0]
    assert saved.target_text == '原文' and saved.edits == []
