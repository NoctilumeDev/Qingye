"""Opt-in real-model business review. Credentials live only in a non-echo console and relay memory."""
import argparse
from datetime import datetime, timezone
import getpass
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import threading
import time
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[1]
ENDPOINTS = {'deepseek': 'https://api.deepseek.com/chat/completions', 'qwen': 'https://maas.qianwenaiapi.com/compatible-mode/v1/chat/completions'}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('evidence', type=Path)
    parser.add_argument('--port', type=int, default=19377)
    parser.add_argument('--proxy')
    args = parser.parse_args()
    manifest = json.loads((args.evidence/'manifest.json').read_text(encoding='utf-8'))
    for filename, expected in manifest['files'].items():
        if hashlib.sha256(Path(filename).read_bytes()).hexdigest() != expected:
            raise SystemExit('Frozen input changed: ' + filename)
    raw = args.evidence/'network.jsonl'
    results = args.evidence/'business-results.jsonl'
    if raw.exists() or results.exists():
        raise SystemExit('Refuse to reuse or overwrite review identities')
    cp = (ROOT/'backend/target/test-classpath.txt').read_text(encoding='utf-8').strip()
    classpath = str(ROOT/'backend/target/classes')+';'+cp
    compiled = args.evidence/'compiled'
    compiled.mkdir(exist_ok=True)
    subprocess.run(['javac','-encoding','UTF-8','-cp',classpath,'-d',str(compiled),str(ROOT/'scripts/AssistantBusinessProbe.java')],check=True)
    keys = {'deepseek':getpass.getpass('DeepSeek temporary key (hidden): '), 'qwen':getpass.getpass('Qwen temporary key (hidden): ')}
    if not all(keys.values()):
        keys.clear()
        raise SystemExit('Missing credential')
    state = {'calls':0, 'stop':None, 'startedAt':datetime.now(timezone.utc).isoformat()}
    lock = threading.Lock()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({'https':args.proxy} if args.proxy else {}))

    class Relay(BaseHTTPRequestHandler):
        def log_message(self, *unused):
            pass

        def do_POST(self):
            provider = self.path.strip('/')
            payload = self.rfile.read(int(self.headers.get('Content-Length','0')))
            if provider not in ENDPOINTS or len(payload)>50000:
                self.send_error(400)
                return
            with lock:
                if state['stop'] or state['calls']>=manifest['maximumNewCalls']:
                    self.send_error(503)
                    return
                state['calls']+=1
                identity='BQ-20261021-'+str(state['calls'])
            start=time.monotonic();status=0;body=b'';error=None
            try:
                req=urllib.request.Request(ENDPOINTS[provider],data=payload,headers={'Content-Type':'application/json','Authorization':'Bearer '+keys[provider]},method='POST')
                with opener.open(req,timeout=5) as response:
                    status=response.status;body=response.read(100001)
            except urllib.error.HTTPError as exc:
                status=exc.code;body=exc.read(100001)
            except Exception as exc:
                error=type(exc).__name__
            elapsed=round((time.monotonic()-start)*1000,3)
            decoded=body.decode('utf-8',errors='replace')
            for key in keys.values():
                decoded=decoded.replace(key,'[REDACTED]')
            try:
                response=json.loads(decoded)
            except Exception:
                response={'unparsedBody':decoded}
            record=dict(identity=identity,provider=provider,endpoint=ENDPOINTS[provider],startedAt=datetime.now(timezone.utc).isoformat(),payload=json.loads(payload),payloadSha256=hashlib.sha256(payload).hexdigest(),httpStatus=status,response=response,latencyMs=elapsed,error=error,usage=response.get('usage'),receivedComplete=status==200 and len(body)<=100000)
            with lock:
                with raw.open('a',encoding='utf-8') as out:
                    out.write(json.dumps(record,ensure_ascii=False)+'\n')
                if status in [401,402,403] or 'insufficient' in decoded.lower() or 'arrearage' in decoded.lower():
                    state['stop']='AUTH_OR_BALANCE_HTTP_'+str(status)
                (args.evidence/'RUN_STATUS.json').write_text(json.dumps(state,indent=2),encoding='utf-8')
            try:
                self.send_response(status or 504);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(body or b'{}')
            except (ConnectionError,OSError):
                pass

    server=ThreadingHTTPServer(('127.0.0.1',args.port),Relay)
    worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
    try:
        command=['java','-Dfile.encoding=UTF-8','-cp',str(compiled)+';'+classpath,'AssistantBusinessProbe','http://127.0.0.1:'+str(args.port),str(args.evidence/'cases.json'),str(results)]
        with (args.evidence/'native-probe.log').open('w',encoding='utf-8') as out:
            completed=subprocess.run(command,stdout=out,stderr=subprocess.STDOUT)
        state['nativeExitCode']=completed.returncode
    finally:
        server.shutdown();server.server_close();worker.join();keys.clear()
        state['credentialsCleared']=True;state['finishedAt']=datetime.now(timezone.utc).isoformat()
        (args.evidence/'RUN_STATUS.json').write_text(json.dumps(state,indent=2),encoding='utf-8')
    print(json.dumps(state,indent=2))


if __name__ == '__main__':
    main()
