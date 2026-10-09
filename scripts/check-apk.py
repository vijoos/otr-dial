"""Fail CI before publishing an APK with invalid Android resource packaging."""
import struct
import sys
import zipfile


def check(path):
    with zipfile.ZipFile(path) as apk, open(path, 'rb') as raw:
        assert apk.testzip() is None, 'Corrupt ZIP entry'
        resources = apk.getinfo('resources.arsc')
        assert resources.compress_type == zipfile.ZIP_STORED, 'resources.arsc must be uncompressed for target API 30+'
        for entry in apk.infolist():
            if entry.filename == 'resources.arsc' or entry.filename.endswith('.so'):
                assert entry.compress_type == zipfile.ZIP_STORED, f'{entry.filename} must be uncompressed'
                raw.seek(entry.header_offset + 26)
                name_len, extra_len = struct.unpack('<HH', raw.read(4))
                offset = entry.header_offset + 30 + name_len + extra_len
                alignment = 16384 if entry.filename.endswith('.so') else 4
                assert offset % alignment == 0, f'{entry.filename} is not {alignment}-byte aligned'
    print('APK ZIP integrity, resource compression and alignment passed')

if __name__ == '__main__':
    check(sys.argv[1])
