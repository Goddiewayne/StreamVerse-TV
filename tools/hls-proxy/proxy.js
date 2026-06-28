// Dev-only HLS restreaming proxy.
//
// Some CDNs (e.g. CDN77 behind odysee, push2stream) fingerprint the connecting TLS/HTTP
// client and return 403 to the Android stack (HttpURLConnection AND OkHttp use conscrypt),
// while accepting curl/browsers from the same IP. This proxy lets the emulator play those
// feeds: the app points direct streams at http://10.0.2.2:8088/p?u=<b64url>&r=<b64referer>
// (see StreamResolver.STREAM_PROXY), and we re-fetch upstream with the host's real curl
// (a non-blocked fingerprint) plus the playlist's Referer, rewriting playlists so every
// child request flows back through here too.
//
// Run:  node tools/hls-proxy/proxy.js
const http = require('http');
const { spawn } = require('child_process');
const { URL } = require('url');

const PORT = 8088;
const PROXY_HOST = `10.0.2.2:${PORT}`; // how the emulator addresses the host
const CURL = 'C:\\Program Files\\Git\\mingw64\\bin\\curl.exe'; // OpenSSL curl — verified 200
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

const b64 = (s) => Buffer.from(s, 'utf8').toString('base64url');
const unb64 = (s) => Buffer.from(s, 'base64url').toString('utf8');

// The proxy URL must end in an extension ExoPlayer's inferContentType() recognises, or it
// treats the stream as a progressive file and fails to demux the playlist. Query params are
// ignored by inferContentType, so we encode the type as the path's extension.
function suffixFor(absUrl) {
  const p = new URL(absUrl).pathname.toLowerCase();
  if (p.endsWith('.m3u8')) return '.m3u8';
  if (p.endsWith('.ts')) return '.ts';
  if (/\.(m4s|mp4|m4v|cmfv|cmfa|m4a)$/.test(p)) return '.mp4';
  if (p.endsWith('.aac')) return '.aac';
  if (p.endsWith('.vtt')) return '.vtt';
  return '.m3u8'; // unknown extension: assume a playlist (most live entry points are)
}
const wrap = (abs, ref) => `http://${PROXY_HOST}/s${suffixFor(abs)}?u=${b64(abs)}&r=${b64(ref || '')}`;

function curlArgs(url, referer) {
  const args = ['-sL', '--max-time', '30', '-A', UA];
  if (referer) args.push('-e', referer);
  args.push(url);
  return args;
}

function curlText(url, referer) {
  return new Promise((resolve, reject) => {
    const p = spawn(CURL, curlArgs(url, referer));
    const out = [];
    let err = '';
    p.stdout.on('data', (d) => out.push(d));
    p.stderr.on('data', (d) => (err += d));
    p.on('close', (code) =>
      code === 0 ? resolve(Buffer.concat(out).toString('utf8')) : reject(new Error(`curl ${code}: ${err}`)));
  });
}

function rewritePlaylist(text, baseUrl, referer) {
  return text.split(/\r?\n/).map((line) => {
    const t = line.trim();
    if (t === '') return line;
    if (t.startsWith('#')) {
      // rewrite URI="..." attributes (EXT-X-KEY / EXT-X-MEDIA / EXT-X-MAP)
      return line.replace(/URI="([^"]+)"/g, (_m, uri) => `URI="${wrap(new URL(uri, baseUrl).href, referer)}"`);
    }
    return wrap(new URL(t, baseUrl).href, referer); // variant or segment URI line
  }).join('\n');
}

const server = http.createServer(async (req, res) => {
  try {
    const u = new URL(req.url, 'http://localhost');
    if (!u.searchParams.get('u')) { res.writeHead(404); return res.end('not found'); }
    const target = unb64(u.searchParams.get('u') || '');
    const referer = unb64(u.searchParams.get('r') || '');
    const isPlaylist = new URL(target).pathname.toLowerCase().endsWith('.m3u8');
    if (isPlaylist) {
      const body = rewritePlaylist(await curlText(target, referer), target, referer);
      res.writeHead(200, { 'Content-Type': 'application/vnd.apple.mpegurl' });
      res.end(body);
    } else {
      // binary passthrough for segments / keys. The proxy URL hides the upstream extension,
      // so set an explicit media Content-Type — ExoPlayer's HLS extractor selects the segment
      // demuxer from it (otherwise: ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED / 3003).
      const path = new URL(target).pathname.toLowerCase();
      let ct = 'application/octet-stream';
      if (path.endsWith('.ts')) ct = 'video/mp2t';
      else if (/\.(m4s|mp4|m4v|cmfv|cmfa|m4a)$/.test(path)) ct = 'video/mp4';
      else if (path.endsWith('.aac')) ct = 'audio/aac';
      else if (path.endsWith('.vtt')) ct = 'text/vtt';
      const p = spawn(CURL, curlArgs(target, referer));
      res.writeHead(200, { 'Content-Type': ct });
      p.stdout.pipe(res);
      p.on('error', () => { try { res.end(); } catch (_) {} });
    }
  } catch (e) {
    res.writeHead(502);
    res.end(`proxy error: ${e.message}`);
    console.error('ERR', e.message);
  }
});

server.listen(PORT, '0.0.0.0', () => console.log(`HLS proxy listening on 0.0.0.0:${PORT} (emulator: ${PROXY_HOST})`));
