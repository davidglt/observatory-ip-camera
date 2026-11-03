#!/usr/bin/env python3
"""Serve one exported JPEG, never a directory. Development use only."""
import argparse
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import urlsplit

MAX_BYTES = 16 * 1024 * 1024


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot', type=Path, default=Path('snapshot.jpg'))
    parser.add_argument('--bind', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8080)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error('Port must be between 1 and 65535.')
    snapshot = args.snapshot.absolute()
    if args.bind != '127.0.0.1':
        print('WARNING: No authentication or TLS. Use only on a trusted private network; never expose to the Internet.', flush=True)

    class Handler(BaseHTTPRequestHandler):
        server_version = 'ObservatorySnapshot/0.1'
        sys_version = ''

        def setup(self):
            super().setup()
            self.connection.settimeout(5)

        def do_GET(self):
            self.respond(False)

        def do_HEAD(self):
            self.respond(True)

        def respond(self, head):
            if urlsplit(self.path).path != '/snapshot.jpg':
                self.send_error(404, 'Only /snapshot.jpg is available.')
                return
            try:
                with snapshot.open('rb') as source:
                    data = source.read(MAX_BYTES + 1)
            except FileNotFoundError:
                self.send_error(404, 'Snapshot not found. Export a capture first.')
                return
            except OSError:
                self.send_error(503, 'Snapshot cannot be read.')
                return
            if len(data) > MAX_BYTES or not data.startswith(b'\xff\xd8') or not data.endswith(b'\xff\xd9'):
                self.send_error(503, 'Snapshot is incomplete, oversized or not a supported JPEG.')
                return
            self.send_response(200)
            self.send_header('Content-Type', 'image/jpeg')
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Connection', 'close')
            self.end_headers()
            self.close_connection = True
            if not head:
                try:
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError, TimeoutError):
                    pass

    try:
        with HTTPServer((args.bind, args.port), Handler) as server:
            print(f'Serving: {snapshot}', flush=True)
            print(f'Listening on {args.bind}:{args.port}; endpoint: /snapshot.jpg', flush=True)
            print('Press Ctrl+C to stop. Captures are not refreshed automatically.', flush=True)
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                print('\nServer stopped.', flush=True)
    except OSError as error:
        parser.exit(1, f'Error: {error}\n')


if __name__ == '__main__':
    main()
