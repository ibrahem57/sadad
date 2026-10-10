const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../server/public');
const types = {'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.woff2':'font/woff2','.jpeg':'image/jpeg','.png':'image/png'};
const port = Number(process.env.SADAD_ADMIN_PREVIEW_PORT || 8081);
http.createServer((req,res)=>{
  const pathname = new URL(req.url,'http://localhost').pathname;
  const file = path.resolve(root, '.' + (pathname === '/' ? '/admin.html' : pathname));
  if (!file.startsWith(root + path.sep)) {res.writeHead(403);res.end();return;}
  fs.readFile(file,(err,data)=>{res.writeHead(err?404:200,{'Content-Type':types[path.extname(file)]||'application/octet-stream','Cache-Control':'no-store'});res.end(err?'Not found':data);});
}).listen(port,'127.0.0.1',()=>console.log(`Admin dashboard: http://localhost:${port}`));
