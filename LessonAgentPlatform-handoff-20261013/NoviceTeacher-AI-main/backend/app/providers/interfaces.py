"""Research contracts: providers receive plain snapshots, never a live DB session."""
from typing import Protocol, Any, Literal
from pydantic import BaseModel, ConfigDict, Field, StrictInt, model_validator


class SectionDraft(BaseModel):
    title: str
    content: str
    section_type: str | None = None


class KnowledgeItem(BaseModel):
    id: str
    content: str
    source: str
    metadata: dict = Field(default_factory=dict)
    score: float | None = None


class StructuredEdit(BaseModel):
    """Full raw node values; JSON decoding is the only escaping layer."""
    model_config = ConfigDict(extra='forbid')
    operation: Literal['replace', 'insert_after'] = 'replace'
    block_id: str = Field(min_length=1, max_length=200)
    row_id: str | None = Field(default=None, min_length=1, max_length=200)
    column_index: StrictInt | None = Field(default=None, ge=0)
    item_index: StrictInt | None = Field(default=None, ge=0)
    expected_text: str = Field(max_length=8000)
    text: str = Field(max_length=8000)

    @model_validator(mode='after')
    def valid_address(self):
        if (self.row_id is None) != (self.column_index is None):
            raise ValueError('row_id and column_index must be supplied together')
        if self.row_id is not None and self.item_index is not None:
            raise ValueError('A target cannot be both a cell and a list item')
        if self.operation == 'insert_after' and (self.row_id is not None or self.item_index is not None):
            raise ValueError('Insertions must anchor a paragraph block')
        if self.operation == 'insert_after' and not self.text.strip():
            raise ValueError('An inserted paragraph cannot be empty')
        return self


class SuggestionDraft(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True, extra='forbid')
    issue: str = Field(min_length=1, max_length=4000)
    reason: str = Field(min_length=1, max_length=4000)
    pedagogical_basis: str = Field(min_length=1, max_length=4000)
    revision: str = Field(min_length=1, max_length=8000)
    issue_type: Literal['missing_component', 'objective_measurability', 'learner_analysis',
        'content_accuracy', 'pedagogical_alignment', 'activity_design', 'assessment_alignment',
        'interaction_quality', 'cross_section_consistency', 'resource_design', 'document_quality', 'other'] = 'other'
    scope: Literal['local', 'cross_section', 'global'] = 'local'
    confidence: float | None = Field(default=None, ge=0, le=1)
    revision_mode: Literal['append', 'replace'] = 'append'
    target_text: str | None = None
    edits: list[StructuredEdit] = Field(default_factory=list, max_length=40)
    knowledge_ids: list[str] = Field(default_factory=list)

    @model_validator(mode='after')
    def structured_mode(self):
        if self.edits and self.revision_mode != 'replace':
            raise ValueError('Node edits must use replace mode; append is for independent additions')
        return self


class MemoryContext(BaseModel):
    latest_content: str
    rejected_suggestions: list[dict] = Field(default_factory=list)
    accepted_suggestions: list[dict] = Field(default_factory=list)


class LLMContext(BaseModel):
    system: str
    user: dict[str, Any]


class SectionParser(Protocol):
    def parse(self, lesson_plan_text: str, lesson_metadata: dict | None = None) -> list[SectionDraft]: ...


class SuggestionProvider(Protocol):
    def generate(self, context: LLMContext) -> list[SuggestionDraft]: ...


class KnowledgeRetriever(Protocol):
    def retrieve(self, section: dict, lesson_metadata: dict, query: str | None = None,
                 limit: int | None = None) -> list[KnowledgeItem]: ...


class MemoryProvider(Protocol):
    def build_memory(self, session: dict, round: dict, section: dict) -> MemoryContext: ...


class ContextBuilder(Protocol):
    def build(self, section: dict, lesson_metadata: dict, memory_context: MemoryContext,
              retrieved_knowledge: list[KnowledgeItem], custom_prompt: str | None = None) -> LLMContext: ...


class LLMClient(Protocol):
    def complete(self, context: LLMContext) -> dict: ...


class InquiryProvider(Protocol):
    def inquire(self, session: dict, section: dict, prompt_text: str, context: LLMContext) -> list[SuggestionDraft]: ...


class RevisionStrategy(Protocol):
    def apply(self, section_content: str, suggestion: SuggestionDraft) -> 'RevisionResult': ...


class RevisionResult(BaseModel):
    status: Literal['applied', 'candidate']
    content: str
    candidate: str | None = None
    reason: str | None = None
