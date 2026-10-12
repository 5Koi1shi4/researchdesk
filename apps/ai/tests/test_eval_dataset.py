import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]

def test_fixed_dataset_split_and_sources():
    dataset_path = ROOT / 'evals/dataset.jsonl'
    assert dataset_path.exists(), '60-question dataset is missing'
    rows = [json.loads(line) for line in dataset_path.read_text(encoding='utf-8').splitlines()]
    assert len(rows) == 60
    assert len({r['id'] for r in rows}) == 60
    assert Counter(r['category'] for r in rows) == {'fact':20,'comparison':15,'insufficient':10,'conflict':5,'adversarial':10}
    split = json.loads((ROOT / 'evals/split.json').read_text(encoding='utf-8'))
    assert len(split['dev']) == 40 and len(split['holdout']) == 20
    assert not set(split['dev']) & set(split['holdout'])
    assert set(split['dev'] + split['holdout']) == {r['id'] for r in rows}
    chunks = {r['chunk_id']: r for r in json.loads((ROOT / 'evals/fixtures/chunks.json').read_text(encoding='utf-8'))}
    for chunk in chunks.values():
        assert chunk['text'] in (ROOT / 'evals/fixtures' / chunk['source']).read_text(encoding='utf-8')
    for row in rows:
        assert row['question'] and row['expected_behavior'] and row['expected_facts']
        assert row['allowed_version_ids']
        for source in row['gold_chunk_ids']:
            chunk = chunks[source]
            assert chunk['document_version_id'] in row['allowed_version_ids']
            assert chunk['project_id'] == row['project_id']
            assert chunk['text'] in (ROOT / 'evals/fixtures' / chunk['source']).read_text(encoding='utf-8')
