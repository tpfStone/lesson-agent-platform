"""Ordered LessonDocument adapter. Authoritative cells, never parallel editable semantics."""
from copy import deepcopy
import hashlib
import json
import re
from uuid import uuid4
from .providers.interfaces import SectionDraft
from .providers.defaults import DefaultRevisionStrategy
from .chapter_sections import heading, main_headings


def normalize(value):
    return str(value).replace('\r\n', '\n').replace('\r', '\n').replace('\x00', '').strip()


def digest(document):
    return hashlib.sha256(json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def _block_projection(block, schema_version):
    if block.get('tableRows'):
        return '\n'.join(' | '.join(str(c).strip() if schema_version == '1.0' else
            str(c).strip().replace('\r\n', '\n').replace('\r', '\n').replace('\\', '\\\\').replace('|', '\\|').replace('\n', '\\n')
            for c in row) for row in block['tableRows'])
    if block.get('items'):
        return '\n'.join((f'{i + 1}. ' if block.get('ordered') else '• ') + item
                         for i, item in enumerate(block['items']))
    if block.get('text'):
        return block['text'].replace('\r\n', '\n').replace('\r', '\n').strip()
    return None


def project(document):
    parts = [_block_projection(block, document.get('schemaVersion')) for block in document['blocks']]
    return '\n\n'.join(part for part in parts if part is not None).strip()


def validate(document):
    if document.get('schemaVersion') not in ('1.0', '1.1'):
        raise ValueError('UNSUPPORTED_SCHEMA')
    blocks = document.get('blocks')
    if not isinstance(blocks, list) or len(blocks) > 5000:
        raise ValueError('INVALID_BLOCKS')
    ids, row_ids = set(), set()
    for block in blocks:
        if not isinstance(block, dict):
            raise ValueError('INVALID_BLOCK')
        key = block.get('id')
        if not isinstance(key, str) or not key or key in ids: raise ValueError('DUPLICATE_OR_EMPTY_BLOCK_ID')
        ids.add(key)
        if block.get('text') is not None and not isinstance(block['text'], str):
            raise ValueError('INVALID_TEXT')
        if block.get('items') is not None and (not isinstance(block['items'], list)
                or any(not isinstance(item, str) for item in block['items'])):
            raise ValueError('INVALID_ITEMS')
        if block.get('tableRows') is not None and not isinstance(block['tableRows'], list):
            raise ValueError('INVALID_TABLE')
        rows = block.get('tableRows') or []
        if rows and (len(rows) > 5000 or any(not isinstance(row, list) or len(row) > 100
                or any(not isinstance(cell, str) for cell in row) for row in rows)):
            raise ValueError('INVALID_TABLE')
        if block.get('rowIds') is not None and not isinstance(block['rowIds'], list):
            raise ValueError('INVALID_ROW_IDS')
        rids = block.get('rowIds') or []
        if rows and document.get('schemaVersion') == '1.1' and not rids: raise ValueError('ROW_IDS_REQUIRED')
        if rids and len(rids) != len(rows): raise ValueError('ROW_ID_COUNT_MISMATCH')
        for rid in rids:
            if not isinstance(rid, str) or not rid or rid in row_ids: raise ValueError('DUPLICATE_ROW_ID')
            row_ids.add(rid)
        if block.get('columnBindings') is not None and not isinstance(block['columnBindings'], dict):
            raise ValueError('INVALID_COLUMN_BINDING')
        for index in (block.get('columnBindings') or {}).values():
            if type(index) is not int or index < 0 or any(index >= len(row) for row in rows):
                raise ValueError('INVALID_COLUMN_BINDING')
        teaching = block.get('teachingProcess')
        if teaching is not None and (not isinstance(teaching, dict)
                or not isinstance(teaching.get('rows', []), list)):
            raise ValueError('INVALID_TEACHING_PROCESS')
        used = set()
        for row in (block.get('teachingProcess') or {}).get('rows', []):
            if not isinstance(row, dict):
                raise ValueError('INVALID_SOURCE_ROW')
            index = row.get('sourceRowIndex')
            if type(index) is not int or index < 0 or index >= len(rows) or index in used:
                raise ValueError('INVALID_SOURCE_ROW')
            used.add(index)
            if rids and row.get('rowId') != rids[index]: raise ValueError('ROW_REFERENCE_MISMATCH')
            for field in ('teacherActivity', 'studentActivity', 'evaluation'):
                column = (block.get('columnBindings') or {}).get(field)
                if column is not None and row.get(field, '') != normalize(rows[index][column]):
                    raise ValueError('INCONSISTENT_TEACHING_ROWS')
    if len(project(document)) > 100000: raise ValueError('DOCUMENT_TOO_LARGE')


def sections(document, content):
    validate(document)
    if project(document) != content: raise ValueError('STRUCTURE_CONTENT_MISMATCH')
    explicit, inferred = [], []
    blocks = document['blocks']
    for index, block in enumerate(blocks):
        is_heading = block.get('type') == 'heading'
        value = heading(block.get('text') or '', (block.get('level') or 1) if is_heading else None)
        if value:
            (explicit if is_heading or re.match(r'^#{1,6}\s+', block.get('text') or '') else inferred).append((index, value))
        elif block.get('columnBindings') or (block.get('teachingProcess') or {}).get('rows'):
            parents = main_headings(inferred, inferred=True)
            parent = parents[-1][1] if parents else None
            if not parent or (parent[1] != 'teaching_process' and
                    not re.match(r'^(?:[一二三四五六七八九十百]+|\d+)[、.．]', parent[0])):
                inferred.append((index, ('教学过程', 'teaching_process', 1)))
    starts = main_headings(explicit or inferred, inferred=not explicit)
    if not starts:
        starts = [(0, ('完整教案', None, 1))]
    return [(SectionDraft(title=value[0], section_type=value[1],
                         content=project({**document, 'blocks': group})),
             {'blockIds': [block['id'] for block in group]})
            for i, (start, value) in enumerate(starts)
            if (group := blocks[start:starts[i + 1][0] if i + 1 < len(starts) else len(blocks)])]


def chapter_blocks(document, source_ref):
    ids = source_ref['blockIds']
    positions = {block['id']: i for i, block in enumerate(document['blocks'])}
    if not ids or any(key not in positions for key in ids):
        raise ValueError('INVALID_CHAPTER_REFERENCE')
    start = positions[ids[0]]
    blocks = document['blocks'][start:start + len(ids)]
    if [block['id'] for block in blocks] != ids:
        raise ValueError('INVALID_CHAPTER_REFERENCE')
    return blocks


def chapter_content(document, source_ref):
    return project({**document, 'blocks': chapter_blocks(document, source_ref)})


def _chapter_leaves(document, blocks):
    """Yield exact projection spans and raw-text offsets, including escaped table cells."""
    raw_projection = '\n\n'.join(part for block in blocks
        if (part := _block_projection(block, document.get('schemaVersion'))) is not None)
    offset = -(len(raw_projection) - len(raw_projection.lstrip()))
    for block in blocks:
        block_text = _block_projection(block, document.get('schemaVersion'))
        if block_text is None:
            continue
        local = 0
        if block.get('tableRows'):
            for r, row in enumerate(block['tableRows']):
                if r: local += 1
                for c, raw in enumerate(row):
                    if c: local += 3
                    raw = raw.strip()
                    if document.get('schemaVersion') != '1.0':
                        raw = raw.replace('\r\n', '\n').replace('\r', '\n')
                    boundaries, length = {0: 0}, 0
                    for i, char in enumerate(raw):
                        length += 2 if document.get('schemaVersion') != '1.0' and char in '\\|\n' else 1
                        boundaries[length] = i + 1
                    yield block, ('cell', r, c), offset + local, raw, boundaries
                    local += length
        elif block.get('items'):
            for i, raw in enumerate(block['items']):
                if i: local += 1
                local += len(f'{i + 1}. ' if block.get('ordered') else '• ')
                yield block, ('item', i), offset + local, raw, {j: j for j in range(len(raw) + 1)}
                local += len(raw)
        elif block.get('text'):
            raw = block_text
            yield block, ('text',), offset, raw, {j: j for j in range(len(raw) + 1)}
        offset += len(block_text) + 2


def _revise_chapter(document, source_ref, previous, suggestion):
    if chapter_content(document, source_ref) != previous:
        raise ValueError('STALE_CHAPTER_CONTENT')
    updated = deepcopy(document)
    blocks = chapter_blocks(updated, source_ref)
    if suggestion.revision_mode == 'append':
        added = {'id': 'f4-' + uuid4().hex, 'type': 'paragraph', 'text': suggestion.revision.strip()}
        index = updated['blocks'].index(blocks[-1]) + 1
        updated['blocks'].insert(index, added)
        validate(updated)
        source_ref['blockIds'] = [*source_ref['blockIds'], added['id']]
        return updated
    target = suggestion.target_text
    if not target or previous.count(target) != 1:
        raise ValueError('UNMAPPABLE_SECTION_EDIT')
    start, end = previous.index(target), previous.index(target) + len(target)
    for block, path, offset, raw, boundaries in _chapter_leaves(updated, blocks):
        if start - offset not in boundaries or end - offset not in boundaries:
            continue
        value = raw[:boundaries[start - offset]] + suggestion.revision + raw[boundaries[end - offset]:]
        if path[0] == 'cell':
            _, r, c = path
            block['tableRows'][r][c] = value
            _refresh_teaching_row(block, r)
        elif path[0] == 'item':
            block['items'][path[1]] = value
        else:
            block['text'] = value
        validate(updated)
        return updated
    raise ValueError('UNMAPPABLE_SECTION_EDIT')


def _refresh_teaching_row(block, index):
    values = block['tableRows'][index]
    bindings = block.get('columnBindings') or {}
    if block.get('teachingProcess') and not bindings:
        raise ValueError('LEGACY_BINDINGS_UNAVAILABLE')
    for ordinal, row in enumerate((block.get('teachingProcess') or {}).get('rows', []), 1):
        if row['sourceRowIndex'] != index: continue
        for field in ('teacherActivity', 'studentActivity', 'evaluation'):
            if field in bindings: row[field] = normalize(values[bindings[field]])
        if not any(row.get(field, '') for field in ('teacherActivity', 'studentActivity', 'evaluation')):
            raise ValueError('EDIT_REMOVES_TEACHING_ROW')
        title = normalize(values[bindings.get('title', bindings.get('teacherActivity', 0))])
        duration_source = normalize(values[bindings['durationMinutes']]) if 'durationMinutes' in bindings else title
        if re.fullmatch(r'[0-9]{1,3}', duration_source): duration_source += '分钟'
        match = re.search(r'(?:约\s*)?(\d{1,3})\s*(?:分钟|min(?:ute)?s?)', duration_source, re.I)
        row['durationMinutes'] = int(match[1]) if match else None
        title = title.split('\n')[0].split(' / ')[0]
        title = re.sub(r'[（(]?\s*(?:约\s*)?\d{1,3}\s*(?:分钟|min(?:ute)?s?)[）)]?', '', title, flags=re.I)
        title = re.sub(r'^\s*\d{1,3}[.．、)）]\s*', '', title)
        title = re.sub(r'[\s/|：:\-—]+$', '', title).strip()
        # Java String.length counts UTF-16 code units.
        row['title'] = title if title and len(title.encode('utf-16-le')) // 2 <= 60 else f'教学环节 {ordinal}'
        row['extraCells'] = [v for i, v in enumerate(values) if i not in bindings.values()]


def revise(document, source_ref, previous, content, suggestion=None):
    if source_ref.get('blockIds'):
        if suggestion is None:
            raise ValueError('CHAPTER_REVISION_REQUIRES_TARGET')
        # Honor the configured strategy's effective edit, including append-only experiments.
        if content == DefaultRevisionStrategy().apply(previous, suggestion):
            suggestion = suggestion.model_copy(update={'revision_mode': 'append'})
        elif (suggestion.revision_mode != 'replace' or not suggestion.target_text
                or previous.count(suggestion.target_text) != 1
                or content != previous.replace(suggestion.target_text, suggestion.revision, 1)):
            raise ValueError('UNMAPPABLE_SECTION_EDIT')
        return _revise_chapter(document, source_ref, previous, suggestion)
    updated = deepcopy(document)
    block = next(b for b in updated['blocks'] if b['id'] == source_ref['blockId'])
    if source_ref.get('rowId'):
        index = source_ref['sourceRowIndex']
        cells = block['tableRows'][index]
        pattern = r'(?m)^\[(\d+)\] '
        chunks = re.split(pattern, content)
        if chunks[0] or chunks[1::2] != [str(i + 1) for i in range(len(cells))]:
            raise ValueError('UNMAPPABLE_SECTION_EDIT')
        values = [c.rstrip('\n') for c in chunks[2::2]]
        block['tableRows'][index] = values
        _refresh_teaching_row(block, index)
    elif block.get('tableRows') or block.get('items'):
        raise ValueError('UNMAPPABLE_SECTION_EDIT')
    else:
        block['text'] = content
    validate(updated)
    return updated


def fallback_edit(state, document, source_ref, content):
    """Keep all original blocks/rows as a text projection, including unreviewed cells.

    The retained source is internal provenance, never returned as usable structure.
    Overrides are keyed by source location, so repeated text cannot target a wrong row.
    """
    state = deepcopy(state) if state else {'document': deepcopy(document), 'edits': {}}
    key = f"{source_ref['blockId']}:{source_ref.get('sourceRowIndex', 'block')}"
    state['edits'][key] = content
    return state


def project_fallback(state):
    parts = []
    for block in state['document']['blocks']:
        key = block['id']
        edits = state['edits']
        if f'{key}:block' in edits:
            parts.append(edits[f'{key}:block'])
        elif block.get('tableRows'):
            parts.append('\n'.join(edits.get(f'{key}:{index}', project({'schemaVersion': state['document'].get('schemaVersion'), 'blocks': [
                {'tableRows': [row]}]})) for index, row in enumerate(block['tableRows'])))
        else:
            parts.append(project({'schemaVersion': state['document'].get('schemaVersion'), 'blocks': [block]}))
    return '\n\n'.join(part for part in parts if part).strip()
