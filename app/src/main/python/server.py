#!/usr/bin/env python3
"""PyDM - download manager ala 1DM+/IDM+. Hanya library bawaan Python.
Jalankan: python server.py  ->  buka http://127.0.0.1:8080
Env: PORT=8080  HOST=127.0.0.1 (0.0.0.0 agar bisa diakses dari perangkat lain)"""
import os, re, json, time, threading, mimetypes, html as hl
import urllib.request, urllib.error
from urllib.parse import urlparse, urljoin, unquote, parse_qs
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

BASE = os.path.dirname(os.path.abspath(__file__))
STATE = os.path.join(BASE, 'state.json')
INDEX = os.path.join(BASE, 'index.html')
UA = 'Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36'
EXT = 'mp4|mkv|webm|avi|mov|mp3|m4a|flac|wav|ogg|zip|rar|7z|tar|gz|xz|apk|obb|pdf|iso|cso|exe|msi|png|jpg|jpeg|gif|webp|ini'
EXT_RE = re.compile(r'\.(?:%s)(?:$|[?#])' % EXT, re.I)

def default_dir():
    for p in ('~/storage/downloads', '~/Downloads'):
        p = os.path.expanduser(p)
        if os.path.isdir(p): return os.path.join(p, 'PyDM')
    return os.path.join(BASE, 'downloads')

CFG = {'dir': default_dir(), 'parallel': 3, 'segments': 4}
JOBS, FLAG, LOCK, NEXT = {}, {}, threading.RLock(), [1]

def save():
    with LOCK:
        json.dump({'cfg': CFG, 'next': NEXT[0], 'jobs': list(JOBS.values())}, open(STATE, 'w'))

def load():
    if not os.path.exists(STATE): return
    d = json.load(open(STATE)); CFG.update(d['cfg']); NEXT[0] = d['next']
    for j in d['jobs']:
        if j['status'] in ('downloading', 'queued'): j['status'] = 'paused'
        JOBS[j['id']] = j

COOKIES = {}
def cookie_for(url):
    return '; '.join(f'{k}={v}' for k, v in COOKIES.get(urlparse(url).hostname, {}).items())

def store_cookies(url, r):
    for c in r.headers.get_all('Set-Cookie') or []:
        k, _, v = c.split(';')[0].partition('=')
        COOKIES.setdefault(urlparse(url).hostname, {})[k.strip()] = v

def fetch(url, ref='', rng=None, cookie='', ua=''):
    h = {'User-Agent': ua or UA, 'Accept': '*/*', 'Accept-Language': 'id,en;q=0.8'}
    if cookie: h['Cookie'] = cookie
    if ref: h['Referer'] = ref
    if rng: h['Range'] = rng
    return urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=30)

def clean(n): return re.sub(r'[\\/:*?"<>|\x00-\x1f]', '_', n).strip()[:150] or 'file'

def fname(url, hd):
    cd = hd.get('Content-Disposition') or ''
    m = re.search(r"filename\*=(?:UTF-8'')?([^;]+)", cd, re.I)
    if m: n = unquote(m.group(1).strip('" '))
    else:
        m = re.search(r'filename="?([^";]+)', cd, re.I)
        n = m.group(1) if m else unquote(urlparse(url).path.rsplit('/', 1)[-1])
    n = clean(n)
    if '.' not in n:
        e = mimetypes.guess_extension((hd.get('Content-Type') or '').split(';')[0].strip())
        if e: n += e
    return n

def probe(url, ref='', cookie='', ua=''):
    r = fetch(url, ref, 'bytes=0-0', cookie, ua)
    cr = r.headers.get('Content-Range') or ''
    if r.status == 206 and cr.split('/')[-1].isdigit():
        size, ranged = int(cr.split('/')[-1]), True
    else:
        size, ranged = int(r.headers.get('Content-Length') or 0), False
    out = dict(size=size, ranged=ranged, name=fname(r.geturl(), r.headers),
               type=r.headers.get('Content-Type', ''), url=r.geturl())
    r.close()
    return out

def uniq(d, name):
    base, ext = os.path.splitext(name); n, c = name, 1
    while os.path.exists(os.path.join(d, n)) or os.path.exists(os.path.join(d, n + '.part')) \
            or any(j['dir'] == d and j['name'] == n for j in JOBS.values()):
        n = f'{base} ({c}){ext}'; c += 1
    return n

def make_segs(p):
    if p['ranged'] and p['size'] > 2 << 20:
        n = max(1, CFG['segments']); step = -(-p['size'] // n)
        return [[i * step, min((i + 1) * step, p['size']) - 1, i * step] for i in range(n)]
    return [[0, p['size'] - 1 if p['ranged'] else -1, 0]]

def add(url, name=None, ref='', cookie='', ua='', paused=False):
    """paused=True: masuk daftar tanpa langsung diunduh (mulai manual lewat tombol ▶)."""
    p = probe(url, ref, cookie, ua)
    os.makedirs(CFG['dir'], exist_ok=True)
    with LOCK:
        name = uniq(CFG['dir'], clean(name) if name else p['name'])
        jid = NEXT[0]; NEXT[0] += 1
        JOBS[jid] = dict(id=jid, url=p['url'], name=name, dir=CFG['dir'], size=p['size'], ranged=p['ranged'],
                         segs=make_segs(p), status='paused' if paused else 'queued', error='', ref=ref, cookie=cookie,
                         ua=ua, done=0, speed=0, added=time.time(), active=0, finished=0)
    save(); return jid

def seg_run(j, s, path):
    tries = 0
    while not FLAG.get(j['id']):
        if j['ranged'] and s[2] > s[1]: return
        if not j['ranged']: s[2] = 0
        try:
            r = fetch(j['url'], j['ref'], f"bytes={s[2]}-{s[1]}" if j['ranged'] else None, j.get('cookie', ''), j.get('ua', ''))
            if j['ranged'] and r.status != 206: raise IOError('Server menolak Range')
            with open(path, 'r+b' if j['ranged'] else 'wb') as f:
                f.seek(s[2])
                while not FLAG.get(j['id']):
                    b = r.read(65536)
                    if not b: break
                    if s[1] >= 0: b = b[:s[1] + 1 - s[2]]
                    f.write(b); s[2] += len(b)
                    if s[1] >= 0 and s[2] > s[1]: break
            r.close()
            if FLAG.get(j['id']) or s[1] < 0 or s[2] > s[1]: return
            raise IOError('Koneksi terputus')
        except Exception as e:
            tries += 1
            if tries > 5: j['error'] = str(e); FLAG[j['id']] = 'fail'; return
            time.sleep(2)

def worker(j):
    path = os.path.join(j['dir'], j['name'] + '.part')
    try:
        if not os.path.exists(path):
            with open(path, 'wb') as f:
                if j['ranged'] and j['size']: f.truncate(j['size'])
        ts = [threading.Thread(target=seg_run, args=(j, s, path), daemon=True) for s in j['segs']]
        [t.start() for t in ts]; [t.join() for t in ts]
        fl = FLAG.pop(j['id'], None)
        if fl == 'pause': j['status'] = 'paused'
        elif fl == 'stop': j['status'] = 'stopped'
        elif fl == 'fail': j['status'] = 'error'
        else:
            os.replace(path, os.path.join(j['dir'], j['name']))
            j['status'] = 'finished'; j['finished'] = time.time()
            j['size'] = j['size'] or os.path.getsize(os.path.join(j['dir'], j['name']))
    except Exception as e:
        j['status'], j['error'] = 'error', str(e)
    j['speed'] = 0; save()

def ticker():
    n = 0
    while True:
        time.sleep(1); n += 1
        with LOCK:
            act = 0
            for j in JOBS.values():
                d = sum(s[2] - s[0] for s in j['segs'])
                j['speed'] = max(0, d - j['done']) if j['status'] == 'downloading' else 0
                j['done'] = d; act += j['status'] == 'downloading'
                if j['status'] == 'downloading': j['active'] = j.get('active', 0) + 1
            for j in sorted(JOBS.values(), key=lambda x: x['id']):
                if act < CFG['parallel'] and j['status'] == 'queued':
                    FLAG.pop(j['id'], None); j['status'] = 'downloading'; act += 1
                    threading.Thread(target=worker, args=(j,), daemon=True).start()
        if n % 5 == 0: save()

def act(jid, a, with_file=False):
    with LOCK:
        j = JOBS.get(jid)
        if not j: return
        s = j['status']
        if a == 'pause':
            if s == 'downloading': FLAG[jid] = 'pause'
            elif s == 'queued': j['status'] = 'paused'
        elif a == 'stop':
            if s == 'downloading': FLAG[jid] = 'stop'
            elif s in ('queued', 'paused'): j['status'] = 'stopped'
        elif a == 'resume' and s in ('paused', 'stopped', 'error'):
            j['status'], j['error'] = 'queued', ''
        elif a == 'delete':
            if s == 'downloading': FLAG[jid] = 'stop'
            del JOBS[jid]
            def rm():
                time.sleep(1.5)
                for suf in (['.part', ''] if with_file else ['.part']):
                    try: os.remove(os.path.join(j['dir'], j['name'] + suf))
                    except OSError: pass
            threading.Thread(target=rm, daemon=True).start()
    save()

def stop_wait(j):
    """Hentikan unduhan yang sedang jalan dan tunggu workernya selesai."""
    if j['status'] == 'downloading': FLAG[j['id']] = 'stop'
    for _ in range(60):
        if j['status'] != 'downloading': return
        time.sleep(0.1)
    raise RuntimeError('Unduhan belum berhenti, coba lagi sebentar lagi')

def redo(jid, url=None):
    """Unduh ulang: hapus berkas/bagian lama lalu mulai dari awal (opsional dengan link baru)."""
    j = JOBS.get(jid)
    if not j: raise ValueError('Unduhan tidak ditemukan')
    try: p = probe(url or j['url'], j['ref'], j.get('cookie', ''), j.get('ua', ''))
    except Exception as e: raise ValueError('Link tidak bisa dibuka (mungkin kedaluwarsa): %s' % e)
    with LOCK:
        if j['status'] == 'queued': j['status'] = 'paused'  # cegah ticker memulainya selagi direset
    stop_wait(j)
    with LOCK:
        for suf in ('.part', ''):
            try: os.remove(os.path.join(j['dir'], j['name'] + suf))
            except OSError: pass
        FLAG.pop(jid, None)
        j.update(url=p['url'], size=p['size'], ranged=p['ranged'], segs=make_segs(p), status='queued',
                 error='', done=0, speed=0, active=0, finished=0, added=time.time())
    save()

def relink(jid, url, force=False):
    """Ganti link unduhan yang kedaluwarsa. Lanjut dari bagian lama kalau ukuran berkasnya sama."""
    j = JOBS.get(jid)
    if not j: raise ValueError('Unduhan tidak ditemukan')
    if j['status'] == 'finished': raise ValueError('Unduhan sudah selesai')
    if j['status'] == 'downloading': raise ValueError('Jeda unduhan dulu')
    p = probe(url, j['ref'], j.get('cookie', ''), j.get('ua', ''))
    if (j['size'] and p['size'] != j['size']) or (j['ranged'] and not p['ranged']):
        if not force: return {'mismatch': 1, 'old': j['size'], 'new': p['size']}
        redo(jid, url); return {'ok': 1, 'redo': 1}
    with LOCK:
        j['url'] = p['url']; j['status'] = 'queued'; j['error'] = ''
    save(); return {'ok': 1}

def rename(jid, new):
    with LOCK:
        j = JOBS.get(jid)
        if not j: raise ValueError('Unduhan tidak ditemukan')
        if j['status'] == 'downloading': raise ValueError('Jeda unduhan dulu')
        new = clean(new)
        if new == j['name']: return new
        d = j['dir']
        if os.path.exists(os.path.join(d, new)) or os.path.exists(os.path.join(d, new + '.part')) \
                or any(o['dir'] == d and o['name'] == new for o in JOBS.values()):
            raise ValueError('Nama itu sudah dipakai')
        for suf in ('', '.part'):
            a = os.path.join(d, j['name'] + suf)
            if os.path.exists(a): os.rename(a, os.path.join(d, new + suf))
        j['name'] = new
    save(); return new

INJ = r'''<script>(function(){var P=parent,E=/\.(?:@EXT@)(?:$|[?#])/i;
function go(u){if(E.test(u))P.postMessage({t:'dl',url:u,ref:__B},'*');else location.href='/browse?u='+encodeURIComponent(u)}
addEventListener('click',function(e){var a=e.target.closest&&e.target.closest('a[href]');if(!a||!/^https?:/.test(a.href))return;e.preventDefault();go(a.href)},true);
addEventListener('submit',function(e){var f=e.target;e.preventDefault();var a=new URL(f.getAttribute('action')||__B,__B);
if((f.method||'get').toLowerCase()=='get')a.search=new URLSearchParams(new FormData(f)).toString();go(a.href)},true);
window.open=function(u){go(new URL(u,__B).href);return null};
if(window.navigation)navigation.addEventListener('navigate',function(e){var u=e.destination.url;
if(e.destination.sameDocument||!/^https?:/.test(u)||new URL(u).origin==location.origin)return;if(e.cancelable){e.preventDefault();go(u)}});
function rep(){P.postMessage({t:'page',url:__B,title:document.title,links:__L},'*')}rep();addEventListener('load',rep)})();</script>'''.replace('@EXT@', EXT)

def landing(msg):
    return f'<body style="font:16px sans-serif;padding:24px;background:#121212;color:#ddd">{hl.escape(msg)}</body>'.encode()

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def send(self, code, body, ct='application/json'):
        if isinstance(body, (dict, list)): body = json.dumps(body).encode()
        self.send_response(code)
        self.send_header('Content-Type', ct); self.send_header('Content-Length', str(len(body)))
        self.end_headers(); self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path); q = {k: v[0] for k, v in parse_qs(u.query).items()}
        try:
            if u.path == '/':
                self.send(200, open(INDEX, 'rb').read(), 'text/html; charset=utf-8')
            elif u.path == '/api/list':
                with LOCK: self.send(200, {'jobs': list(JOBS.values()), 'cfg': CFG})
            elif u.path == '/api/probe':
                self.send(200, probe(q['u'], q.get('ref', '')))
            elif u.path == '/browse':
                self.browse(q.get('u', ''), q.get('js', '1'))
            elif u.path.startswith('/file/'):
                j = JOBS.get(int(u.path[6:]))
                p = os.path.join(j['dir'], j['name'])
                self.send_response(200)
                self.send_header('Content-Type', mimetypes.guess_type(p)[0] or 'application/octet-stream')
                self.send_header('Content-Length', str(os.path.getsize(p)))
                self.send_header('Content-Disposition', "attachment; filename*=UTF-8''" + urllib.request.quote(j['name']))
                self.end_headers()
                with open(p, 'rb') as f:
                    while (b := f.read(65536)): self.wfile.write(b)
            else: self.send(404, {'error': 'not found'})
        except (BrokenPipeError, ConnectionResetError): pass
        except Exception as e: self.send(500, {'error': str(e)})

    def do_POST(self):
        try:
            d = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))) or b'{}')
            if self.path == '/api/add': self.send(200, {'id': add(d['url'], d.get('name'), d.get('ref', ''), paused=bool(d.get('paused')))})
            elif self.path == '/api/act': act(int(d['id']), d['a'], d.get('file', False)); self.send(200, {'ok': 1})
            elif self.path == '/api/relink': self.send(200, relink(int(d['id']), d['url'], bool(d.get('force'))))
            elif self.path == '/api/redo': redo(int(d['id'])); self.send(200, {'ok': 1})
            elif self.path == '/api/rename': self.send(200, {'name': rename(int(d['id']), d['name'])})
            elif self.path == '/api/cfg':
                CFG.update(dir=d['dir'], parallel=max(1, int(d['parallel'])), segments=min(16, max(1, int(d['segments']))))
                save(); self.send(200, CFG)
            else: self.send(404, {'error': 'not found'})
        except Exception as e: self.send(400, {'error': str(e)})

    def browse(self, url, js='1'):
        if not re.match(r'https?://', url): return self.send(400, landing('Alamat tidak valid'), 'text/html; charset=utf-8')
        try: r = fetch(url, cookie=cookie_for(url))
        except urllib.error.HTTPError as e: r = e
        except Exception as e: return self.send(502, landing(f'Gagal membuka halaman: {e}'), 'text/html; charset=utf-8')
        store_cookies(url, r)
        final, ct = r.geturl(), (r.headers.get('Content-Type') or '').lower()
        if 'html' not in ct:  # bukan halaman web -> anggap file unduhan
            r.close()
            js = json.dumps({'t': 'dl', 'url': final, 'ref': url, 'back': True})
            return self.send(200, f'<script>parent.postMessage({js},"*")</script>'.encode(), 'text/html')
        t = r.read(5_000_000).decode(r.headers.get_content_charset() or 'utf-8', 'replace')
        t = re.sub(r'<meta[^>]+http-equiv=["\']?(?:content-security-policy|refresh)[^>]*>', '', t, flags=re.I)
        tt, found = t.replace('\\/', '/'), {}
        for m in re.finditer(r'(?:href|src|data-src)=["\']([^"\'#]+)', tt, re.I):
            a = urljoin(final, hl.unescape(m.group(1)))
            if a.startswith('http') and EXT_RE.search(a): found[a] = 1
        for m in re.finditer(r'https?://[^\s"\'<>\\]+', tt):
            if EXT_RE.search(m.group(0)): found[hl.unescape(m.group(0))] = 1
        if js == '0': t = re.sub(r'<script\b.*?</script>', '', t, flags=re.S | re.I)
        head = '<base href="%s"><script>window.__B=%s;window.__L=%s</script>%s' % (
            hl.escape(final, True), json.dumps(final), json.dumps(list(found)[:200]).replace('</', '<\\/'), INJ)
        t, n = re.subn(r'(<head[^>]*>)', lambda m: m.group(1) + head, t, count=1, flags=re.I)
        if not n: t = head + t
        self.send(200, t.encode(), 'text/html; charset=utf-8')

def run(home=BASE, dl_dir=None, host='127.0.0.1', port=8080):
    """Dipanggil dari Android (Chaquopy) atau langsung dari terminal."""
    global STATE, INDEX
    STATE, INDEX = os.path.join(home, 'state.json'), os.path.join(home, 'index.html')
    fresh = not os.path.exists(STATE)
    load()
    if dl_dir and fresh: CFG['dir'] = dl_dir
    try: os.makedirs(CFG['dir'], exist_ok=True)
    except OSError:
        CFG['dir'] = os.path.join(home, 'downloads'); os.makedirs(CFG['dir'], exist_ok=True)
    threading.Thread(target=ticker, daemon=True).start()
    print(f'PyDM berjalan di http://{host}:{port}  |  folder unduhan: {CFG["dir"]}')
    ThreadingHTTPServer((host, port), Handler).serve_forever()

if __name__ == '__main__':
    run(BASE, None, os.environ.get('HOST', '127.0.0.1'), int(os.environ.get('PORT', 8080)))
