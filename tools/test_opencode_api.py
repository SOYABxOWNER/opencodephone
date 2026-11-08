#!/usr/bin/env python3
"""Smoke-test a real supported CLI without touching the user's OpenCode data.

Example:
  npm pack opencode-linux-x64@1.19.5
  tar xf opencode-linux-x64-1.19.5.tgz
  python3 tools/test_opencode_api.py --binary ./package/bin/opencode

Uses a temporary workspace, private XDG directories, an ephemeral loopback
port and authentication. Does not submit model prompts or require API keys.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--proot", type=Path, help="also run through the bundled x86_64 PRoot")
    parser.add_argument("--android-seccomp", action="store_true", help="trap Android-incompatible syscalls (Linux x86_64)")
    args = parser.parse_args()
    binary = args.binary.resolve()
    with tempfile.TemporaryDirectory(prefix="opencode-api-test-") as tmp:
        root = Path(tmp)
        env = dict(os.environ)
        for name in ("DATA", "CONFIG", "CACHE", "STATE"):
            env[f"XDG_{name}_HOME"] = str(root / name.lower())
        password = secrets.token_urlsafe(24)
        env["OPENCODE_SERVER_PASSWORD"] = password
        env["OPENCODE_SERVER_USERNAME"] = "opencode"
        # Never inherit machine-local provider credentials/config into the test.
        env.pop("OPENCODE_CONFIG", None)
        env.pop("OPENCODE_CONFIG_CONTENT", None)
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        auth = "Basic " + base64.b64encode(f"opencode:{password}".encode()).decode()
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

        def request(path, method="GET", body=None, authenticated=True):
            headers = {"Accept": "application/json"}
            if authenticated:
                headers["Authorization"] = auth
            if body is not None:
                headers["Content-Type"] = "application/json"
            req = urllib.request.Request(
                f"http://127.0.0.1:{port}{path}", method=method, headers=headers,
                data=json.dumps(body).encode() if body is not None else None,
            )
            with opener.open(req, timeout=4) as response:
                assert "application/json" in response.headers.get("Content-Type", "")
                return json.load(response)

        command = [str(binary), "serve", "--hostname", "127.0.0.1", "--port", str(port)]
        if args.proot:
            command = [str(args.proot.resolve()), "--kill-on-exit", "-0", "-r", "/", *command]
            env["PROOT_NO_SECCOMP"] = "1"
            env["PROOT_TMP_DIR"] = tmp
            env["GLIBC_TUNABLES"] = "glibc.pthread.rseq=0"
        child_setup = None
        if args.android_seccomp:
            from test_proot_seccomp import block_android_incompatible_syscalls
            child_setup = block_android_incompatible_syscalls
        with open(root / "server.log", "w+") as log:
            proc = subprocess.Popen(
                command, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT,
                preexec_fn=child_setup,
            )
            try:
                deadline = time.monotonic() + 45
                while True:
                    try:
                        health = request("/global/health")
                        assert health["healthy"] is True and health["version"].startswith("1.")
                        break
                    except (OSError, ValueError):
                        if proc.poll() is not None or time.monotonic() >= deadline:
                            log.seek(0)
                            raise AssertionError("Server failed readiness: " + log.read()[-1500:])
                        time.sleep(0.2)
                try:
                    request("/global/health", authenticated=False)
                    raise AssertionError("Server accepted missing credentials")
                except urllib.error.HTTPError as error:
                    assert error.code == 401
                session = request("/session", "POST", {"title": "OpenCodePhone smoke test"})
                assert isinstance(session["id"], str)
                assert isinstance(request("/session"), list)
                assert request(f"/session/{session['id']}/message") == []
                assert isinstance(request("/agent"), list)
                assert isinstance(request("/provider")["all"], list)
                request(f"/session/{session['id']}", "DELETE")
                print(f"PASS: real OpenCode {health['version']} health, auth, sessions, messages, agents and providers")
            finally:
                proc.terminate()
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait()


if __name__ == "__main__":
    main()
