#!/usr/bin/env python3
"""Linux x86_64 regression test for Debian glibc under Android-like seccomp.

Run on a glibc >= 2.35 host: python3 tools/test_proot_seccomp.py
Uses the APK's actual static proot. No root, Android SDK or downloads needed.
Only children receive the filter. This models rseq rejection, not all Android
policies, and does not replace real-device testing.
"""
import ctypes
import os
from pathlib import Path
import platform
import re
import signal
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PROOT = ROOT / "app/src/main/jniLibs/x86_64/libproot.so"
COMMAND_SOURCE = ROOT / (
    "core/linuxenv/src/main/java/io/github/oustery/opencodephone/"
    "core/linuxenv/PRootCommand.kt"
)


class SockFilter(ctypes.Structure):
    _fields_ = [("code", ctypes.c_ushort), ("jt", ctypes.c_ubyte),
                ("jf", ctypes.c_ubyte), ("k", ctypes.c_uint)]


class SockFprog(ctypes.Structure):
    _fields_ = [("len", ctypes.c_ushort), ("filter", ctypes.POINTER(SockFilter))]


def block_rseq():
    # x86_64 __NR_rseq=334. BPF: load syscall number; trap rseq; allow others.
    filters = (SockFilter * 4)(
        SockFilter(0x20, 0, 0, 0), SockFilter(0x15, 0, 1, 334),
        SockFilter(0x06, 0, 0, 0x00030000),  # SECCOMP_RET_TRAP -> SIGSYS
        SockFilter(0x06, 0, 0, 0x7fff0000),  # SECCOMP_RET_ALLOW
    )
    program = SockFprog(4, filters)
    libc = ctypes.CDLL(None, use_errno=True)
    # PR_SET_NO_NEW_PRIVS, then PR_SET_SECCOMP / SECCOMP_MODE_FILTER.
    if (libc.prctl(38, 1, 0, 0, 0) != 0 or
            libc.prctl(22, 2, ctypes.byref(program), 0, 0) != 0):
        os._exit(120)


@unittest.skipUnless(platform.system() == "Linux" and
                     platform.machine() == "x86_64" and
                     platform.libc_ver()[0] == "glibc" and
                     tuple(map(int, platform.libc_ver()[1].split("."))) >= (2, 35),
                     "requires Linux x86_64 with glibc >= 2.35")
class PRootSeccompTest(unittest.TestCase):
    def run_guest(self, tunables, command):
        with tempfile.TemporaryDirectory(prefix="proot-seccomp-") as tmp:
            result = subprocess.run(
                [str(PROOT), "--kill-on-exit", "-v", "1", "-r", "/", *command],
                env=dict(os.environ, PROOT_NO_SECCOMP="1", PROOT_TMP_DIR=tmp,
                         GLIBC_TUNABLES=tunables),
                preexec_fn=block_rseq, capture_output=True, text=True, timeout=20,
            )
        self.assertNotEqual(120, result.returncode, "cannot install seccomp filter")
        return result

    def test_unfixed_probe_silently_returns_255(self):
        result = self.run_guest("glibc.pthread.rseq=1", ["/bin/true"])
        self.assertEqual(255, result.returncode, result.stderr)
        self.assertIn(f"terminated with signal {int(signal.SIGSYS)}", result.stderr)

    def test_app_environment_runs_probe_and_shell_children(self):
        # Read the production setting so deleting/changing it fails this test.
        setting = re.search(r'"GLIBC_TUNABLES" to "([^"]+)"',
                            COMMAND_SOURCE.read_text())
        self.assertIsNotNone(setting)
        for command in (["/bin/true"],
                        ["/bin/sh", "-c", "/bin/true && /bin/echo guest-ok"]):
            with self.subTest(command=command):
                result = self.run_guest(setting.group(1), command)
                self.assertEqual(0, result.returncode, result.stderr)
                if command[0] == "/bin/sh":
                    self.assertIn("guest-ok", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
