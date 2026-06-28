import puppeteer from 'puppeteer';
import fs from 'fs';
import path from 'path';
import { listChannels, getStreamToken, getEntitlementSession, buildMpdUrl } from './api.js';
import { downloadChannel } from './stream.js';

const WEB_PLAYER = 'https://dstv.stream';
const COOKIE_DIR = './.dstv-session';

/**
 * Launches Chrome via Puppeteer, logs into DSTV Stream,
 * captures the OAuth tokens, the WAF token, and the profile ID,
 * then captures a Widevine license for the given channel tag.
 */
export async function captureLicense({ channelTag, username, password, outputDir, duration }) {
  console.log(`[dstv-archiver] Targeting channel: ${channelTag}`);

  // 1. Ensure output dir
  fs.mkdirSync(outputDir, { recursive: true });
  fs.mkdirSync(COOKIE_DIR, { recursive: true });

  // 2. Launch headless Chrome — must NOT be headless for Widevine CDM
  const browser = await puppeteer.launch({
    headless: false, // Widevine CDM requires a real display or Xvfb
    args: [
      '--disable-web-security',
      '--no-sandbox',
      '--disable-features=ChromeWidevineDRM',
      '--enable-widevine',
    ],
    defaultViewport: { width: 1280, height: 720 },
  });

  const page = await browser.newPage();

  // 3. Intercept network requests to capture WAF token + license exchanges
  const captured = {
    wafToken: null,
    profileId: 'd61e229f-66c2-4dc4-8d53-e2b33095828e',
    licenseUrl: null,
    licensePayload: null,
    licenseResponse: null,
    mpdUrl: null,
  };

  await page.setRequestInterception(true);

  page.on('request', (request) => {
    const url = request.url();
    const headers = request.headers();

    // Capture the WAF token from API requests
    if (url.includes('dstv.stream/api/') && headers['x-aws-waf-token']) {
      captured.wafToken = headers['x-aws-waf-token'];
    }
    if (url.includes('x-aws-waf-token')) {
      const match = url.match(/x-aws-waf-token=([^&]+)/);
      if (match) captured.wafToken = decodeURIComponent(match[1]);
    }

    // Capture Widevine license request
    if (url.includes('widevine') || url.includes('drm') || url.includes('license')) {
      captured.licenseUrl = url;
      captured.licensePayload = request.postData();
    }

    // Capture MPD manifest request
    if (url.includes('.mpd') && url.includes('hdnts=')) {
      captured.mpdUrl = url;
    }

    request.continue();
  });

  page.on('response', async (response) => {
    const url = response.url();
    if (url === captured.licenseUrl) {
      try {
        captured.licenseResponse = await response.buffer();
      } catch {}
    }
    // Capture WAF token from set-cookie or response headers
    const headers = response.headers();
    if (headers['x-aws-waf-token']) {
      captured.wafToken = headers['x-aws-waf-token'];
    }
    // Capture profile ID
    if (headers['x-profile-id']) {
      captured.profileId = headers['x-profile-id'];
    }
  });

  // 4. Check for stored session or login
  const storedSession = loadStoredSession();
  if (storedSession) {
    console.log('[dstv-archiver] Restoring stored session...');
    await page.goto(WEB_PLAYER, { waitUntil: 'networkidle2' });
    await page.evaluate((session) => {
      localStorage.setItem('connect_oidc_user_session', JSON.stringify(session));
    }, storedSession);
    await page.reload({ waitUntil: 'networkidle2' });
  } else if (username && password) {
    console.log('[dstv-archiver] Logging in...');
    await page.goto('https://authentication.dstv.stream/registration/signin', { waitUntil: 'networkidle2' });

    // Wait for login form
    await page.waitForSelector('input[type="email"], input[name="email"], input[name="username"]', { timeout: 15000 });
    await page.type('input[type="email"], input[name="email"], input[name="username"]', username, { delay: 30 });
    await page.type('input[type="password"]', password, { delay: 30 });
    await page.click('button[type="submit"]');

    // Wait for OAuth redirect back to dstv.stream with tokens
    await page.waitForFunction(
      'window.location.href.includes("access_token=")',
      { timeout: 30000 },
    );

    // Extract tokens from URL
    const finalUrl = page.url();
    console.log(`[dstv-archiver] OAuth redirect: ${finalUrl.substring(0, 100)}...`);

    const params = new URLSearchParams(finalUrl.split('?')[1]);
    const session = {
      authToken: params.get('access_token'),
      idToken: params.get('id_token'),
      trackingId: params.get('tracking_id') || '',
      expiresAt: Date.now() + 86400000,
      refreshToken: params.get('refresh_token') || '',
    };
    saveStoredSession(session);

    // Navigate to web player and let Angular boot
    await page.goto(WEB_PLAYER, { waitUntil: 'networkidle2' });
  } else {
    console.log('[dstv-archiver] No session or credentials. Opening web player...');
    await page.goto(WEB_PLAYER, { waitUntil: 'networkidle2' });
  }

  // 5. Navigate to the channel's live stream
  console.log(`[dstv-archiver] Navigating to /livetv#${channelTag}...`);
  await page.goto(`${WEB_PLAYER}/livetv#${channelTag}`, { waitUntil: 'networkidle2' });

  // Wait for the stream to load
  await new Promise((r) => setTimeout(r, 8000));

  // 6. Capture the WAF token from localStorage or page context
  const wafToken = captured.wafToken || await page.evaluate(() => {
    // AWS WAF token is sometimes stored in sessionStorage or a global
    return window.__waf_token || document.cookie.match(/aws-waf-token=([^;]+)/)?.[1] || null;
  });

  // 7. Get the stream token and MPD URL
  if (wafToken) {
    console.log('[dstv-archiver] Got WAF token, fetching stream token...');
    try {
      const hdntsToken = await getStreamToken(channelTag, wafToken, captured.profileId);
      const mpdUrl = buildMpdUrl(channelTag, hdntsToken);
      console.log(`[dstv-archiver] MPD URL: ${mpdUrl.substring(0, 120)}...`);

      // Download the MPD manifest
      const dlDir = path.join(outputDir, channelTag);
      fs.mkdirSync(dlDir, { recursive: true });

      // Get entitlement for decryption context
      const entitlement = await getEntitlementSession(wafToken, captured.profileId);

      // Save session context for decryption
      const sessionCtx = {
        channelTag,
        wafToken,
        profileId: captured.profileId,
        mpdUrl,
        hdntsToken,
        licenseKey: captured.licenseResponse?.toString('base64') || null,
        entitlementSession: entitlement.session,
        streamingFilter: entitlement.streaming_filter,
      };
      fs.writeFileSync(path.join(dlDir, 'session.json'), JSON.stringify(sessionCtx, null, 2));
      console.log(`[dstv-archiver] Session context saved to ${dlDir}/session.json`);

    } catch (err) {
      console.error(`[dstv-archiver] Failed to get stream token: ${err.message}`);
    }
  } else {
    console.log('[dstv-archiver] No WAF token captured. The page may need manual login.');
    // Keep browser open for manual login if duration > 0
    if (duration > 0) {
      console.log(`[dstv-archiver] Recording for ${duration} minutes...`);
    }
  }

  // 8. If license was captured, attempt decryption
  if (captured.licenseResponse) {
    console.log(`[dstv-archiver] Widevine license captured (${captured.licenseResponse.length} bytes)`);
    const dlDir = path.join(outputDir, channelTag);
    fs.writeFileSync(path.join(dlDir, 'license.bin'), captured.licenseResponse);
  }

  // Keep alive for recording if duration > 0
  if (duration > 0) {
    console.log(`[dstv-archiver] Recording for ${duration} minutes. Press Ctrl+C to stop.`);
    await new Promise((r) => setTimeout(r, duration * 60 * 1000));
  }

  await browser.close();
  console.log('[dstv-archiver] Done.');
}

function loadStoredSession() {
  const file = path.join(COOKIE_DIR, 'session.json');
  try {
    return JSON.parse(fs.readFileSync(file, 'utf-8'));
  } catch {
    return null;
  }
}

function saveStoredSession(session) {
  const file = path.join(COOKIE_DIR, 'session.json');
  fs.writeFileSync(file, JSON.stringify(session, null, 2));
  console.log('[dstv-archiver] Session saved for future use.');
}
