"""Read-only flash backup through the installed esptool. Does reset into/out of bootloader.
No erase/write-flash operations. Run with PlatformIO's Python, never concurrently on the same port.
"""
import argparse
import hashlib
import json
import sys
import site
import struct
from pathlib import Path
sys.path.insert(0,str(Path.home()/'.platformio/packages/tool-esptoolpy'))
site.addsitedir(str(Path.home()/'.platformio/packages/tool-esptoolpy/_contrib'))
import esptool

def read_bounded(chip, offset, length):
    # Espressif READ_FLASH supports a configurable in-flight window. Stock esptool uses 64;
    # use ONE 1-KiB packet here to avoid overflowing the Windows/USB receive path.
    chip.check_command('read flash (window=1)', chip.ESP_READ_FLASH,
                       struct.pack('<IIII',offset,length,1024,1))
    data=bytearray()
    while len(data)<length:
        packet=chip.read()
        if len(packet)!=min(1024,length-len(data)): raise ValueError('Invalid backup packet length')
        data.extend(packet)
        chip.write(struct.pack('<I',len(data)))
    digest=chip.read()
    if len(digest)!=16 or hashlib.md5(data).digest()!=digest: raise ValueError('Backup digest mismatch')
    return bytes(data)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--port',required=True)
    p.add_argument('--output',required=True)
    p.add_argument('--baud',type=int,default=230400)
    p.add_argument('--resume',action='store_true')
    args=p.parse_args()
    output=Path(args.output)
    if output.exists() and not args.resume: raise ValueError('Refusing to overwrite a backup')
    output.parent.mkdir(parents=True,exist_ok=True)
    chip=esptool.detect_chip(args.port,115200)
    chip=chip.run_stub()
    try:
        mac=':'.join(f'{byte:02x}' for byte in chip.read_mac())
        metadata=output.with_suffix(output.suffix+'.identity.json')
        if metadata.exists() and json.loads(metadata.read_text())['mac']!=mac:
            raise ValueError('Backup chip identity mismatch')
        metadata.write_text(json.dumps({'port':args.port,'mac':mac,'complete':False}),encoding='utf-8')
        chip.change_baud(args.baud)
        chip._port.timeout=3
        chip._port.write_timeout=3
        chip.flash_spi_attach(0)
        print('Flash ID:',hex(chip.flash_id()),flush=True)
        size=0x400000 # Board/flash probe reports 4 MiB. Check ID capacity rather than assume it.
        if (chip.flash_id()>>16)&0xff != 0x16: raise ValueError('Expected verified 4 MiB flash')
        start=output.stat().st_size if output.exists() else 0
        if start%65536 or start>size: raise ValueError('Invalid partial backup length')
        digest=hashlib.sha256()
        if start:
            prior=output.read_bytes()
            if read_bounded(chip,0,4096)!=prior[:4096]: raise ValueError('Partial backup belongs to a different image')
            digest.update(prior)
        with output.open('ab' if start else 'xb') as stream:
            for offset in range(start,size,65536):
                data=read_bounded(chip,offset,65536)
                if len(data)!=65536: raise ValueError('Incomplete backup block')
                stream.write(data); stream.flush(); digest.update(data)
                print(f'{args.port}: backed up {offset+len(data)}/{size}',flush=True)
        print('COMPLETE SHA256',digest.hexdigest(),flush=True)
        metadata.write_text(json.dumps({'port':args.port,'mac':mac,'complete':True,'size':size,
                                       'sha256':digest.hexdigest()}),encoding='utf-8')
    finally:
        chip.hard_reset()
        chip._port.close()

if __name__=='__main__': main()
