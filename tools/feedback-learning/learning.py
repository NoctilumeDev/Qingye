"""Optional Qingye feedback store. Model/service callers cannot review records."""
import argparse
import hmac
import json
import math
import os
from pathlib import Path
import sqlite3
import time
import uuid
from collections import Counter
from http.server import BaseHTTPRequestHandler, HTTPServer


class Store:
    def __init__(self, path, clock=time.time):
        self.path, self.clock = str(path), clock
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute('''CREATE TABLE IF NOT EXISTS feedback (
                id TEXT PRIMARY KEY, owner TEXT NOT NULL, receipt TEXT NOT NULL,
                contract TEXT NOT NULL, capability TEXT NOT NULL, question TEXT NOT NULL,
                feedback TEXT NOT NULL, evidence TEXT NOT NULL, created REAL NOT NULL,
                status TEXT NOT NULL DEFAULT 'PENDING'
                    CHECK(status IN ('PENDING','APPROVED','REJECTED')),
                lesson TEXT NOT NULL DEFAULT '', review_basis TEXT NOT NULL DEFAULT '',
                reviewed REAL, UNIQUE(owner, receipt))''')

    def connect(self):
        db = sqlite3.connect(self.path, timeout=2)
        db.row_factory = sqlite3.Row
        return db

    def submit(self, body):
        # Input is a server-held result receipt, not client-supplied database facts.
        expected = {'owner', 'receipt', 'contract', 'capability', 'question', 'feedback', 'evidence'}
        if set(body) != expected:
            raise ValueError('unexpected or missing fields')
        for key, limit in [('owner', 64), ('receipt', 36), ('contract', 120),
                           ('capability', 40), ('question', 8000), ('feedback', 300)]:
            if not isinstance(body[key], str) or not 0 < len(body[key]) <= limit:
                raise ValueError('invalid ' + key)
        if len(body['owner']) != 64 or any(c not in '0123456789abcdef' for c in body['owner']):
            raise ValueError('invalid owner')
        uuid.UUID(body['receipt'])
        if body['capability'] not in {'ACTIVITIES', 'EQUIPMENT', 'MY_REGISTRATIONS', 'MY_LOANS'}:
            raise ValueError('invalid capability')
        if not isinstance(body['evidence'], dict):
            raise ValueError('invalid evidence')
        evidence = json.dumps(body['evidence'], ensure_ascii=False, allow_nan=False)
        if len(evidence.encode('utf-8')) > 64000:
            raise ValueError('evidence too large')
        now = self.clock()
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            prior = db.execute('SELECT id,status FROM feedback WHERE owner=? AND receipt=?',
                               (body['owner'], body['receipt'])).fetchone()
            if prior:
                return dict(prior)
            count = db.execute('SELECT count(*) FROM feedback WHERE owner=? AND created>?',
                               (body['owner'], now - 3600)).fetchone()[0]
            total = db.execute('SELECT count(*) FROM feedback').fetchone()[0]
            if count >= 20 or total >= 10000:
                raise ValueError('feedback intake limit reached')
            key = str(uuid.uuid4())
            db.execute('''INSERT INTO feedback
                (id,owner,receipt,contract,capability,question,feedback,evidence,created)
                VALUES (?,?,?,?,?,?,?,?,?)''', (key, body['owner'], body['receipt'],
                body['contract'], body['capability'], body['question'], body['feedback'], evidence, now))
            return {'id': key, 'status': 'PENDING'}

    def review(self, key, state, basis, lesson=''):
        """Maintainer-only local operation; deliberately absent from the HTTP API."""
        if state not in {'APPROVED', 'REJECTED', 'PENDING'} or not basis.strip():
            raise ValueError('review state and evidence basis required')
        if state == 'APPROVED' and not lesson.strip():
            raise ValueError('verified correction required')
        if len(lesson) > 1000 or len(basis) > 4000:
            raise ValueError('review too long')
        with self.connect() as db:
            if db.execute('''UPDATE feedback SET status=?,lesson=?,review_basis=?,reviewed=?
                             WHERE id=?''', (state, lesson if state == 'APPROVED' else '',
                                            basis, self.clock(), key)).rowcount != 1:
                raise ValueError('record not found')

    @staticmethod
    def features(text):
        # General character features, not a second Chinese semantic interpreter.
        text = ''.join(text.casefold().split())
        return Counter(text[i:i + 2] for i in range(max(0, len(text) - 1)))

    def retrieve(self, owner, contract, question):
        if not isinstance(question, str) or len(question) > 8000:
            raise ValueError('invalid question')
        query = self.features(question)
        norm = math.sqrt(sum(v * v for v in query.values()))
        if not norm:
            return []
        with self.connect() as db:
            rows = db.execute('''SELECT id,capability,question,lesson FROM feedback
                WHERE owner=? AND contract=? AND status='APPROVED' ''', (owner, contract)).fetchall()
        ranked = []
        for row in rows:
            terms = self.features(row['question'])
            denom = norm * math.sqrt(sum(v * v for v in terms.values()))
            score = sum(v * terms[k] for k, v in query.items()) / denom if denom else 0
            if score >= .2:
                ranked.append((score, row['id'], {'id': row['id'], 'capability': row['capability'],
                                                'lesson': row['lesson']}))
        return [item[2] for item in sorted(ranked, key=lambda item: (-item[0], item[1]))[:3]]

    def counts(self):
        with self.connect() as db:
            return {**dict.fromkeys(('PENDING', 'APPROVED', 'REJECTED'), 0),
                    **dict(db.execute('SELECT status,count(*) FROM feedback GROUP BY status'))}


def handler(store, key):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass  # No bearer token, question or private result in access logs.

        def do_POST(self):
            self.connection.settimeout(2)
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 100000:
                    raise ValueError('invalid body size')
                # Finish the bounded body before closing a rejected POST; otherwise
                # Windows clients can receive a reset instead of the HTTP status.
                wire_body = self.rfile.read(length)
                if len(wire_body) != length:
                    raise ValueError('incomplete body')
                if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + key):
                    self.send_error(401)
                    return
                if self.path not in {'/feedback', '/experiences'}:
                    self.send_error(404)
                    return
                body = json.loads(wire_body)
                if not isinstance(body, dict):
                    raise ValueError('invalid request')
                if self.path == '/feedback':
                    result = store.submit(body)
                else:
                    if set(body) != {'owner', 'contract', 'question'} or not all(isinstance(v, str) for v in body.values()):
                        raise ValueError('invalid request')
                    result = {'experiences': store.retrieve(body['owner'], body['contract'], body['question'])}
                wire = json.dumps(result, ensure_ascii=False).encode('utf-8')
                self.send_response(200)
                self.send_header('Content-Type', 'application/json; charset=utf-8')
                self.send_header('Content-Length', str(len(wire)))
                self.end_headers()
                self.wfile.write(wire)
            except (ValueError, TypeError, OSError, sqlite3.Error):
                self.send_error(400, 'Feedback request unavailable')
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--db', required=True, help='Private local SQLite file outside Git')
    commands = parser.add_subparsers(dest='command', required=True)
    serve = commands.add_parser('serve')
    serve.add_argument('--port', type=int, default=8097)
    commands.add_parser('counts')
    listing = commands.add_parser('list')
    listing.add_argument('--state', choices=['PENDING', 'APPROVED', 'REJECTED'], default='PENDING')
    listing.add_argument('--offset', type=int, default=0)
    inspect = commands.add_parser('inspect')
    inspect.add_argument('id')
    review = commands.add_parser('review')
    review.add_argument('id')
    review.add_argument('state', choices=['PENDING', 'APPROVED', 'REJECTED'])
    review.add_argument('--basis-file', required=True, help='Evidence checked by maintainer')
    review.add_argument('--lesson-file', help='Verified correction, required for APPROVED')
    args = parser.parse_args()
    store = Store(args.db)
    if args.command == 'serve':
        key = os.environ.get('QINGYE_FEEDBACK_KEY', '')
        if len(key) < 32:
            parser.error('QINGYE_FEEDBACK_KEY must contain at least 32 characters')
        HTTPServer(('127.0.0.1', args.port), handler(store, key)).serve_forever()
    elif args.command == 'counts':
        print(json.dumps(store.counts()))
    elif args.command == 'list':
        with store.connect() as db:
            rows = db.execute('''SELECT id,status,capability,created FROM feedback
                WHERE status=? ORDER BY created,id LIMIT 50 OFFSET ?''', (args.state, max(0, args.offset)))
            print(json.dumps([dict(row) for row in rows], ensure_ascii=False, indent=2))
    elif args.command == 'inspect':
        with store.connect() as db:
            row = db.execute('SELECT * FROM feedback WHERE id=?', (args.id,)).fetchone()
            print(json.dumps(dict(row) if row else None, ensure_ascii=False, indent=2))
    else:
        store.review(args.id, args.state, Path(args.basis_file).read_text(encoding='utf-8'),
                     Path(args.lesson_file).read_text(encoding='utf-8') if args.lesson_file else '')
        print(args.state)


if __name__ == '__main__':
    main()
