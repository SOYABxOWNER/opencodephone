#!/usr/bin/env python3
"""Rebuild standalone Android-compatible PRoot for all APK ABIs.

Requires Linux x86_64, Python 3.9+, git, make, patch, readelf and objdump.
Downloads pinned Zig (musl cross compiler), Termux PRoot and talloc sources.
All scratch/downloads go to .work/proot (ignored); only final ELFs go to jniLibs.
No NDK, Termux prefix, shared libraries or Android installation required.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SUPPORT = ROOT / "tools/native"
WORK = ROOT / ".work/proot"
PROOT_COMMIT = "52d65a4dc140ca554d241a1438ddd4f6b00c9813"
STATIC_COMMIT = "aa7ec720453edd8245476c6e1e61977f7c30e95c"
ZIG_URL = "https://registry.npmjs.org/@ryoppippi/zig-linux-x64/-/zig-linux-x64-0.13.0.tgz"
ZIG_SHA256 = "6eb1597c9b0cc4cb5adc0cc3a6752d6ab933ae60dd98ab9cdb13608f7fbc637f"
TALLOC_SHA256 = "3e29ce6c3ba3c4f7c2d57ce8cf0fbc24c86618c519f2b2fb6a459025488b6174"
TARGETS = {
    "x86_64": ("x86_64-linux-musl", "x86-linux-musl", "0x600000000000", "0xa0000000"),
    "arm64-v8a": ("aarch64-linux-musl", "arm-linux-musleabihf", "0x5400000000", "0x20000000"),
    "armeabi-v7a": ("arm-linux-musleabihf", None, "0x20000000", None),
}


def run(args, **kw):
    subprocess.run([str(x) for x in args], check=True, **kw)


def checkout(repo, commit, dest):
    if not dest.exists():
        run(["git", "init", dest])
        run(["git", "-C", dest, "fetch", "--depth", "1", f"https://github.com/{repo}.git", commit])
        run(["git", "-C", dest, "checkout", "--detach", "FETCH_HEAD"])
    actual = subprocess.check_output(["git", "-C", dest, "rev-parse", "HEAD"], text=True).strip()
    if actual != commit:
        raise RuntimeError(f"Unexpected source revision: {dest}: {actual}")


def verify(path, digest):
    if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
        raise RuntimeError(f"Checksum mismatch: {path}")


def executable(path, text):
    path.write_text(text)
    path.chmod(0o755)


def build(abi, zig, source, talloc):
    target, target32, address, address32 = TARGETS[abi]
    work = WORK / abi
    if work.exists():
        shutil.rmtree(work)
    work.mkdir()
    src = work / "src"
    shutil.copytree(source / "src", src)
    for patch in ("zig-loader.patch", "android-seccomp-log.patch", "android-shutdown.patch"):
        run(["patch", "-p1", "-i", SUPPORT / patch], cwd=work)
    # Linker's .text address option is not supported by zig cc; use an
    # equivalent script. Align each segment, including on 16 KB Android pages.
    for suffix, addr in [("", address), ("-m32", address32)]:
        if addr:
            (src / f"loader{suffix}.ld").write_text(
                "ENTRY(_start)\n"
                "PHDRS { text PT_LOAD FLAGS(5); data PT_LOAD FLAGS(6); "
                "stack PT_GNU_STACK FLAGS(6); }\nSECTIONS {\n"
                f" . = {addr};\n .text : {{ *(.text*) }} :text\n"
                " .rodata : { *(.rodata*) } :text\n"
                " .ARM.extab : { *(.ARM.extab*) } :text\n"
                " .ARM.exidx : { *(.ARM.exidx*) } :text\n"
                " . = ALIGN(0x4000);\n .data : { *(.data*) *(.got*) } :data\n"
                " .bss : { *(.bss*) *(COMMON) } :data\n"
                " /DISCARD/ : { *(.eh_frame*) *(.comment) }\n}\n")
    cc = work / "cc"
    executable(cc, f'''#!/usr/bin/env python3
import os, sys
args = sys.argv[1:]
target = {target!r}
if '-m32' in args:
    args.remove('-m32')
    target = {target32!r}
os.execv({str(zig)!r}, ['zig', 'cc', '-target', target, *args])
''')
    # GNU objcopy on the host need not support cross-architecture ELF.
    # Assemble incbin into the target object instead, with identical symbols.
    embed = work / "embed"
    executable(embed, f'''#!/usr/bin/env python3
import hashlib, pathlib, subprocess, sys
src, dst = sys.argv[-2:]
sym = src.replace('.', '_').replace('-', '_').replace('/', '_')
asm = pathlib.Path(dst + '.S')
# Include content digest: Zig's cache does not track .incbin dependencies.
asm.write_text('/* ' + hashlib.sha256(pathlib.Path(src).read_bytes()).hexdigest() + ' */\\n'
    + '.section .rodata\\n.global _binary_' + sym + '_start\\n.global _binary_' + sym + '_end\\n'
    + '_binary_' + sym + '_start:\\n.incbin "' + src + '"\\n_binary_' + sym + '_end:\\n'
    + '.section .note.GNU-stack,"",%progbits\\n')
subprocess.run([{str(zig)!r}, 'cc', '-target', {target!r}, '-c', str(asm), '-o', dst], check=True)
''')
    strip = work / "strip"
    executable(strip, f'''#!/usr/bin/env python3
import os, subprocess, sys
path = sys.argv[1]
subprocess.run([{str(zig)!r}, 'objcopy', '--strip-all', path, path + '.stripped'], check=True)
os.replace(path + '.stripped', path)
''')
    # Upstream uses gawk strtonum(); keep the build independent of host awk.
    info = work / "loader-info"
    executable(info, '''#!/usr/bin/env python3
import re, subprocess, sys
symbols = subprocess.check_output(['readelf', '-s', sys.argv[1]], text=True)
values = {}
for line in symbols.splitlines():
    fields = line.split()
    if fields and fields[-1] in ('_start', 'pokedata_workaround'):
        values[fields[-1]] = int(fields[1], 16)
print('#include <unistd.h>')
print('const ssize_t offset_to_pokedata_workaround=%d;' %
      (values['pokedata_workaround'] - values['_start']))
''')
    makefile = src / "GNUmakefile"
    makefile.write_text(makefile.read_text().replace(
        "readelf -s $< | awk -f loader/loader-info.awk > $@", f"{info} $< > $@"))
    run([zig, "cc", "-target", target, "-O2", "-c", talloc / "talloc.c",
         f"-I{talloc}", "-o", work / "talloc.o"])
    run([zig, "ar", "rcs", work / "libtalloc.a", work / "talloc.o"])
    version_script = work / "version"
    version_text = f'#undef VERSION\n#define VERSION "termux-{PROOT_COMMIT[:12]}-android2"'
    executable(version_script, f"#!/usr/bin/env python3\nprint({version_text!r})\n")
    # musl uses SYS_fork on x86_64, but Android app policies only allow
    # clone there. Preserve musl's atfork/lock/TLS handling and change only
    # the syscall choice; the tracer's own children are not traced yet.
    musl = zig.parent / "lib/libc/musl"
    fork_source = work / "musl-Fork.c"
    fork_source.write_text((musl / "src/process/_Fork.c").read_text().replace(
        "#ifdef SYS_fork", "#if 0 /* use Android-allowed clone */"))
    arch = {"x86_64": "x86_64", "arm64-v8a": "aarch64", "armeabi-v7a": "arm"}[abi]
    run([zig, "cc", "-target", target, "-O2", "-c", fork_source,
         f"-I{musl}/src/internal", f"-I{musl}/arch/{arch}",
         f"-I{musl}/arch/generic", f"-I{musl}/src/include",
         "-o", work / "musl-Fork.o"])
    flags = (f"-O2 -D_LARGEFILE64_SOURCE -include {SUPPORT}/musl-compat.h "
             f"-I{talloc} -I{SUPPORT} -fno-ident -ffile-prefix-map={WORK}=/build")
    run(["make", "-j4", "proot", f"CC={cc}", f"STRIP={strip}", f"OBJCOPY={embed}",
         "OBJDUMP=objdump", f"CFLAGS={flags}",
         f"LDFLAGS=-static {work}/musl-Fork.o -L{work} -ltalloc -Wl,-z,noexecstack,-z,max-page-size=16384",
         f"CHECK_VERSION={version_script}"],
        cwd=src)
    output = ROOT / f"app/src/main/jniLibs/{abi}/libproot.so"
    run([zig, "objcopy", "--strip-all", src / "proot", output])
    output.chmod(0o755)
    print(f"{abi}: {hashlib.sha256(output.read_bytes()).hexdigest()}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("abis", nargs="*", metavar="ABI", help=", ".join(TARGETS))
    parser.add_argument("--zig", type=Path, help="existing Zig 0.13.0 executable")
    args = parser.parse_args()
    for abi in args.abis:
        if abi not in TARGETS:
            parser.error(f"Unsupported ABI: {abi}")
    WORK.mkdir(parents=True, exist_ok=True)
    zig = args.zig.resolve() if args.zig else WORK / "package/zig"
    if not zig.exists():
        archive = WORK / "zig.tgz"
        urllib.request.urlretrieve(ZIG_URL, archive)
        verify(archive, ZIG_SHA256)
        with tarfile.open(archive) as tar:
            tar.extractall(WORK)  # trusted archive verified by SHA-256 above
    version = subprocess.check_output([zig, "version"], text=True).strip()
    if version != "0.13.0":
        raise RuntimeError(f"Expected Zig 0.13.0, got {version}")
    source, static = WORK / "source", WORK / "static-source"
    checkout("termux/proot", PROOT_COMMIT, source)
    checkout("proot-me/proot-static-build", STATIC_COMMIT, static)
    archive = static / "packages/talloc-2.1.1.tar.gz"
    verify(archive, TALLOC_SHA256)
    with tarfile.open(archive) as tar:
        tar.extractall(WORK)  # trusted archive verified by SHA-256 above
    talloc = WORK / "talloc-2.1.1"
    shutil.copy(SUPPORT / "talloc-replace.h", talloc / "replace.h")
    for abi in args.abis or TARGETS:
        build(abi, zig, source, talloc)


if __name__ == "__main__":
    main()
