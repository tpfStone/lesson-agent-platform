"""Conservative main-heading detection shared by structured and plain-text input."""
import re
from .providers.defaults import DefaultSectionParser
from .providers.interfaces import SectionDraft


NAMES = {**DefaultSectionParser.names,
    '教材与内容分析': 'content_analysis', '教材分析': 'content_analysis',
    '教学重点与难点': 'key_difficulties', '教学资源': 'preparation',
    '形成性评价设计': 'assessment', '作业设计': 'homework', '教学后反思预设': 'reflection',
    '教学反思': 'reflection'}


def heading(text, level=None):
    title = text.strip()
    markdown = re.match(r'^(#{1,6})\s+(.+)$', title)
    if markdown:
        level, title = len(markdown[1]), markdown[2].strip()
    if not title or '\n' in title or len(title) > 100:
        return None
    # A document title is context, including when Word marks it as Heading 1.
    if re.search(r'(?:教学设计|教案|教学方案)$', title) and title not in NAMES:
        return None
    cleaned = re.sub(r'^(?:[一二三四五六七八九十百]+[、.．]|\d+[、.．]|[（(][一二三四五六七八九十\d]+[）)])\s*', '', title)
    label = re.split(r'[：:]', cleaned.strip('*【】 ：:'), maxsplit=1)[0].strip()
    kind = NAMES.get(label.lower())
    if level is not None:
        return title, kind, level if type(level) is int and level > 0 else 1
    # Chinese chapter numbering is distinct from ordinary numbered exercises.
    if re.match(r'^[一二三四五六七八九十百]+[、.．]', title) and not re.search(r'[。！？；]', title):
        return title, kind, 1
    if kind:
        depth = 2 if re.match(r'^[（(]', title) else 1
        return title.split('：', 1)[0].split(':', 1)[0], kind, depth
    return None


def main_headings(candidates, inferred=False):
    """Keep the shallowest body level; nested headings remain inside their parent."""
    if not candidates:
        return []
    if inferred and any(value[1] == 'teaching_process' for _, value in candidates):
        steps = {'introduction', 'exploration', 'practice', 'summary'}
        candidates = [(index, (title, kind, 2 if kind in steps and
            not re.match(r'^(?:[一二三四五六七八九十百]+|\d+)[、.．]', title) else depth))
            for index, (title, kind, depth) in candidates]
    depth = min(value[2] for _, value in candidates)
    roots = [(index, value) for index, value in candidates if value[2] == depth]
    # One unclassified H1 at the start, above several sibling chapters, is the document title.
    if not inferred and len(roots) == 1 and roots[0] == candidates[0] and roots[0][1][1] is None:
        children = [(index, value) for index, value in candidates if value[2] > depth]
        if children:
            child_depth = min(value[2] for _, value in children)
            siblings = [(index, value) for index, value in children if value[2] == child_depth]
            if len(siblings) > 1:
                return siblings
    return roots


class ChapterSectionParser:
    def parse(self, text, lesson_metadata=None):
        if not text.strip():
            raise ValueError('教案不能为空。')
        explicit, inferred = [], []
        offset = 0
        for line in text.splitlines(keepends=True):
            value = heading(line)
            if value:
                (explicit if re.match(r'^\s*#{1,6}\s+', line) else inferred).append((offset, value))
            offset += len(line)
        starts = main_headings(explicit or inferred, inferred=not explicit)
        if not starts:
            return [SectionDraft(title='完整教案', content=text)]
        if starts[0][0] > 0:
            starts.insert(0, (0, ('教案概述', None, 1)))
        return [SectionDraft(title=value[0], section_type=value[1],
            content=text[start:starts[i + 1][0] if i + 1 < len(starts) else len(text)])
            for i, (start, value) in enumerate(starts)]
