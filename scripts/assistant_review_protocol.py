"""Offline reviewer evidence checks. Never grants execution or rejects a product query."""
import json

KINDS = {'PROTOCOL', 'TARGET', 'HARD_CONDITION', 'TIME_ROLE', 'STOP_STATE', 'AUTHORITY', 'CONTRACT_AMBIGUITY'}


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError('DUPLICATE_JSON_KEY')
        value[key] = item
    return value


def validate(text):
    obj = json.loads(text, object_pairs_hook=unique_object)
    if not isinstance(obj, dict) or set(obj) != {'verdict', 'issues', 'note'}:
        raise ValueError('REVIEW_FIELDS')
    if obj['verdict'] not in ['PASS', 'FLAG', 'UNCERTAIN']:
        raise ValueError('REVIEW_VERDICT')
    if not isinstance(obj['note'], str) or len(obj['note']) > 1600:
        raise ValueError('REVIEW_NOTE')
    if obj['verdict'] == 'UNCERTAIN' and not obj['note'].strip():
        raise ValueError('UNCERTAIN_NEEDS_REASON')
    issues = obj['issues']
    if not isinstance(issues, list) or len(issues) > 4:
        raise ValueError('REVIEW_ISSUES')
    if obj['verdict'] == 'PASS' and issues or obj['verdict'] == 'FLAG' and not issues:
        raise ValueError('VERDICT_ISSUES_INCONSISTENT')
    for item in issues:
        if not isinstance(item, dict) or set(item) != {'kind', 'rulePath', 'ruleQuote', 'reason', 'enumClaim'}:
            raise ValueError('ISSUE_FIELDS')
        if item['kind'] not in KINDS:
            raise ValueError('ISSUE_KIND')
        for field in ['rulePath', 'ruleQuote', 'reason']:
            if not isinstance(item[field], str) or not item[field].strip() or len(item[field]) > 2000:
                raise ValueError('ISSUE_CONTENT')
        claim = item['enumClaim']
        if claim is not None and (not isinstance(claim, dict) or set(claim) != {'path', 'value', 'allowed'}
                                  or not isinstance(claim['path'], str) or not isinstance(claim['value'], str)
                                  or type(claim['allowed']) is not bool):
            raise ValueError('ENUM_CLAIM')
    return obj


def pointer(value, path):
    if not path.startswith('/'):
        raise ValueError('INVALID_POINTER')
    for part in path[1:].split('/'):
        part = part.replace('~1', '/').replace('~0', '~')
        if isinstance(value, list):
            if not part.isdecimal():
                raise ValueError('INVALID_INDEX')
            value = value[int(part)]
        elif isinstance(value, dict):
            value = value[part]
        else:
            raise ValueError('NOT_A_CONTAINER')
    return value


def check_references(audit, contract):
    """A real quotation is evidence of a reference, never proof of a semantic allegation."""
    results = []
    for item in audit['issues']:
        failures = []
        try:
            rule = pointer(contract, item['rulePath'])
            rendered = rule if isinstance(rule, str) else json.dumps(rule, ensure_ascii=False, separators=(',', ':'))
            if item['ruleQuote'] not in rendered:
                failures.append('QUOTE_NOT_IN_RULE')
        except (KeyError, IndexError, ValueError, TypeError):
            failures.append('RULE_NOT_FOUND')
        claim = item['enumClaim']
        if claim is not None:
            try:
                allowed = pointer(contract, claim['path'])
                if not isinstance(allowed, list) or not all(isinstance(v, str) for v in allowed):
                    failures.append('NOT_AN_ENUM')
                elif (claim['value'] in allowed) != claim['allowed']:
                    failures.append('CONTRACT_CONTRADICTS_ENUM_CLAIM')
            except (KeyError, IndexError, ValueError, TypeError):
                failures.append('ENUM_NOT_FOUND')
        results.append({'status': 'REFERENCE_REJECTED' if failures else 'REFERENCES_VALID_SEMANTICS_UNVERIFIED',
                        'failures': failures, 'semanticVerdict': 'NOT_AUTOMATICALLY_DECIDED'})
    return results
