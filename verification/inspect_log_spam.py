"""Read-only live log/resource investigation; writes evidence only inside project build/."""
from pathlib import Path
from collections import Counter,defaultdict
from datetime import datetime
import re,json,zipfile,gzip,struct,hashlib

ROOT=Path(__file__).resolve().parents[1]
SERVER=Path(r'C:\Prominence_2_4.1.3')
output=ROOT/'build'/('log-spam-'+datetime.now().strftime('%Y%m%d-%H%M%S'));output.mkdir(parents=True)
lines=(SERVER/'logs/latest.log').read_text(encoding='utf-8',errors='replace').splitlines()
entries=[]
for number,line in enumerate(lines,1):
    m=re.match(r'\[(\d\d:\d\d:\d\d)\] \[(.*)/(INFO|WARN|ERROR)\]: (.*)',line)
    if m:
        timestamp,thread,level,message=m.groups()
        second=sum(int(v)*factor for v,factor in zip(timestamp.split(':'),(3600,60,1)))
        entries.append(dict(line=number,time=timestamp,second=second,thread=thread,level=level,message=message))
last=max(e['second'] for e in entries)
recent=[e for e in entries if e['second']>=last-300]
def normalized(message):return re.sub(r'class_2338\{[^}]*\}','<block-position>',message)
top=Counter(normalized(e['message']) for e in recent)
families={}
for token in ['Empty or non-existent pool','Skipping Entity with id','Tried to access a block entity','section raced to into save queue','LOD Only job finished']:
    chosen=[e for e in entries if token in e['message']]
    families[token]=dict(total=len(chosen),recent=len([e for e in chosen if e in recent]),
        threads=dict(Counter(e['thread'] for e in chosen)),first=chosen[:3],last=chosen[-3:])

def nbt(data):
    if data[:2]==b'\x1f\x8b':data=gzip.decompress(data)
    view=memoryview(data);offset=0
    def take(n):
        nonlocal offset
        if n<0 or offset+n>len(view):raise ValueError('NBT bounds')
        b=view[offset:offset+n];offset+=n;return b
    def number(fmt):return struct.unpack(fmt,take(struct.calcsize(fmt)))[0]
    def string():return bytes(take(number('>H'))).decode('utf-8',errors='replace')
    def payload(kind):
        if kind in (1,2,3,4,5,6):return number({1:'>b',2:'>h',3:'>i',4:'>q',5:'>f',6:'>d'}[kind])
        if kind==7:take(number('>i'));return None
        if kind==8:return string()
        if kind==9:
            subtype=number('>B');length=number('>i')
            if not 0<=length<=1_000_000:raise ValueError('NBT list size')
            return [payload(subtype) for _ in range(length)]
        if kind==10:
            result={}
            while (subtype:=number('>B'))!=0:
                name=string();result[name]=payload(subtype)
            return result
        if kind in (11,12):take(number('>i')*(4 if kind==11 else 8));return None
        raise ValueError('NBT kind '+str(kind))
    if number('>B')!=10:raise ValueError('NBT root')
    string();return payload(10)

resources=[];parse_errors=[];installed=[];scanned=Counter();ids=set()
def inspect(name,raw,archive):
    if name.endswith('.nbt') and '/structures/' in name:
        scanned['templates']+=1
        try:
            if raw[:2]==b'\x1f\x8b':raw=gzip.decompress(raw)
            if not any(token in raw for token in (b'\x08\x00\x04pool\x00\x00',b'\x08\x00\x04pool\x00\x0aminecraft:',b'quark:glass_frame',b'guardvillagers:guard')):return
            data=nbt(raw);bad=[];entity=[]
            for block in data.get('blocks',[]):
                tag=block.get('nbt') or {}
                if tag.get('pool') in ('','minecraft:'):
                    bad.append(dict(pos=block.get('pos'),nbt=tag))
            for obj in data.get('entities',[]):
                tag=obj.get('nbt') or {}
                if tag.get('id') in ('quark:glass_frame','guardvillagers:guard'):entity.append(tag.get('id'))
            if bad or entity:resources.append(dict(archive=str(archive),entry=name,bad_pools=bad,entity_ids=dict(Counter(entity))))
        except Exception as error:parse_errors.append(dict(archive=str(archive),entry=name,error=str(error)))
    elif name.endswith('.json') and '/worldgen/template_pool/' in name:
        scanned['pools']+=1
        try:
            data=json.loads(raw)
            if data.get('fallback') in ('','minecraft:'):
                resources.append(dict(archive=str(archive),entry=name,bad_fallback=data.get('fallback')))
        except Exception as error:parse_errors.append(dict(archive=str(archive),entry=name,error=str(error)))

archives=list((SERVER/'mods').glob('*.jar'))
for relative in ['config/paxi/datapacks','world/datapacks','datapacks','moonlight-global-datapacks']:
    folder=SERVER/relative
    if folder.exists():
        archives.extend(folder.glob('*.zip'))
        for name in folder.rglob('*'):
            if name.is_file() and name.suffix in ('.nbt','.json'):inspect(name.as_posix(),name.read_bytes(),folder)
for path in archives:
    with zipfile.ZipFile(path) as z:
        names=z.namelist()
        if 'fabric.mod.json' in names:
            try:
                metadata=json.loads(z.read('fabric.mod.json'));ids.add(metadata['id'])
                if metadata['id'] in ('voxy','voxyserver','voxy_lod_only'):
                    installed.append(dict(path=str(path),id=metadata['id'],version=metadata['version'],sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
            except (json.JSONDecodeError,KeyError):pass
        for name in names:
            if name.endswith('.nbt') and '/structures/' in name or name.endswith('.json') and '/worldgen/template_pool/' in name:
                inspect(name,z.read(name),path)

jar=Path.home()/'.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2/minecraft-merged-1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2.jar'
classes=[]
with zipfile.ZipFile(jar) as z:
    for name in z.namelist():
        if name.endswith('.class'):
            data=z.read(name)
            tokens=[t for t in ['Empty or non-existent pool','Skipping Entity with id','Tried to access a block entity'] if t.encode() in data]
            if tokens:
                target=output/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
                classes.append(dict(entry=name,messages=tokens))
summary=dict(captured_at=datetime.now().isoformat(),source=str(SERVER/'logs/latest.log'),snapshot_lines=len(lines),
    recent_start_seconds=last-300,recent_end_seconds=last,recent_top=top.most_common(12),families=families,
    installed=installed,scanned=dict(scanned),resource_summary=dict(Counter(Path(r['archive']).name for r in resources)),
    resources=resources,parse_errors=parse_errors,minecraft_classes=classes,
    quark_mod_id_present='quark' in ids,guardvillagers_mod_id_present='guardvillagers' in ids)
(output/'evidence.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in summary.items() if k not in ('resources','parse_errors','families')},ensure_ascii=False,indent=2))
print('Evidence:',output)
