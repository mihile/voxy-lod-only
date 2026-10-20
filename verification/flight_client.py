"""Loopback-only 1.20.1 clients: real player/chunk tracking and VoxyServer transport, no renderer."""
import base64, io, socket, struct, threading, time, zlib

def vi(n):
    out=bytearray()
    while True:
        b=n&127;n>>=7;out.append(b|(128 if n else 0))
        if not n:return bytes(out)

def readvi(stream):
    result=0
    for i in range(10):
        b=stream.read(1)
        if not b:raise EOFError()
        value=b[0];result|=(value&127)<<(7*i)
        if not value&128:return result
    raise ValueError('invalid varint')

def string(value):
    data=value.encode();return vi(len(data))+data

def readstr(stream):return stream.read(readvi(stream)).decode()

class FlightClient:
    def __init__(self,name,port,ids,login=None):
        self.name=name;self.ids=ids;self.compression=False;self.alive=True;self.error=None
        self.login=login or {};self.lock=threading.RLock();self.position=None;self.teleports=0;self.chunks=0
        self.lod_bytes=0;self.lod_sections=0;self.channels={};self.play=threading.Event();self.last_packets=[]
        self.sock=socket.create_connection(('127.0.0.1',port),timeout=10)
        self.session=str(self.sock.getsockname()[1])
        self.sock.settimeout(120);self.stream=self.sock.makefile('rb')
        self.send(0,vi(763)+string('127.0.0.1')+struct.pack('>H',port)+vi(2))
        self.send(0,string(name)+b'\x00')
        self.thread=threading.Thread(target=self.read,daemon=True);self.thread.start()
        if not self.play.wait(60):self.close();raise RuntimeError('login failed: '+str(self.error))
        if self.error:self.close();raise RuntimeError(self.error)
        self.send(ids['settings'],string('en_us')+b'\x0a'+vi(0)+b'\x01\x7f'+vi(1)+b'\x00\x01')
        channels=['lod_protocol','lod_server_settings','lod_hash_sync_settings','lod_stream_session',
                  'lod_preserialized','lod_bulk','lod_section','lod_clear']
        self.payload('minecraft:register',b'\x00'.join(('voxyserver:'+s).encode() for s in channels))
        self.payload('voxyserver:lod_handshake',vi(2))
        self.send(ids['abilities'],b'\x02')

    def send(self,packet,data=b''):
        body=vi(packet)+data
        with self.lock:
            if self.compression:body=vi(0)+body
            self.sock.sendall(vi(len(body))+body)

    def payload(self,channel,data):self.send(self.ids['payloadReply'],string(channel)+data)

    def read(self):
        logged=False
        try:
            while self.alive:
                length=readvi(self.stream);data=self.stream.read(length)
                if len(data)!=length:raise EOFError()
                buf=io.BytesIO(data)
                if self.compression:
                    size=readvi(buf);raw=buf.read();buf=io.BytesIO(zlib.decompress(raw) if size else raw)
                packet=readvi(buf)
                self.last_packets=(self.last_packets+[(packet,len(data))])[-20:]
                if not logged:
                    if packet==3:self.compression=True
                    elif packet==4:
                        transaction=readvi(buf);channel=readstr(buf);response=self.login.get(channel)
                        self.send(2,vi(transaction)+(b'\x01'+base64.b64decode(response) if response is not None else b'\x00'))
                    elif packet==2:logged=True
                    elif packet==0:raise RuntimeError('login disconnect: '+readstr(buf))
                    continue
                if packet==self.ids['join']:self.play.set()
                elif packet==self.ids['keepalive']:self.send(self.ids['keepaliveReply'],buf.read(8))
                elif packet==self.ids['teleport']:
                    x,y,z,yaw,pitch,flags=struct.unpack('>dddffB',buf.read(33));teleport=readvi(buf)
                    old=self.position or (0,0,0)
                    self.position=(x+old[0] if flags&1 else x,y+old[1] if flags&2 else y,z+old[2] if flags&4 else z)
                    self.teleports+=1;self.send(self.ids['teleportReply'],vi(teleport))
                elif packet==self.ids['chunk']:self.chunks+=1
                elif packet==self.ids['disconnect']:raise RuntimeError('play disconnect: '+readstr(buf))
                elif packet==self.ids['payload']:
                    channel=readstr(buf);self.channels[channel]=self.channels.get(channel,0)+1
                    if channel=='voxyserver:lod_stream_session':
                        dimension=readstr(buf);epoch=readvi(buf);request=readvi(buf);required=buf.read(1)!=b'\x00'
                        if required:self.payload('voxyserver:lod_manifest',string(dimension)+vi(epoch)+vi(request)+b'\x01'+vi(0))
                    elif channel=='voxyserver:lod_preserialized':
                        epoch=readvi(buf);batch=readvi(buf);count=readvi(buf);length=readvi(buf)
                        raw=buf.read(length)
                        if len(raw)!=length:raise ValueError('truncated LOD transport')
                        self.lod_bytes+=length;self.lod_sections+=count
                        self.payload('voxyserver:lod_batch_result',vi(epoch)+vi(batch)+b'\x00')
        except Exception as exc:
            if self.alive:self.error=repr(exc)
        finally:self.play.set()

    def move(self,x,y,z):
        self.position=(x,y,z)
        self.send(self.ids['position'],struct.pack('>ddd?',x,y,z,False))

    def close(self):
        self.alive=False
        try:self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:pass
        self.sock.close()

    def result(self):return dict(name=self.name,chunks=self.chunks,lod_bytes=self.lod_bytes,
                                lod_sections=self.lod_sections,channels=self.channels,error=self.error,
                                position=self.position,teleports=self.teleports,last_packets=self.last_packets,session=self.session)
