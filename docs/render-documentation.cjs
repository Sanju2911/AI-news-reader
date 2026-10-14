// Rebuild the standalone HTML guide from its Markdown source and local screenshots.
const fs = require('fs');
const path = require('path');
const escape = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const inline = s => escape(s).replace(/`([^`]+)`/g, '<code>$1</code>');
const lines = fs.readFileSync(path.join(__dirname, 'PROJECT_DOCUMENTATION.md'), 'utf8').split(/\r?\n/);
const blocks = [], toc = [];
for (let i = 0; i < lines.length;) {
  const line = lines[i];
  if (!line.trim()) { i++; continue; }
  if (line.startsWith('```')) {
    const code = [];
    i++;
    while (i < lines.length && !lines[i].startsWith('```')) code.push(lines[i++]);
    i++;
    blocks.push('<pre><code>' + escape(code.join('\n')) + '</code></pre>');
    continue;
  }
  const heading = line.match(/^(#{1,3}) (.+)$/);
  if (heading) {
    const level = heading[1].length, title = heading[2], id = 'section-' + i;
    if (level === 2) toc.push(`<a href="#${id}">${escape(title)}</a>`);
    blocks.push(`<h${level} id="${id}">${inline(title)}</h${level}>`);
    i++; continue;
  }
  const img = line.match(/^!\[([^\]]+)\]\(([^)]+)\)$/);
  if (img) {
    const bytes = fs.readFileSync(path.join(__dirname, img[2]));
    const mime = path.extname(img[2]).toLowerCase() === '.png' ? 'image/png' : 'image/jpeg';
    blocks.push(`<figure><img ${mime === 'image/png' ? 'style="width:auto;max-width:100%"' : ''} src="data:${mime};base64,${bytes.toString('base64')}" alt="${escape(img[1])}"><figcaption>${escape(img[1])}</figcaption></figure>`);
    i++; continue;
  }
  if (line.startsWith('|')) {
    const rows = [];
    while (i < lines.length && lines[i].startsWith('|')) rows.push(lines[i++].split('|').slice(1,-1).map(s => s.trim()));
    const header = rows.shift(); rows.shift();
    blocks.push('<div class="table-wrap"><table><thead><tr>' + header.map(s => '<th>'+inline(s)+'</th>').join('') + '</tr></thead><tbody>' + rows.map(r => '<tr>'+r.map(s=>'<td>'+inline(s)+'</td>').join('')+'</tr>').join('')+'</tbody></table></div>');
    continue;
  }
  if (/^(- |\d+\. )/.test(line)) {
    const ordered = /^\d/.test(line), items = [];
    while (i < lines.length && /^(- |\d+\. )/.test(lines[i])) items.push(lines[i++].replace(/^(- |\d+\. )/,''));
    const tag = ordered ? 'ol' : 'ul';
    blocks.push(`<${tag}>${items.map(s=>'<li>'+inline(s)+'</li>').join('')}</${tag}>`);
    continue;
  }
  const paragraph = [line]; i++;
  while (i < lines.length && lines[i].trim() && !/^(#|\||```|!\[|- |\d+\. )/.test(lines[i])) paragraph.push(lines[i++]);
  blocks.push('<p>'+inline(paragraph.join(' '))+'</p>');
}
const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>AI News Reader — Project Documentation</title><style>
:root{color-scheme:light;--ink:#192331;--accent:#aa4c17;--line:#e0e5ea}*{box-sizing:border-box}body{margin:0;background:#f3f5f7;color:var(--ink);font:16px/1.65 system-ui,Segoe UI,sans-serif}aside{position:fixed;width:265px;inset:0 auto 0 0;padding:30px 20px;background:#172330;color:#fff;overflow:auto}aside strong{display:block;font-size:20px;margin-bottom:6px}aside small{color:#b5c4d3}nav{margin-top:24px}nav a{display:block;color:#dce5ee;text-decoration:none;font-size:13px;padding:9px 8px;border-radius:6px}nav a:hover{background:#2c4054}main{margin:0 0 0 265px;padding:48px max(28px,calc((100vw - 1320px)/2));max-width:1450px}article{background:white;padding:42px;border:1px solid var(--line);border-radius:14px}h1{font-size:36px;line-height:1.2;letter-spacing:-1px;margin:0 0 16px}h2{font-size:25px;margin:46px 0 18px;padding-top:20px;border-top:2px solid var(--line);scroll-margin-top:20px}h3{font-size:19px;margin:30px 0 10px;color:var(--accent)}p{margin:15px 0}code{font:13px/1.5 Consolas,monospace;background:#edf1f5;padding:2px 5px;border-radius:4px;overflow-wrap:anywhere}pre{background:#172330;color:#edf4fa;padding:22px;border-radius:9px;overflow:auto}pre code{background:none;padding:0;color:inherit;white-space:pre;overflow-wrap:normal}.table-wrap{overflow:auto;margin:22px 0}table{border-collapse:collapse;width:100%;font-size:13px;line-height:1.55}th,td{text-align:left;vertical-align:top;padding:12px;border:1px solid var(--line)}th{background:#edf1f5}tr:nth-child(even){background:#fafbfc}figure{margin:24px 0}figure img{width:100%;border-radius:10px;border:1px solid var(--line)}figcaption{font-size:13px;color:#657383;margin-top:7px}li{margin:7px 0}footer{font-size:12px;color:#657383;margin:25px 0}@media(max-width:1000px){aside{position:static;width:auto;padding:22px}nav{display:flex;flex-wrap:wrap;gap:4px;margin-top:10px}nav a{padding:5px}main{margin:0;padding:20px}article{padding:24px}h1{font-size:28px}}@media print{aside{display:none}main{margin:0;padding:0}article{border:0;padding:0}body{background:white;font-size:11px}h1{font-size:25px}h2{font-size:18px;break-after:avoid}h3{break-after:avoid}figure,pre{break-inside:avoid}th,td{padding:6px}table{font-size:10px}figure img{max-height:420px;object-fit:contain}}
</style></head><body><aside><strong>AI News Reader</strong><small>Project guide · 14 October 2026</small><nav>${toc.join('')}</nav></aside><main><article>${blocks.join('\n')}</article><footer>Four web screenshots and six native watch screenshots. Populated watch screens use labeled local sample state; paired-phone behavior remains unverified.</footer></main></body></html>`;
fs.writeFileSync(path.join(__dirname, 'PROJECT_DOCUMENTATION.html'), html);
console.log(`Built offline guide: ${toc.length} sections, ${blocks.filter(b=>b.startsWith('<figure>')).length} screenshots.`);
