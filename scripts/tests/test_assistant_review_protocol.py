import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from assistant_review_protocol import validate, check_references


class ReviewProtocolTest(unittest.TestCase):
    def test_pass_note_may_be_empty_but_uncertainty_must_be_explained(self):
        self.assertEqual(validate('{"verdict":"PASS","issues":[],"note":""}')['verdict'], 'PASS')
        for raw in ['{"verdict":"PASS","verdict":"FLAG","issues":[],"note":""}',
                    '{"verdict":"FLAG","issues":[],"note":""}',
                    '{"verdict":"UNCERTAIN","issues":[],"note":""}']:
            with self.assertRaises(ValueError):
                validate(raw)

    def test_fabricated_enum_premise_cannot_become_verified_evidence(self):
        contract = {'equipment': {'time': ['CURRENT', 'TODAY']}}
        issue = {'kind':'PROTOCOL', 'rulePath':'/equipment/time', 'ruleQuote':'CURRENT',
                 'reason':'The reviewer claims FUTURE is supported.',
                 'enumClaim': {'path':'/equipment/time','value':'FUTURE','allowed':True}}
        audit = validate(json.dumps({'verdict':'FLAG','issues':[issue],'note':''}))
        self.assertEqual(check_references(audit, contract)[0]['failures'], ['CONTRACT_CONTRADICTS_ENUM_CLAIM'])
        audit['issues'][0]['enumClaim']['allowed'] = False
        checked = check_references(audit, contract)[0]
        self.assertEqual(checked['status'], 'REFERENCES_VALID_SEMANTICS_UNVERIFIED')
        self.assertEqual(checked['semanticVerdict'], 'NOT_AUTOMATICALLY_DECIDED')
        audit['issues'][0]['rulePath'] = '/invented'
        self.assertIn('RULE_NOT_FOUND', check_references(audit, contract)[0]['failures'])

    def test_literal_source_never_proves_time_role(self):
        audit = {'issues':[{'rulePath':'/rule','ruleQuote':'source exists', 'enumClaim':None}]}
        checked = check_references(audit, {'rule':'source exists does not imply role binding correct'})[0]
        self.assertEqual(checked['semanticVerdict'], 'NOT_AUTOMATICALLY_DECIDED')


if __name__ == '__main__':
    unittest.main()
