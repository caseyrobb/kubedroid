"""Spec 11 stage 3: dump and diff the APK Signing Block pairs of two builds.

Locates the EOCD by tail scan, then the 'APK Sig Block 42' block before the
central directory, and compares each id-length-value pair. Nondeterminism
suspects: SDKP (0x504b4453, AGP dependenciesInfo — must be absent since the
opt-out), v2 (0x7109871a), v3 (0xf05368c0), 'Brew' (0x42726577).

Usage: apk-sigdiff.py A.apk B.apk
"""
import struct
import sys


def _eocd(data):
    i = len(data) - 22
    while i >= 0 and data[i:i + 4] != b"PK\x05\x06":
        i -= 1
    assert i >= 0, "EOCD not found"
    return struct.unpack("<II", data[i + 12:i + 20])  # cd_size, cd_off


def pairs(path):
    data = open(path, "rb").read()
    _, cd_off = _eocd(data)
    assert data[cd_off - 16:cd_off] == b"APK Sig Block 42", \
        "no v2+ signing block"
    size2 = struct.unpack("<Q", data[cd_off - 24:cd_off - 16])[0]
    # The leading size field repeats size2; locate it by scanning back.
    s1 = data.rfind(struct.pack("<Q", size2), 0, cd_off - 24)
    assert s1 >= 0, "signing block leading size not found"
    off = s1 + 8
    end = cd_off - 24
    out = {}
    while off < end:
        plen = struct.unpack("<Q", data[off:off + 8])[0]
        pid = struct.unpack("<I", data[off + 8:off + 12])[0]
        out[pid] = data[off + 12:off + plen]
        off += 8 + plen
    return out


def main():
    pa = pairs(sys.argv[1])
    pb = pairs(sys.argv[2])
    print("pair ids A:", [hex(k) for k in pa])
    print("pair ids B:", [hex(k) for k in pb])
    rc = 0
    for k in sorted(set(pa) | set(pb)):
        da, db = pa.get(k), pb.get(k)
        if da == db:
            print(f"{hex(k)} identical ({len(da or b'')} bytes)")
            continue
        rc = 1
        print(f"{hex(k)} DIFFERS lenA={len(da or b'')} lenB={len(db or b'')}")
        if da and db:
            n = min(len(da), len(db))
            d = [i for i in range(n) if da[i] != db[i]]
            if d:
                lo = d[0]
                print(f"   diffs: {len(d)}, first at {lo}")
                print("   A:", da[lo:lo + 24].hex())
                print("   B:", db[lo:lo + 24].hex())
    return rc


if __name__ == "__main__":
    sys.exit(main())
