#!/usr/bin/env python3
import http.server,json,pathlib,time
root=pathlib.Path(__file__).parent/'assets'
class Handler(http.server.SimpleHTTPRequestHandler):
 def __init__(self,*a,**kw):super().__init__(*a,directory=str(root),**kw)
 def do_GET(self):
  if self.path in ['/http-error','/truncated','/oversize','/slow']:
   data=(root/'newer.apk').read_bytes()
   if self.path=='/http-error':self.send_error(503);return
   self.send_response(200)
   if self.path!='/oversize':self.send_header('Content-Length',str(len(data)))
   self.end_headers()
   try:
    if self.path=='/truncated':self.wfile.write(data[:len(data)//2])
    elif self.path=='/oversize':self.wfile.write(data+b'extra')
    else:
     for i in range(0,len(data),256):self.wfile.write(data[i:i+256]);self.wfile.flush();time.sleep(.1)
   except (BrokenPipeError,ConnectionResetError):pass
   self.close_connection=True
  else:super().do_GET()
http.server.ThreadingHTTPServer(('127.0.0.1',8765),Handler).serve_forever()
