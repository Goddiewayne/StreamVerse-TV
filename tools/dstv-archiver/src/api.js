import axios from 'axios';

const BASE = 'https://dstv.stream/api/cs-mobile/v7/epg-service';
const STREAM_API = 'https://dstv.stream/api/dstv_now';

/**
 * Lists all available channels from the public DSTV Stream API.
 * No authentication required.
 */
export async function listChannels(country = 'NG', pkg = 'COMPACT-PLUS') {
  const url = `${BASE}/channels/events;genre=ALL;country=${country};packageId=${pkg};count=0;utcOffset=+02:00`;
  const { data } = await axios.get(url, {
    headers: {
      'Accept': 'application/json',
      'Referer': 'https://dstv.stream/',
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
    },
  });
  return data.items.map((ch) => ({
    tag: ch.id,
    name: ch.name,
    number: ch.number,
    genre: (ch.genres || []).join(', '),
    logo: (ch.channelLogoPathsWeb || ch.channelLogoPaths || {}).XLARGE || null,
    streamUrl: (ch.streams || []).find((s) => s.active)?.playerUrl || null,
    features: ch.features || {},
  }));
}

/**
 * Gets the Akamai streaming token for a channel.
 * Requires authenticated session (x-aws-waf-token + x-profile-id).
 */
export async function getStreamToken(channelTag, wafToken, profileId) {
  const { data } = await axios.post(
    `${STREAM_API}/play_stream/access_token?channel_tag=${channelTag}`,
    {},
    {
      headers: {
        'Content-Type': 'application/json',
        'x-aws-waf-token': wafToken,
        'x-profile-id': profileId,
        'Origin': 'https://dstv.stream',
        'Referer': 'https://dstv.stream/',
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
      },
    },
  );
  return data.access_token;
}

/**
 * Gets the Widevine DRM entitlement session.
 * Returns the JWT session token and filters.
 */
export async function getEntitlementSession(wafToken, profileId) {
  const { data } = await axios.post(
    `https://dstv.stream/api/vod-auth/entitlement/session`,
    { device_id: 'desktop' },
    {
      headers: {
        'Content-Type': 'application/json',
        'x-aws-waf-token': wafToken,
        'x-profile-id': profileId,
        'Origin': 'https://dstv.stream',
        'Referer': 'https://dstv.stream/',
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
      },
    },
  );
  return data;
}

/**
 * Builds the full MPD URL with hdnts token for a channel.
 */
export function buildMpdUrl(channelTag, hdntsToken) {
  const base = `https://i-live-cache.akamaized.net/USL01/${channelTag}/${channelTag}.isml/.mpd`;
  const ssai = `ssai=cGlkPURWU1RMTUVMN1FYNkVYWk9KRFFHR1k5QzAwNDQmZGNpZD1kZXNrdG9wJnQ9UEE6Q09NUEFDVC1QTFVTLFBSOkRTdHYsU0M6TGluZWFyJnBmPWh0bWw1JmdkcHI9MCZnZHByX2NvbnNlb50PTA%3D%3D`;
  const filter = `filter=%28type%3D%3D%22video%22%26%26MaxHeight%3C%3D720%29%7C%7C%28type%3D%3D%22audio%22%26%26systemBitrate%3E30000%29%7C%7C%28type%3D%3D%22textstream%22%29`;
  return `${base}?${ssai}&${filter}&hdnts=${encodeURIComponent(hdntsToken)}`;
}
