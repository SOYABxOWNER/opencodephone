#!/usr/bin/env python3
"""Build per-architecture toolset tarballs for OpenCodePhone bootstrap.

Instead of running apt-get inside proot on the device (fragile on Android 12+
because of the phantom process killer), the app downloads a pre-resolved,
digest-pinned tarball of .deb payloads and extracts it app-side. This script
resolves the dependency closure from the Debian trixie index, downloads the
debs, extracts their payloads and packs them into toolset-<arch>.tar.gz.

Tarball layout (relative to rootfs root):
  usr/... lib/... etc/...                      — merged package payloads
  var/lib/dpkg/status.d/<package>              — dpkg status stanza (merged by the app)
  var/lib/dpkg/info/<package>.list             — dpkg file lists

Usage: python3 build_toolset.py <arch> [<arch> ...]
"""
import concurrent.futures as futures
import gzip
import hashlib
import io
import os
import shutil
import subprocess
import sys
import tarfile
import urllib.request

BASE = "http://deb.debian.org/debian"
SUITE = "trixie"
# The pre-resolved toolset installed at bootstrap. Seeds beyond the original
# git/curl/ripgrep core are requested for the on-device experience: jq and
# fd-find (as `fdfind`) for data/file lookup, tree/zip/unzip for archives and
# inspection, python3 for agent-written scripts and quick automation.
SEEDS = [
    "git", "curl", "ripgrep", "less", "procps", "ca-certificates",
    "jq", "fd-find", "tree", "zip", "unzip", "python3",
]
WORK = os.environ.get("TOOLSET_WORK", os.path.join(os.path.dirname(__file__), ".work"))
OUT = os.environ.get("TOOLSET_OUT", os.path.join(os.path.dirname(__file__), ".out"))


def fetch(url, binary=True):
    req = urllib.request.Request(url, headers={"User-Agent": "opencodephone-toolset/0.1"})
    with urllib.request.urlopen(req, timeout=180) as r:
        return r.read() if binary else r


def load_index(arch):
    # Debian archive paths use "armhf" for the dpkg architecture "arm".
    index_arch = {"arm": "armhf"}.get(arch, arch)
    url = f"{BASE}/dists/{SUITE}/main/binary-{index_arch}/Packages.gz"
    raw = gzip.decompress(fetch(url))
    pkgs = {}
    for stanza in raw.decode("utf-8", "replace").split("\n\n"):
        fields = {}
        last = None
        for line in stanza.splitlines():
            if line.startswith((" ", "\t")) and last:
                fields[last] += "\n" + line
            elif ":" in line:
                k, _, v = line.partition(":")
                last = k.strip()
                fields[last] = v.strip()
        name = fields.get("Package")
        if name:
            pkgs.setdefault(name, []).append(fields)
    return pkgs


def dep_names(field):
    """Split a Depends field into list of lists of candidate package names."""
    out = []
    for clause in field.split(","):
        clause = clause.strip()
        if not clause:
            continue
        alts = []
        for alt in clause.split("|"):
            alt = alt.strip()
            name = alt.split()[0] if alt.split() else alt
            if ":" in name:  # arch qualifier like libc6:arm64
                name = name.split(":")[0]
            alts.append(name)
        out.append(alts)
    return out


def provides_map(pkgs):
    provides = {}
    for name, stanzas in pkgs.items():
        for s in stanzas:
            for prov in s.get("Provides", "").split(","):
                prov = prov.strip().split()[0] if prov.strip() else ""
                if prov:
                    provides.setdefault(prov, name)
    return provides


def resolve(pkgs, provides, seeds):
    chosen = {}
    missing = []
    queue = list(seeds)
    while queue:
        name = queue.pop(0)
        if name in chosen:
            continue
        stanzas = pkgs.get(name)
        if not stanzas:
            real = provides.get(name)
            stanzas = pkgs.get(real) if real else None
            if not stanzas:
                missing.append(name)
                continue
            name = real
            if name in chosen:
                continue
        s = max(stanzas, key=lambda x: x.get("Version", ""))
        chosen[name] = s
        dep_fields = s.get("Pre-Depends", "") + "," + s.get("Depends", "")
        for alt in dep_names(dep_fields):
            pick = None
            for cand in alt:
                if cand in pkgs or cand in provides:
                    pick = cand
                    break
            if pick is None:
                missing.append(f"{name} -> {alt}")
            elif pick not in chosen:
                queue.append(pick)
    return chosen, missing


def download_all(arch, chosen):
    cache = os.path.join(WORK, f"debs-{arch}")
    os.makedirs(cache, exist_ok=True)
    paths = {}

    def grab(item):
        name, s = item
        fn = os.path.basename(s["Filename"])
        dest = os.path.join(cache, fn)
        if not os.path.exists(dest):
            data = fetch(f"{BASE}/{s['Filename']}")
            digest = hashlib.sha256(data).hexdigest()
            if digest != s["SHA256"]:
                raise RuntimeError(f"{name}: sha256 mismatch for {fn}")
            with open(dest, "wb") as f:
                f.write(data)
        return name, dest

    with futures.ThreadPoolExecutor(max_workers=8) as pool:
        for name, dest in pool.map(grab, chosen.items()):
            paths[name] = dest
    return paths


def extract_deb(path):
    """Return (control_fields, data_tar_bytes_stream)."""
    raw = open(path, "rb").read()
    # ar archive: "!<arch>\n" then 60-byte headers
    assert raw.startswith(b"!<arch>\n"), path
    pos = 8
    control, data = None, None
    while pos < len(raw):
        hdr = raw[pos:pos + 60]
        size = int(hdr[48:58].decode().strip())
        name = hdr[0:16].decode().strip()
        member = raw[pos + 60:pos + 60 + size]
        pos += 60 + size + (size % 2)
        if name in ("debian-binary",):
            continue
        if name.startswith("control.tar"):
            control = member
        elif name.startswith("data.tar"):
            data = member
    return control, data


def open_tar(member):
    if member[:2] == b"\x1f\x8b":
        return tarfile.open(fileobj=io.BytesIO(member), mode="r:gz")
    if member[:6] == b"\xfd7zXZ\x00":
        import lzma
        return tarfile.open(fileobj=io.BytesIO(lzma.decompress(member)), mode="r:")
    raise RuntimeError("unknown data.tar compression")


def build_arch(arch):
    print(f"=== {arch}: index...")
    pkgs = load_index(arch)
    provides = provides_map(pkgs)
    chosen, missing = resolve(pkgs, provides, SEEDS)
    if missing:
        print(f"  MISSING (ignored if only optional deps): {sorted(set(missing))}")
    print(f"  resolved {len(chosen)} packages")
    paths = download_all(arch, chosen)

    root = os.path.join(WORK, f"toolset-{arch}")
    if os.path.exists(root):
        shutil.rmtree(root)
    os.makedirs(root)
    status_dir = os.path.join(root, "var/lib/dpkg/status.d")
    info_dir = os.path.join(root, "var/lib/dpkg/info")
    os.makedirs(status_dir)
    os.makedirs(info_dir)

    for name, s in sorted(chosen.items()):
        control, data = extract_deb(paths[name])
        fields = {}
        with open_tar(control) as t:
            member = t.extractfile("./control")
            text = member.read().decode("utf-8", "replace")
        for line in text.splitlines():
            if ":" in line and not line.startswith((" ", "\t")):
                k, _, v = line.partition(":")
                fields[k.strip()] = v.strip()
        keep = ["Package", "Version", "Architecture", "Multi-Arch", "Depends",
                "Pre-Depends", "Provides", "Essential", "Source", "Origin"]
        stanza = "\n".join(f"{k}: {fields[k]}" for k in keep if k in fields)
        stanza += "\nStatus: install ok installed\n"
        with open(os.path.join(status_dir, name), "w") as f:
            f.write(stanza)

        file_list = []
        with open_tar(data) as t:
            for m in t.getmembers():
                t.extract(m, root, filter="fully_trusted")
                p = m.name.lstrip("./").rstrip("/")
                if p and m.name.startswith("./"):
                    file_list.append(p)
        with open(os.path.join(info_dir, f"{name}.list"), "w") as f:
            f.write("\n".join("/" + p for p in file_list) + "\n")
        print(f"  + {name} {fields.get('Version', '?')}")

    with open(os.path.join(root, "var/lib/dpkg/arch"), "w") as f:
        f.write(arch + "\n")

    os.makedirs(OUT, exist_ok=True)
    out_path = os.path.join(OUT, f"toolset-{arch}.tar.gz")
    with tarfile.open(out_path, "w:gz", compresslevel=9) as t:
        for dirpath, _, files in os.walk(root):
            for fn in files:
                full = os.path.join(dirpath, fn)
                rel = os.path.relpath(full, root)
                t.add(full, arcname="./" + rel)
    digest = hashlib.sha256(open(out_path, "rb").read()).hexdigest()
    size = os.path.getsize(out_path) / 1e6
    print(f"  == {out_path}  {size:.1f} MB  sha256:{digest}")
    return arch, digest


if __name__ == "__main__":
    for arch in (sys.argv[1:] or ["arm64", "arm", "amd64"]):
        build_arch(arch)
