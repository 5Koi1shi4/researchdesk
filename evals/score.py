"""Frozen metric definitions. Human annotations are never inferred from valid IDs."""
from math import ceil, isfinite
from statistics import mean


def score(rows: list[dict]) -> dict:
    recalls, citations, coverage, refusals, completion, latency, usage = [], [], [], [], [], [], []
    for row in rows:
        gold = set(row['gold_chunk_ids'])
        if gold:
            recalls.append(len(gold & set(row['retrieved_chunk_ids'][:5])) / len(gold))
        for citation in row.get('citations', []):
            if type(citation.get('supported')) is not bool or type(citation.get('exists')) is not bool:
                raise ValueError('citation review requires boolean exists and supported')
            citations.append(citation['exists'] and citation['supported'])
        if 'key_claims' in row:
            count, supported = row['key_claims'], row.get('supported_cited_claims')
            if type(count) is not int or type(supported) is not int or not 0 <= supported <= count:
                raise ValueError('invalid reviewed claim counts')
            if count:
                coverage.append(supported / count)
        if row.get('expected_refusal') and 'correct_refusal' in row:
            if type(row['correct_refusal']) is not bool:
                raise ValueError('refusal review requires boolean')
            refusals.append(row['correct_refusal'])
        if 'task_completion' in row:
            value = row['task_completion']
            if type(value) not in (int, float) or value not in (0, 0.5, 1):
                raise ValueError('task completion must be 0, 0.5 or 1')
            completion.append(value)
        value = row['latency_ms']
        if type(value) not in (int, float) or not isfinite(value) or value < 0:
            raise ValueError('invalid latency')
        latency.append(value)
        token_count = (row.get('usage') or {}).get('total_tokens')
        if token_count is not None and (type(token_count) is not int or token_count < 0):
            raise ValueError('invalid actual token usage')
        usage.append(token_count)
    return dict(
        recall_at_5=mean(recalls) if recalls else None,
        citation_support_accuracy=mean(citations) if citations else None,
        key_claim_citation_coverage=mean(coverage) if coverage else None,
        refusal_accuracy=mean(refusals) if refusals else None,
        task_completion=mean(completion) if completion else None,
        latency_ms_mean=mean(latency) if latency else None,
        latency_ms_p95=sorted(latency)[ceil(len(latency)*0.95)-1] if latency else None,
        total_tokens=sum(usage) if usage and all(x is not None for x in usage) else None,
        sample_counts=dict(questions=len(rows),recall=len(recalls),citations=len(citations),coverage=len(coverage),refusal=len(refusals),completion=len(completion)),
    )


def review_results(report: dict, annotation: dict, run_sha256: str) -> dict:
    """Import complete human review bound to the exact serialized run artifact."""
    if annotation.get('run_sha256') != run_sha256 or not annotation.get('reviewer'):
        raise ValueError('review must identify reviewer and match exact run hash')
    reviews=annotation.get('reviews',[])
    by_id={r['id']:r for r in reviews}
    rows=report['results']
    if len(by_id)!=len(reviews) or set(by_id)!={r['id'] for r in rows}:
        raise ValueError('review must cover each run question exactly once')
    import re
    reviewed=[]
    permitted={'id','citations','key_claims','supported_cited_claims','correct_refusal','task_completion','notes'}
    for row in rows:
        review=by_id[row['id']]
        required={'citations','key_claims','supported_cited_claims','task_completion'}
        if row.get('expected_refusal'):
            required.add('correct_refusal')
        if not required <= review.keys() or review.keys()-permitted:
            raise ValueError('review incomplete or attempts to replace observed run data')
        occurrences=re.findall(r'\[\[cite:([^\]]*)\]\]',row['answer'])
        citations=review['citations']
        if not isinstance(citations,list) or [c.get('chunk_id') for c in citations]!=occurrences:
            raise ValueError('citation review must cover every answer citation occurrence in order')
        checked=[]
        for citation in citations:
            exists=citation['chunk_id'] in row['retrieved_chunk_ids']
            if 'exists' in citation and citation['exists'] is not exists:
                raise ValueError('citation existence conflicts with observed retrieved evidence')
            checked.append({'exists':exists,'supported':citation.get('supported')})
        reviewed.append(row | {k:v for k,v in review.items() if k!='id'} | {'citations':checked})
    return score(reviewed)


def main():
    import argparse
    import hashlib
    import json
    from pathlib import Path
    parser=argparse.ArgumentParser(description='Score a complete human annotation bound to a saved run')
    parser.add_argument('--run',type=Path,required=True)
    parser.add_argument('--annotations',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    payload=args.run.read_bytes()
    annotation=json.loads(args.annotations.read_text(encoding='utf-8-sig'))
    metrics=review_results(json.loads(payload),annotation,hashlib.sha256(payload).hexdigest())
    args.output.write_text(json.dumps({'run_sha256':hashlib.sha256(payload).hexdigest(),'reviewer':annotation['reviewer'],'metrics':metrics,'quality_acceptance':'REQUIRES_INDEPENDENT_REVIEW'},indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':
    main()
