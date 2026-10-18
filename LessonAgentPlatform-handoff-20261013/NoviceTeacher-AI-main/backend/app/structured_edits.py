"""Atomic node edits and conservative compatibility for chapter projections.

Only the legacy full-row path decodes projection escapes. Typed node text is raw.
"""
from copy import deepcopy
import re
from uuid import uuid4


def editable_nodes(document, source_ref):
    from . import structured_adapter as adapter

    adapter.validate(document)
    nodes = []
    for block in adapter.chapter_blocks(document, source_ref):
        base = {'block_id': block['id']}
        if block.get('tableRows'):
            # Old tables without stable row identities remain on the legacy path.
            for row_id, row in zip(block.get('rowIds') or [], block['tableRows']):
                for column, text in enumerate(row):
                    nodes.append({**base, 'kind': 'cell', 'row_id': row_id,
                                  'column_index': column, 'text': text})
        elif block.get('items'):
            nodes.extend({**base, 'kind': 'item', 'item_index': index, 'text': text}
                         for index, text in enumerate(block['items']))
        elif isinstance(block.get('text'), str):
            nodes.append({**base, 'kind': block.get('type', 'paragraph'), 'text': block['text']})
    return nodes


def _key(edit):
    return tuple(edit.get(field) for field in ('block_id', 'row_id', 'column_index', 'item_index'))


def apply_edits(document, source_ref, edits):
    """Validate against one snapshot, copy/apply, then publish chapter IDs on success."""
    from . import structured_adapter as adapter

    if not isinstance(edits, list) or not edits or len(edits) > 40:
        raise ValueError('INVALID_STRUCTURED_EDITS')
    nodes = {_key(node): node for node in editable_nodes(document, source_ref)}
    chapter = {block['id']: block for block in adapter.chapter_blocks(document, source_ref)}
    document_ids = {block['id'] for block in document['blocks']}
    checked, replaced = [], set()
    for value in edits:
        edit = value.model_dump() if hasattr(value, 'model_dump') else value
        if (not isinstance(edit, dict) or not isinstance(edit.get('block_id'), str)
                or not isinstance(edit.get('expected_text'), str) or not isinstance(edit.get('text'), str)):
            raise ValueError('INVALID_STRUCTURED_EDIT')
        block_id = edit['block_id']
        if block_id not in chapter:
            raise ValueError('EDIT_OUTSIDE_CHAPTER' if block_id in document_ids else 'INVALID_EDIT_TARGET')
        block = chapter[block_id]
        operation = edit.get('operation', 'replace')
        if operation not in ('replace', 'insert_after'):
            raise ValueError('INVALID_STRUCTURED_EDIT')
        if operation == 'insert_after' and (block.get('type') != 'paragraph'
                or block.get('tableRows') or block.get('items')
                or any(edit.get(field) is not None for field in ('row_id', 'column_index', 'item_index'))
                or not edit['text'].strip()):
            raise ValueError('INVALID_INSERTION_ANCHOR')
        for field in ('column_index', 'item_index'):
            index = edit.get(field)
            if index is not None and (type(index) is not int or index < 0):
                raise ValueError('INVALID_EDIT_TARGET')
        key = _key(edit)
        node = nodes.get(key)
        if node is None:
            raise ValueError('INVALID_EDIT_TARGET')
        if edit['expected_text'] != node['text']:
            raise ValueError('STALE_STRUCTURED_EDIT')
        if operation == 'replace':
            if key in replaced:
                raise ValueError('OVERLAPPING_STRUCTURED_EDITS')
            replaced.add(key)
            if node['kind'] == 'cell' and looks_like_table_row(edit['text'], block, raw_node=True):
                raise ValueError('SERIALIZED_ROW_IN_CELL')
        elif any(candidate.get('tableRows') and looks_like_table_row(edit['text'], candidate, raw_node=True)
                 for candidate in chapter.values()):
            raise ValueError('TABLE_EDIT_REQUIRES_TARGET')
        checked.append((edit, node, operation))

    updated = deepcopy(document)
    blocks = {block['id']: block for block in updated['blocks']}
    insertions, changed_rows = {}, set()
    for edit, node, operation in checked:
        block = blocks[edit['block_id']]
        if operation == 'insert_after':
            insertions.setdefault(block['id'], []).append(
                {'id': 'f4-' + uuid4().hex, 'type': 'paragraph', 'text': edit['text']})
        elif node['kind'] == 'cell':
            row_index = block['rowIds'].index(node['row_id'])
            block['tableRows'][row_index][node['column_index']] = edit['text']
            changed_rows.add((block['id'], row_index))
        elif node['kind'] == 'item':
            block['items'][node['item_index']] = edit['text']
        else:
            block['text'] = edit['text']
    for block_id, row_index in changed_rows:
        adapter._refresh_teaching_row(blocks[block_id], row_index)
    updated['blocks'] = [entry for block in updated['blocks']
                         for entry in [block, *insertions.get(block['id'], [])]]
    ids = [entry for block_id in source_ref['blockIds']
           for entry in [block_id, *(block['id'] for block in insertions.get(block_id, []))]]
    adapter.validate(updated)
    source_ref['blockIds'] = ids
    return updated


def _projection_cells(text):
    """Split only unescaped projection separators, decoding known escapes once."""
    cells, value, index = [], [], 0
    escapes = {'\\': '\\', '|': '|', 'n': '\n'}
    while index < len(text):
        if text[index] == '\\' and index + 1 < len(text) and text[index + 1] in escapes:
            value.append(escapes[text[index + 1]])
            index += 2
        elif text.startswith(' | ', index):
            cells.append(''.join(value).strip())
            value = []
            index += 3
        else:
            value.append(text[index])
            index += 1
    cells.append(''.join(value).strip())
    return cells


def looks_like_table_row(text, block, *, raw_node=False):
    """Recognize a serialized row, without treating an ordinary pipe as a separator."""
    title_column = (block.get('columnBindings') or {}).get('title', 0)
    labels = {row[title_column].strip() for row in block.get('tableRows') or []
              if title_column < len(row)}
    for line in [text, *text.splitlines()]:
        cells = _projection_cells(line)
        if len(cells) > title_column and len(cells) > 1 and cells[title_column] in labels:
            return True
        # A revised title/duration still identifies an ordinal teaching row,
        # even with missing or extra columns. Ordinary pipe-separated prose
        # without a row label remains raw node text.
        if (raw_node and len(cells) > max(title_column, 1)
                and re.match(r'^\d+[.．、)]\s*\S', cells[title_column])):
            return True
        if not raw_node and (len(cells) >= 3 or (len(cells) > 1 and re.match(r'^\d+[.．、)]', cells[0]))):
            return True
    return False


def legacy_row_replacement(document, blocks, start, end, revision):
    """Return (block, row index, decoded cells) only for an exact same-row anchor."""
    from . import structured_adapter as adapter

    grouped = {}
    for block, path, offset, raw, boundaries in adapter._chapter_leaves(document, blocks):
        if path[0] == 'cell':
            grouped.setdefault((block['id'], path[1]), []).append((block, offset, boundaries))
    for (_, row_index), leaves in grouped.items():
        block = leaves[0][0]
        # The start and end must be text boundaries of cells belonging to this row.
        if not (any(start - offset in boundaries for _, offset, boundaries in leaves)
                and any(end - offset in boundaries for _, offset, boundaries in leaves)):
            continue
        if not looks_like_table_row(revision, block):
            return None
        # Version 1.0 projections did not escape text. A literal path such as
        # C:\new is indistinguishable from an encoded newline in a row rewrite.
        if document.get('schemaVersion') != '1.1':
            raise ValueError('LEGACY_ROW_ENCODING_AMBIGUOUS')
        row = block['tableRows'][row_index]
        values = _projection_cells(revision)
        title_column = (block.get('columnBindings') or {}).get('title', 0)
        # Canonical round-trip disallows physical row separators and ambiguous escapes.
        projected = adapter._block_projection({'tableRows': [values]}, '1.1')
        if (len(values) != len(row)
                or title_column >= len(row) or values[title_column] != row[title_column].strip()
                or projected != revision.strip()):
            raise ValueError('INVALID_ROW_REPLACEMENT')
        return block, row_index, values
    return None


def legacy_paragraph_replacement(document, source_ref, blocks, start, end, revision):
    """Replace only complete, contiguous paragraph blocks; never flatten other types."""
    from . import structured_adapter as adapter

    spans = {block['id']: (offset, offset + len(raw))
             for block, path, offset, raw, _ in adapter._chapter_leaves(document, blocks)
             if path == ('text',) and block.get('type') == 'paragraph'}
    first = next((index for index, block in enumerate(blocks)
                  if spans.get(block['id'], (None, None))[0] == start), None)
    last = next((index for index, block in enumerate(blocks)
                 if spans.get(block['id'], (None, None))[1] == end), None)
    if first is None or last is None or first > last:
        return False
    selected = blocks[first:last + 1]
    if any(block['id'] not in spans for block in selected):
        return False
    paragraphs = [text.strip() for text in re.split(r'\n[ \t]*\n', revision) if text.strip()]
    if not paragraphs:
        raise ValueError('UNMAPPABLE_SECTION_EDIT')
    # A single unchanged paragraph shape belongs to the ordinary exact-node path.
    if len(selected) == 1 and len(paragraphs) == 1:
        return False
    replacement = []
    for index, text in enumerate(paragraphs):
        block = deepcopy(selected[index]) if index < len(selected) else {
            'id': 'f4-' + uuid4().hex, 'type': 'paragraph'}
        block['text'] = text
        replacement.append(block)
    position = document['blocks'].index(selected[0])
    document['blocks'][position:position + len(selected)] = replacement
    new_ids = [*source_ref['blockIds'][:first], *(block['id'] for block in replacement),
               *source_ref['blockIds'][last + 1:]]
    adapter.validate(document)
    source_ref['blockIds'] = new_ids
    return True
