"""Offline DEX declaration inventory; no application code is executed.

Usage: python scripts/inspect_dex.py APK REGEX [--members]
Only declarations are inspected; this does not establish runtime hook coverage.
"""
import argparse
import json
import re
import struct
import zipfile


def inventory(data, pattern, members, implements=None, extends=None):
    def u32(off):
        return struct.unpack_from('<I', data, off)[0]

    def uleb(off):
        value, shift = 0, 0
        while True:
            byte = data[off]
            off += 1
            value |= (byte & 127) << shift
            if not byte & 128:
                return value, off
            shift += 7

    strings = []
    for i in range(u32(56)):
        off = u32(u32(60) + i * 4)
        _, off = uleb(off)
        strings.append(data[off:data.index(0, off)].decode('utf-8', errors='replace'))
    types = [strings[u32(u32(68) + i * 4)] for i in range(u32(64))]
    fields = []
    methods = []
    if members:
        for i in range(u32(80)):
            cls, typ, name = struct.unpack_from('<HHI', data, u32(84) + i * 8)
            fields.append((types[cls], strings[name], types[typ]))
        for i in range(u32(88)):
            cls, proto, name = struct.unpack_from('<HHI', data, u32(92) + i * 8)
            off = u32(76) + proto * 12
            ret, params_off = u32(off + 4), u32(off + 8)
            params = [] if not params_off else [types[struct.unpack_from('<H', data, params_off + 4 + j * 2)[0]] for j in range(u32(params_off))]
            methods.append((types[cls], strings[name], '(' + ''.join(params) + ')' + types[ret]))
    for i in range(u32(96)):
        off = u32(100) + i * 32
        name = types[u32(off)]
        superclass_index = u32(off + 8)
        superclass = None if superclass_index == 0xffffffff else types[superclass_index]
        interfaces_off = u32(off + 12)
        interfaces = [] if not interfaces_off else [types[struct.unpack_from('<H', data, interfaces_off + 4 + j * 2)[0]] for j in range(u32(interfaces_off))]
        if not pattern.search(name):
            continue
        if implements and implements not in interfaces:
            continue
        if extends and extends != superclass:
            continue
        result = {'class': name, 'superclass': superclass, 'interfaces': interfaces}
        if members:
            result['fields'], result['methods'] = [], []
            ptr = u32(off + 24)
            if ptr:
                counts = []
                for _ in range(4):
                    count, ptr = uleb(ptr)
                    counts.append(count)
                for count in counts[:2]:
                    idx = 0
                    for _ in range(count):
                        diff, ptr = uleb(ptr)
                        flags, ptr = uleb(ptr)
                        idx += diff
                        result['fields'].append({'name': fields[idx][1], 'type': fields[idx][2], 'flags': flags})
                for count in counts[2:]:
                    idx = 0
                    for _ in range(count):
                        diff, ptr = uleb(ptr)
                        flags, ptr = uleb(ptr)
                        code, ptr = uleb(ptr)
                        idx += diff
                        result['methods'].append({'name': methods[idx][1], 'descriptor': methods[idx][2], 'flags': flags, 'codeOffset': code})
        yield result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('apk')
    parser.add_argument('pattern')
    parser.add_argument('--members', action='store_true')
    parser.add_argument('--implements')
    parser.add_argument('--extends')
    args = parser.parse_args()
    with zipfile.ZipFile(args.apk) as package:
        for name in package.namelist():
            if re.fullmatch(r'classes\d*\.dex', name):
                for item in inventory(package.read(name), re.compile(args.pattern), args.members, args.implements, args.extends):
                    print(json.dumps({'dex': name, **item}, ensure_ascii=False))
