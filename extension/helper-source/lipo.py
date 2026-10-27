# Joins Mach-O slices into one universal (fat) binary, like Apple's lipo -create.
import struct, sys
out, slices = sys.argv[1], sys.argv[2:]
CPU = {0x01000007: (3, 12), 0x0100000c: (0, 14)}  # x86_64 -> (subtype ALL, align 2^12), arm64 -> (ALL, 2^14)
data = [open(p, 'rb').read() for p in slices]
hdr = struct.pack('>II', 0xcafebabe, len(data))
off = 4096 if len(data) else 0
arch, body, pos = b'', [], 8 + 20 * len(data)
for d in data:
    cputype, sub = struct.unpack('<II', d[4:12])
    align = CPU[cputype][1]
    off = (max(off, pos) + (1 << align) - 1) & ~((1 << align) - 1)
    arch += struct.pack('>IIIII', cputype, sub, off, len(d), align)
    body.append((off, d)); pos = off + len(d); off = pos
buf = bytearray(pos)
buf[:8 + len(arch)] = hdr + arch
for o, d in body: buf[o:o + len(d)] = d
open(out, 'wb').write(buf)
