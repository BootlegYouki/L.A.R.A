import socket,socketserver,time,threading
lock=threading.Lock()
fail_next=True
class Proxy(socketserver.BaseRequestHandler):
 def handle(self):
  global fail_next
  stream=self.request.makefile('rb')
  first=stream.readline()
  if not first:return
  headers=[];length=0
  while True:
   line=stream.readline()
   if line in (b'\r\n',b''):break
   if not line.lower().startswith(b'connection:'):headers.append(line)
   if line.lower().startswith(b'content-length:'):length=int(line.split(b':',1)[1])
  upload=first.startswith(b'POST /api/materials ')
  with lock:
   fail=upload and fail_next
   if fail:fail_next=False
  upstream=None if fail else socket.create_connection(('127.0.0.1',38080))
  try:
   if upstream:upstream.sendall(first+b''.join(headers)+b'Connection: close\r\n\r\n')
   remaining=length
   while remaining:
    chunk=stream.read(min(65536,remaining))
    if not chunk:raise IOError('client closed')
    if upstream:upstream.sendall(chunk)
    remaining-=len(chunk)
    if upload:time.sleep(len(chunk)/(4*1024*1024))
   if fail:
    body=b'{"error":{"code":"TEST_TRANSIENT_FAILURE","message":"Synthetic 503 for durable retry test"}}'
    self.request.sendall(b'HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json\r\nContent-Length: '+str(len(body)).encode()+b'\r\nConnection: close\r\n\r\n'+body)
    print('INJECTED 503 after reading full upload; bytes='+str(length),flush=True)
   else:
    while True:
     chunk=upstream.recv(65536)
     if not chunk:break
     self.request.sendall(chunk)
    if upload:print('FORWARDED successful retry; bytes='+str(length),flush=True)
  finally:
   if upstream:upstream.close()
class Server(socketserver.ThreadingTCPServer):allow_reuse_address=True;daemon_threads=True
with Server(('127.0.0.1',38082),Proxy) as server:
 print('Test proxy: 4 MiB/s upload throttle; one full-body 503 injection; upstream unchanged Mock Hub 38080',flush=True)
 server.serve_forever()
