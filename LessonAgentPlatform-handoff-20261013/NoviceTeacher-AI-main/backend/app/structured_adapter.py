"""Ordered LessonDocument adapter. Authoritative cells, never parallel editable semantics."""
from copy import deepcopy
import hashlib
import json
import re
from .providers.interfaces import SectionDraft


def normalize(value):
    return str(value).replace('\r\n', '\n').replace('\r', '\n').replace('\x00', '').strip()


def digest(document):
    return hashlib.sha256(json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def project(document):
    parts = []
    for block in document['blocks']:
        if block.get('tableRows'):
            parts.append('\n'.join(' | '.join(str(c).strip() if document.get('schemaVersion') == '1.0' else
                                   str(c).strip().replace('\r\n', '\n').replace('\r', '\n').replace('\\', '\\\\').replace('|', '\\|').replace('\n', '\\n') for c in row)
                                   for row in block['tableRows']))
        elif block.get('items'):
            parts.append('\n'.join((f'{i + 1}. ' if block.get('ordered') else '• ') + item
                                   for i, item in enumerate(block['items'])))
        elif block.get('text'):
            parts.append(block['text'].replace('\r\n', '\n').replace('\r', '\n').strip())
    return '\n\n'.join(parts).strip()


def validate(document):
    if document.get('schemaVersion') not in ('1.0', '1.1'):
        raise ValueError('UNSUPPORTED_SCHEMA')
    blocks = document.get('blocks')
    if not isinstance(blocks, list) or len(blocks) > 5000:
        raise ValueError('INVALID_BLOCKS')
    ids, row_ids = set(), set()
    for block in blocks:
        key = block.get('id')
        if not key or key in ids: raise ValueError('DUPLICATE_OR_EMPTY_BLOCK_ID')
        ids.add(key)
        rows = block.get('tableRows') or []
        if rows and (len(rows) > 5000 or any(not isinstance(row, list) or len(row) > 100 for row in rows)):
            raise ValueError('INVALID_TABLE')
        rids = block.get('rowIds') or []
        if rows and document.get('schemaVersion') == '1.1' and not rids: raise ValueError('ROW_IDS_REQUIRED')
        if rids and len(rids) != len(rows): raise ValueError('ROW_ID_COUNT_MISMATCH')
        for rid in rids:
            if not rid or rid in row_ids: raise ValueError('DUPLICATE_ROW_ID')
            row_ids.add(rid)
        for index in (block.get('columnBindings') or {}).values():
            if not isinstance(index, int) or index < 0 or any(index >= len(row) for row in rows):
                raise ValueError('INVALID_COLUMN_BINDING')
        used = set()
        for row in (block.get('teachingProcess') or {}).get('rows', []):
            index = row.get('sourceRowIndex')
            if not isinstance(index, int) or index < 0 or index >= len(rows) or index in used:
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
    result = []
    for block in document['blocks']:
        rows = (block.get('teachingProcess') or {}).get('rows')
        # A reliable binding can identify classroom rows without a separate semantic view.
        indices = [r['sourceRowIndex'] for r in rows] if rows else (
            list(range(1, len(block.get('tableRows') or []))) if block.get('columnBindings') else [])
        if indices:
            for index in indices:
                cells = block['tableRows'][index]
                if not any(str(c).strip() for c in cells): continue
                row_id = (block.get('rowIds') or [f"{block['id']}:{i}" for i in range(len(block['tableRows']))])[index]
                title_index = (block.get('columnBindings') or {}).get('title', 0)
                result.append((SectionDraft(title=cells[title_index][:100] or '教学环节',
                    content='\n'.join(f'[{i + 1}] {c}' for i, c in enumerate(cells)), section_type='teaching_process'),
                    {'blockId': block['id'], 'rowId': row_id, 'sourceRowIndex': index}))
        else:
            text = project({'blocks': [block]})
            if text:
                result.append((SectionDraft(title=(block.get('text') or block['type'])[:100], content=text,
                                            section_type=block['type']), {'blockId': block['id']}))
    return result


def revise(document, source_ref, previous, content):
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
