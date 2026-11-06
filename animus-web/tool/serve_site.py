"""Serves build/jaspr for the browser checks.

python3 -m http.server keeps a listen backlog of 5, so the dozen stylesheet
requests a page makes at once can be reset under load. This server queues
128 and answers each request on its own thread.

    python3 tool/serve_site.py --port 8130 [--directory build/jaspr]
"""

import argparse
import functools
import http.server


class Server(http.server.ThreadingHTTPServer):
    request_queue_size = 128
    daemon_threads = True


class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {**http.server.SimpleHTTPRequestHandler.extensions_map, '.js': 'text/javascript', '.svg': 'image/svg+xml', '.json': 'application/json'}

    def log_message(self, format: str, *args: object) -> None:
        pass


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=8130)
    parser.add_argument('--bind', default='127.0.0.1')
    parser.add_argument('--directory', default='build/jaspr')
    options = parser.parse_args()
    handler = functools.partial(Handler, directory=options.directory)
    with Server((options.bind, options.port), handler) as server:
        print(f'Serving {options.directory} at http://{options.bind}:{options.port}/', flush=True)
        server.serve_forever()


if __name__ == '__main__':
    main()
