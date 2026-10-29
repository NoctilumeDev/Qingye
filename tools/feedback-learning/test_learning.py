import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import uuid
from http.server import HTTPServer
from pathlib import Path

from learning import Store, handler


class LearningTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = Store(Path(self.temp.name) / 'feedback.sqlite', clock=lambda: 1000)

    def case(self, **overrides):
        return dict(owner='a' * 64, receipt=str(uuid.uuid4()), contract='v5',
                    capability='EQUIPMENT', question='今天借用相机的记录',
                    feedback='时间不是计划使用日期', evidence={'items': [], 'hasMore': False}, **overrides)

    def test_pending_is_not_an_error_or_a_lesson_even_with_empty_results(self):
        result = self.store.submit(self.case())
        self.assertEqual(result['status'], 'PENDING')
        self.assertEqual(self.store.retrieve('a' * 64, 'v5', '今天借用相机的记录'), [])
        self.assertEqual(self.store.counts(), dict(PENDING=1, APPROVED=0, REJECTED=0))

    def test_only_maintainer_approved_correction_is_retrievable_and_revocable(self):
        key = self.store.submit(self.case())['id']
        with self.assertRaises(ValueError):
            self.store.review(key, 'APPROVED', '已对照原文和事实')
        self.store.review(key, 'APPROVED', '测试证据：原文未指计划时间，字段仅支持计划时间', '先确认时间角色；不支持的日期条件应停止')
        self.assertEqual(len(self.store.retrieve('a' * 64, 'v5', '今天借用相机的记录')), 1)
        self.store.review(key, 'PENDING', '发现证据矛盾，撤回经验')
        self.assertEqual(self.store.retrieve('a' * 64, 'v5', '今天借用相机的记录'), [])

    def test_rejected_never_learns(self):
        key = self.store.submit(self.case())['id']
        self.store.review(key, 'REJECTED', '核对后目标在原结果中，用户说明系误点')
        self.assertEqual(self.store.retrieve('a' * 64, 'v5', '今天借用相机的记录'), [])
        self.assertEqual(self.store.counts()['REJECTED'], 1)

    def test_duplicates_and_rate_do_not_promote_pending(self):
        body = self.case()
        first = self.store.submit(body)
        for _ in range(50):
            self.assertEqual(self.store.submit(body), first)
        for _ in range(19):
            self.store.submit(self.case())
        with self.assertRaises(ValueError):
            self.store.submit(self.case())
        self.assertEqual(self.store.counts(), dict(PENDING=20, APPROVED=0, REJECTED=0))

    def test_approved_experiences_are_owner_and_contract_scoped(self):
        key = self.store.submit(self.case())['id']
        self.store.review(key, 'APPROVED', '测试核验材料', '时间含义需补充')
        self.assertEqual(self.store.retrieve('b' * 64, 'v5', '今天借用相机的记录'), [])
        self.assertEqual(self.store.retrieve('a' * 64, 'v6', '今天借用相机的记录'), [])
        self.assertEqual(self.store.retrieve('a' * 64, 'v5', '完全无关的数据'), [])

    def test_input_cannot_set_status_or_lesson(self):
        for key in ('status', 'lesson', 'review_basis'):
            body = self.case(); body[key] = 'APPROVED'
            with self.assertRaises(ValueError):
                self.store.submit(body)
        self.assertEqual(self.store.counts()['PENDING'], 0)

    def test_fresh_store_reopens_existing_pending_records(self):
        self.store.submit(self.case())
        other = Store(self.store.path)
        self.assertEqual(other.counts()['PENDING'], 1)

    def test_http_service_has_no_review_authority(self):
        key = 'fixture-only-' * 4
        server = HTTPServer(('127.0.0.1', 0), handler(self.store, key))
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            def call(path, body, credential=key):
                req = urllib.request.Request('http://127.0.0.1:%s%s' % (server.server_port, path),
                    json.dumps(body).encode(), {'Authorization': 'Bearer ' + credential, 'Content-Type': 'application/json'})
                # Explicitly avoid system proxy for this local test.
                with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=2) as reply:
                    return json.load(reply)
            result = call('/feedback', self.case())
            self.assertEqual(result['status'], 'PENDING')
            for path in ('/review', '/approve'):
                with self.assertRaises(urllib.error.HTTPError) as error:
                    call(path, {'id': result['id'], 'status': 'APPROVED'})
                self.assertEqual(error.exception.code, 404)
            with self.assertRaises(urllib.error.HTTPError) as error:
                call('/feedback', self.case(), 'incorrect')
            self.assertEqual(error.exception.code, 401)
            self.assertEqual(call('/experiences', dict(owner='a' * 64, contract='v5', question='今天借用相机')),
                             {'experiences': []})
        finally:
            server.shutdown(); server.server_close(); thread.join()


if __name__ == '__main__':
    unittest.main()
