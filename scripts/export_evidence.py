"""Export a compact portable index of the retained REA records in .tools/."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent
names = [
    'package-evidence.json', 'qcomment-issub-evidence.json', 'user-sex-evidence.json',
    'common-refs-evidence.json', 'kmp-page-evidence.json', 'kmp-i1-evidence.json',
    'kmp-w1-evidence.json', 'kmp-completion-evidence.json', 'comment-footer-add-evidence.json',
]
records = []
for name in names:
    path = root / '.tools' / name
    record = json.loads(path.read_text(encoding='utf-8-sig'))
    if not record.get('evidence_id'):
        raise ValueError(f'Not a successful evidence record: {name}')
    normalized = record['normalized_result']
    entry = {
        'record': '.tools/' + name,
        'record_sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
        'evidence_id': record['evidence_id'],
        'operation': record['operation'],
        'subject_sha256': record['subject']['digest']['sha256'],
        'class_name': normalized.get('class_name'),
        'method': normalized.get('method'),
        'limitations': record.get('limitations', []),
    }
    if record['operation'] == 'inspect_android_package':
        entry['identity'] = {key: normalized.get(key) for key in
                             ['package_name', 'version_name', 'version_code', 'min_sdk', 'target_sdk']}
    source = normalized.get('source')
    if source:
        entry['source_status'] = source.get('status')
        entry['body_status'] = normalized.get('body_status')
        entry['source_sha256'] = hashlib.sha256(source['text'].encode()).hexdigest()
        entry['source_excerpt'] = [line.strip() for line in source['text'].splitlines()
                                   if any(term in line for term in ['getKRootComments', 'getKSubCommentMap',
                                       'setKType', 'getKType', 'getKLocalComments', 'setKLocalFullList',
                                       'mParent', '"M".equals', 't(response.a())', 'u(false)', 's(response.a()', 'e2(view, null)'])]
    records.append(entry)
(root / 'docs' / 'apk-evidence.json').write_text(json.dumps({
    'analysis_date': '2026-10-28', 'timezone': 'Asia/Shanghai', 'kind': 'static-analysis-index',
    'note': 'This index retains REA evidence IDs; full local records remain in ignored .tools/. No runtime validation.',
    'records': records,
}, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
