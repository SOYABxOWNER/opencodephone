#!/usr/bin/env python3
"""Linux x86_64 regression test for Debian glibc under Android-like seccomp.

Run on a glibc >= 2.35 host: python3 tools/test_proot_seccomp.py
Uses the APK's actual static proot. No root, Android SDK or downloads needed.
Only children receive the filter. This models several syscall rejections, not all Android
policies, and does not replace real-device testing.
"""
import ctypes
import os
from pathlib import Path
import platform
import re
import signal
import subprocess
import struct
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


def block_android_incompatible_syscalls():
    # x86_64: fork=57, set_robust_list=273, rseq=334, clone3=435. The old rseq-only
    # test missed glibc's earlier robust-list registration and thread/fork
    # fallback. Model Android SECCOMP_RET_TRAP, never alter the parent filter.
    blocked = (57, 273, 334, 435)
    instructions = [SockFilter(0x20, 0, 0, 0)]  # load syscall number
    for number in blocked:
        instructions += [SockFilter(0x15, 0, 1, number),
                         SockFilter(0x06, 0, 0, 0x00030000)]
    instructions.append(SockFilter(0x06, 0, 0, 0x7fff0000))
    filters = (SockFilter * len(instructions))(*instructions)
    program = SockFprog(len(filters), filters)
    libc = ctypes.CDLL(None, use_errno=True)
    if (libc.prctl(38, 1, 0, 0, 0) != 0 or
            libc.prctl(22, 2, ctypes.byref(program), 0, 0) != 0):
        os._exit(120)


class BundledElfTest(unittest.TestCase):
    def test_all_abis_are_static_with_android_page_compatible_segments(self):
        for abi, machine in (("x86_64", 62), ("arm64-v8a", 183), ("armeabi-v7a", 40)):
            with self.subTest(abi=abi):
                data = (ROOT / f"app/src/main/jniLibs/{abi}/libproot.so").read_bytes()
                self.assertEqual(b"\x7fELF", data[:4])
                self.assertEqual(1, data[5])  # little endian
                is64 = data[4] == 2
                header = struct.unpack_from("<16sHHIQQQIHHHHHH" if is64 else
                                            "<16sHHIIIIIHHHHHH", data)
                self.assertEqual(machine, header[2])
                entry, phoff, phsize, phnum = header[4], header[5], header[9], header[10]
                entry_executable = False
                for i in range(phnum):
                    ph = struct.unpack_from("<IIQQQQQQ" if is64 else "<IIIIIIII",
                                            data, phoff + i * phsize)
                    kind = ph[0]
                    self.assertNotIn(kind, (2, 3), "must not require a dynamic linker or shared libraries")
                    if is64:
                        flags, offset, address, size = ph[1], ph[2], ph[3], ph[6]
                    else:
                        flags, offset, address, size = ph[6], ph[1], ph[2], ph[5]
                    if kind == 1:
                        self.assertEqual(offset % 16384, address % 16384)
                        if flags & 1 and address <= entry < address + size:
                            entry_executable = True
                    if kind == 0x6474e551:  # PT_GNU_STACK
                        self.assertEqual(0, flags & 1)
                self.assertTrue(entry_executable)


@unittest.skipUnless(platform.system() == "Linux" and
                     platform.machine() == "x86_64" and
                     platform.libc_ver()[0] == "glibc" and
                     tuple(map(int, platform.libc_ver()[1].split("."))) >= (2, 35),
                     "requires Linux x86_64 with glibc >= 2.35")
class PRootSeccompTest(unittest.TestCase):
    def run_guest(self, tunables, command):
        with tempfile.TemporaryDirectory(prefix="proot-seccomp-") as tmp:
            result = subprocess.run(
                [str(PROOT), "--kill-on-exit", "-v", "1", "-0", "-r", "/", *command],
                env=dict(os.environ, PROOT_NO_SECCOMP="1", PROOT_TMP_DIR=tmp,
                         GLIBC_TUNABLES=tunables),
                preexec_fn=block_android_incompatible_syscalls, capture_output=True, text=True, timeout=20,
            )
        self.assertNotEqual(120, result.returncode, "cannot install seccomp filter")
        return result

    def test_filter_really_blocks_untraced_glibc_startup(self):
        result = subprocess.run(
            ["/bin/true"], preexec_fn=block_android_incompatible_syscalls,
            env=dict(os.environ, GLIBC_TUNABLES="glibc.pthread.rseq=0"),
            capture_output=True, timeout=20,
        )
        # Even with the previous rseq fix, robust-list registration is fatal
        # without a tracer translating the Android seccomp rejection.
        self.assertEqual(-signal.SIGSYS, result.returncode, result.stderr)

    def test_tracer_handles_rejections_even_with_rseq_enabled(self):
        result = self.run_guest("glibc.pthread.rseq=1", ["/bin/true"])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("outer seccomp trapped syscall 273", result.stderr)

    def test_threads_and_fork_fall_back_when_clone3_is_blocked(self):
        result = self.run_guest("glibc.pthread.rseq=0", [
            "/usr/bin/python3", "-c",
            "import threading, subprocess; "
            "t = threading.Thread(target=lambda: print('thread-ok')); "
            "t.start(); t.join(); subprocess.run(['/bin/true'], check=True)",
        ])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("thread-ok", result.stdout)

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
