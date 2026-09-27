import base64
import json
import re
import secrets
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlencode, urlsplit


def start_provider():
    codes, tokens = {}, {}
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def reply(self, status, body):
            content = json.dumps(body).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(content)))
            self.end_headers()
            self.wfile.write(content)

        def do_GET(self):
            path = urlsplit(self.path)
            if path.path == '/authorize':
                query = {key: values[0] for key, values in parse_qs(path.query).items()}
                email = query.get('login_hint', '')
                redirect = query.get('redirect_uri', '')
                if (query.get('client_id') != 'synthetic-e2e-client'
                        or query.get('response_type') != 'code' or not query.get('state')
                        or redirect != 'http://localhost:8082/login/oauth2/code/google'
                        or not re.fullmatch(r'e2e-[a-f0-9]{32}@example\.invalid', email)):
                    self.reply(400, {'error': 'invalid_request'})
                    return
                code = secrets.token_urlsafe(24)
                with lock:
                    codes[code] = (email, redirect)
                self.send_response(302)
                self.send_header('Location', redirect + '?' + urlencode({'code': code, 'state': query['state']}))
                self.send_header('Content-Length', '0')
                self.end_headers()
            elif path.path == '/userinfo':
                token = self.headers.get('Authorization', '').removeprefix('Bearer ')
                with lock:
                    email = tokens.get(token)
                if email:
                    self.reply(200, {'sub': email, 'email': email, 'email_verified': True, 'name': 'Synthetic E2E'})
                else:
                    self.reply(401, {'error': 'invalid_token'})
            else:
                self.reply(404, {'error': 'not_found'})

        def do_POST(self):
            if self.path != '/token':
                self.reply(404, {'error': 'not_found'})
                return
            size = int(self.headers.get('Content-Length', '0'))
            if size > 65536:
                self.reply(400, {'error': 'invalid_request'})
                return
            query = {key: values[0] for key, values in parse_qs(self.rfile.read(size).decode()).items()}
            credentials = base64.b64encode(b'synthetic-e2e-client:synthetic-e2e-secret').decode()
            if self.headers.get('Authorization') != 'Basic ' + credentials:
                self.reply(401, {'error': 'invalid_client'})
                return
            with lock:
                identity = codes.pop(query.get('code'), None)
                if (query.get('grant_type') != 'authorization_code' or identity is None
                        or query.get('redirect_uri') != identity[1]):
                    self.reply(400, {'error': 'invalid_grant'})
                    return
                token = secrets.token_urlsafe(32)
                tokens[token] = identity[0]
            self.reply(200, {'access_token': token, 'token_type': 'Bearer', 'expires_in': 300})

    server = ThreadingHTTPServer(('0.0.0.0', 18080), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, thread
