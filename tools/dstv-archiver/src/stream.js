import axios from 'axios';
import fs from 'fs';
import path from 'path';
import { spawn } from 'child_process';

/**
 * Downloads the MPD manifest and all segments for a channel using a valid hdnts token.
 */
export async function downloadChannel(channelTag, outputDir, durationMin = 15) {
  const sessionPath = path.join(outputDir, channelTag, 'session.json');
  if (!fs.existsSync(sessionPath)) {
    console.error(`[dstv-archiver] No session found for ${channelTag}. Run 'capture' first.`);
    return;
  }

  const session = JSON.parse(fs.readFileSync(sessionPath, 'utf-8'));
  const dlDir = path.join(outputDir, channelTag, 'segments');
  fs.mkdirSync(dlDir, { recursive: true });

  console.log(`[dstv-archiver] Downloading ${channelTag} for ${durationMin} min...`);

  // Use ffmpeg to download and mux the live stream (if ffmpeg is available)
  // Otherwise download segments individually from the MPD
  try {
    const outputFile = path.join(outputDir, channelTag, `${channelTag}_${Date.now()}.mkv`);
    await downloadWithFfmpeg(session.mpdUrl, outputFile, durationMin);
    console.log(`[dstv-archiver] Saved: ${outputFile}`);
  } catch (err) {
    console.log(`[dstv-archiver] ffmpeg not available, downloading raw segments: ${err.message}`);
    await downloadRawSegments(session.mpdUrl, dlDir, durationMin);
  }
}

/**
 * Uses ffmpeg to download a live DASH stream.
 * Captures up to durationMin minutes.
 * ffmpeg handles the hdnts token in the MPD URL automatically.
 */
function downloadWithFfmpeg(mpdUrl, outputFile, durationMin) {
  return new Promise((resolve, reject) => {
    const args = [
      '-y',
      '-live_start_index', '-1',          // start from latest segment
      '-analyzeduration', '100M',
      '-user_agent', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
      '-headers', `Referer: https://dstv.stream/\r\n`,
      '-i', mpdUrl,
      '-c', 'copy',                        // remux without re-encoding
      '-f', 'matroska',
      '-t', `${durationMin * 60}`,         // duration in seconds
      '-y', outputFile,
    ];

    const proc = spawn('ffmpeg', args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let stderr = '';

    proc.stderr.on('data', (data) => {
      stderr += data.toString();
      // Progress indicator
      const timeMatch = stderr.match(/time=(\d+):(\d+):(\d+\.\d+)/g);
      if (timeMatch) {
        const last = timeMatch[timeMatch.length - 1];
        process.stdout.write(`\r[dstv-archiver] ${last}`);
      }
    });

    proc.on('close', (code) => {
      if (code === 0) resolve();
      else reject(new Error(`ffmpeg exited with code ${code}`));
    });

    proc.on('error', (err) => reject(err));
  });
}

/**
 * Fallback: download raw MPD segments using HTTP.
 * Parses the MPD manifest and downloads each segment individually.
 */
async function downloadRawSegments(mpdUrl, dlDir, durationMin) {
  // Download the MPD manifest
  const { data: mpdXml } = await axios.get(mpdUrl, {
    headers: {
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
      'Referer': 'https://dstv.stream/',
    },
    responseType: 'text',
  });
  fs.writeFileSync(path.join(dlDir, 'manifest.mpd'), mpdXml);

  // Parse out segment URLs from the MPD
  const baseUrlMatch = mpdXml.match(/<BaseURL>([^<]+)<\/BaseURL>/g);
  const segmentUrls = mpdXml.match(/<SegmentURL media="([^"]+)"/g) || [];

  if (segmentUrls.length === 0) {
    // Try to use ffmpeg with the MPD URL directly for demuxing
    console.log('[dstv-archiver] MPD uses dynamic segment list (template-based).');
    console.log('[dstv-archiver] Install ffmpeg for full capture: winget install ffmpeg');
  }

  // Download first N segments based on duration
  const baseUrl = baseUrlMatch?.[0]?.replace(/<\/?BaseURL>/g, '') || '';
  const segCount = Math.min(segmentUrls.length, durationMin * 60 / 2); // ~2s per segment

  for (let i = 0; i < segCount; i++) {
    const match = segmentUrls[i].match(/media="([^"]+)"/);
    if (!match) continue;
    const segUrl = new URL(match[1], baseUrl || mpdUrl).href;
    const segName = `segment_${String(i).padStart(5, '0')}.m4s`;
    try {
      const { data } = await axios.get(segUrl, {
        responseType: 'arraybuffer',
        headers: { 'Referer': 'https://dstv.stream/' },
      });
      fs.writeFileSync(path.join(dlDir, segName), Buffer.from(data));
      process.stdout.write(`\r[dstv-archiver] Downloaded ${i + 1}/${segCount} segments`);
    } catch {
      break; // End of stream
    }
  }
  console.log();
  console.log(`[dstv-archiver] Segments saved to ${dlDir}`);
}

export { downloadWithFfmpeg };
