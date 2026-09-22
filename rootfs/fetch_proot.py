"""Download Termux proot .deb (aarch64) and extract the static binary (fresh code)."""
import re
import os
import subprocess
import tarfile
import urllib.request

MIRRORS = [
    "http://packages.termux.org/apt/termux-main",
    "https://grimler.se/termux/termux-main",
    "https://termux.mentality.rip/termux-main",
]
UA = {"User-Agent": "Debian APT-HTTP/1.3 (arm64)"}


def fetch(url):
    req = urllib.request.Request(url, headers=UA)
    return urllib.request.urlopen(req, timeout=120).read()


def main():
    print("fetching Packages index...", flush=True)
    raw = None
    errs = []
    mirror = None
    for m in MIRRORS:
        try:
            raw = fetch(f"{m}/dists/stable/main/binary-aarch64/Packages")
            mirror = m
            print("mirror OK:", m, flush=True)
            break
        except Exception as e:  # noqa: BLE001
            errs.append(f"{m}: {e!r}")
    assert raw is not None, "all mirrors failed: " + "; ".join(errs)
    text = raw.decode("utf-8", "replace")
    deb = None
    for b in text.split("\n\n"):
        if re.search(r"^Package:\s*proot\s*$", b, re.M):
            m = re.search(r"^Filename:\s*(\S+)\s*$", b, re.M)
            if m:
                deb = m.group(1)
                break
    assert deb, "proot entry not found in Packages index"
    url = f"{mirror}/{deb}"
    print("downloading", url, flush=True)
    open("/tmp/proot.deb", "wb").write(fetch(url))
    out = subprocess.run(["ar", "t", "/tmp/proot.deb"], capture_output=True, text=True).stdout
    member = [ln for ln in out.splitlines() if ln.startswith("data.tar")][0]
    subprocess.run(["ar", "p", "/tmp/proot.deb", member], stdout=open("/tmp/data.tar", "wb"), check=True)
    tf = tarfile.open("/tmp/data.tar")
    names = [n for n in tf.getnames() if n.endswith("bin/proot")]
    print("candidates:", names[:5])
    src = [n for n in names if "proot-distro" not in n and "termux-auth" not in n][0]
    f = tf.extractfile(src)
    assert f is not None
    open("/tmp/proot", "wb").write(f.read())
    os.chmod("/tmp/proot", 0o755)
    print("saved /tmp/proot", os.path.getsize("/tmp/proot"), "bytes")


if __name__ == "__main__":
    main()
