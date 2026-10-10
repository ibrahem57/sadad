const fs = require('node:fs');
const path = require('node:path');

const repository = path.resolve(__dirname, '..');
const source = path.join(repository, 'server/public');
const output = path.join(repository, '_site');
// Publish only the dashboard assets, never repository data or local credentials.
const assets = ['admin.html', 'admin.css', 'admin.js', 'admin-tajawal.woff2', 'logo-light.jpeg', 'logo-dark.png'];

fs.rmSync(output, { recursive: true, force: true });
fs.mkdirSync(path.join(output, 'v3-admin'), { recursive: true });
for (const asset of assets) {
  fs.copyFileSync(path.join(source, asset), path.join(output, asset));
}
fs.copyFileSync(path.join(source, 'admin.html'), path.join(output, 'index.html'));
fs.writeFileSync(path.join(output, 'v3-admin/index.html'), '<!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8"><meta http-equiv="refresh" content="0;url=../"><title>إدارة سدد</title><a href="../">افتح لوحة إدارة سدد</a></html>');
fs.writeFileSync(path.join(output, '.nojekyll'), '');
console.log('Built the unified admin dashboard in _site/.');
