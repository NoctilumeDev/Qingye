"""Read-only summary of one completed new-version business review; no API access."""
import argparse
from collections import Counter
import json
from pathlib import Path


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('evidence',type=Path)
    args=parser.parse_args()
    rows=[json.loads(line) for line in (args.evidence/'business-results.jsonl').read_text(encoding='utf-8').splitlines()]
    calls=[json.loads(line) for line in (args.evidence/'network.jsonl').read_text(encoding='utf-8').splitlines()]
    result={'businessObservations':len(rows),'actualModelRequests':len(calls),'readOnlyObservations':sum(r['readOnly'] for r in rows),'providers':{},'mismatches':[]}
    for provider in ['deepseek','qwen']:
        observed=[r for r in rows if r['provider']==provider]
        requests=[r for r in calls if r['provider']==provider]
        inp=sum((r.get('usage') or {}).get('prompt_tokens',0) for r in requests)
        out=sum((r.get('usage') or {}).get('completion_tokens',0) for r in requests)
        result['providers'][provider]={'observations':len(observed),'strictPlanAndNativeResultMatch':sum(r['businessMatch'] for r in observed),'modelRequests':len(requests),'httpStatus':dict(Counter(str(r['httpStatus']) for r in requests)),
            'inputTokens':inp,'outputTokens':out,'usageMissing':sum(not r.get('usage') for r in requests),'responseModels':dict(Counter((r.get('response') or {}).get('model','UNKNOWN') for r in requests)),
            'conservativePeakNoCacheCny':round((inp*(2 if provider=='deepseek' else .8)+out*(8 if provider=='deepseek' else 2))/1e6,8)}
    for r in rows:
        if not r['businessMatch']:
            result['mismatches'].append({k:r[k] for k in ['caseId','provider','repeat','question','expected','preview','actualIds']})
    result['qualification']='Product development review, not a paper result. Exact plan matching is intentionally strict; all mismatches retained. Confirmation simulation is not a human study. CNY is a conservative list-price estimate, not a cash bill.'
    (args.evidence/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k!='mismatches'},ensure_ascii=True,indent=2))


if __name__=='__main__':
    main()
