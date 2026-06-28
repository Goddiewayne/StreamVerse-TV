import { execSync } from 'child_process';
import fs from 'fs';
import path from 'path';

/**
 * Decrypts downloaded segments using a Widevine content key.
 *
 * Two approaches:
 *   A) pywidevine (Python) — requires a .wvd device file
 *   B) shaka-packager — Google's SDK, handles Widevine decryption with a key
 *
 * This module provides both paths.
 */

/**
 * Attempts to decrypt segments using shaka-packager with a known content key.
 *
 * @param {string} segDir - Directory containing encrypted .m4s segments
 * @param {string} keyHex - 32-byte content key in hex (from license capture)
 * @param {string} keyIdHex - 16-byte key ID in hex (from license / PSSH)
 * @param {string} outputFile - Output MP4 file path
 */
export async function decryptWithKey(segDir, keyHex, keyIdHex, outputFile) {
  const segments = fs.readdirSync(segDir)
    .filter((f) => f.endsWith('.m4s'))
    .sort()
    .map((f) => path.join(segDir, f));

  if (segments.length === 0) {
    console.log('[decrypt] No .m4s segments found. Looking for ffmpeg remux...');
    return remuxWithFfmpeg(segDir, outputFile);
  }

  // Try shaka-packager first
  try {
    await decryptWithShakaPackager(segDir, keyHex, keyIdHex, outputFile);
    return;
  } catch (err) {
    console.log(`[decrypt] shaka-packager failed: ${err.message}`);
  }

  // Fallback: just concat the segments (still encrypted)
  console.log('[decrypt] No decryption tool available. Concatenating raw segments...');
  concatSegments(segments, outputFile);
}

/**
 * Uses Google's shaka-packager to decrypt and mux Widevine-encrypted segments.
 *
 *   shaka-packager input=segments/init.mp4,segments/segment_*.m4s \
 *     --decryption_key={keyHex} --enable_raw_key_decryption \
 *     --output=decrypted.mp4
 */
async function decryptWithShakaPackager(segDir, keyHex, keyIdHex, outputFile) {
  const initMp4 = path.join(segDir, 'init.mp4');
  const pattern = path.join(segDir, 'segment_*.m4s');

  const args = [
    `input=${initMp4},${pattern}`,
    `--output=${outputFile}`,
    '--enable_raw_key_decryption',
    `--keys`, `label=:key_id=${keyIdHex}:key=${keyHex}`,
    '--mp4_output_format', 'mp4',
  ];

  console.log(`[decrypt] Running: shaka-packager ${args.join(' ')}`);
  try {
    const output = execSync('shaka-packager', args, { timeout: 120000 });
    console.log(output.toString());
    console.log(`[decrypt] Decrypted: ${outputFile}`);
  } catch (err) {
    throw new Error(`shaka-packager error: ${err.stderr?.toString() || err.message}`);
  }
}

/**
 * Uses ffmpeg to remux segments if no decryptor is available.
 * For already-decrypted segments only.
 */
async function remuxWithFfmpeg(segDir, outputFile) {
  const concatFile = path.join(segDir, 'concat.txt');
  const segments = fs.readdirSync(segDir)
    .filter((f) => f.endsWith('.m4s'))
    .sort();

  const content = segments.map((f) => `file '${path.join(segDir, f)}'`).join('\n');
  fs.writeFileSync(concatFile, content);

  try {
    execSync(
      `ffmpeg -y -f concat -safe 0 -i "${concatFile}" -c copy "${outputFile}"`,
      { timeout: 120000 },
    );
    console.log(`[decrypt] Remuxed: ${outputFile}`);
  } catch (err) {
    throw new Error(`ffmpeg remux failed: ${err.message}`);
  }
}

/**
 * Pure JS segment concatenation.
 */
function concatSegments(segments, outputFile) {
  const out = fs.createWriteStream(outputFile);
  for (const seg of segments) {
    const buf = fs.readFileSync(seg);
    out.write(buf);
  }
  out.end();
  console.log(`[decrypt] Concatenated: ${outputFile}`);
}

/**
 * Tries to extract the content key from a Widevine license response
 * using pywidevine (Python tool).
 *
 * Requires: pip install pywidevine
 * Requires: a .wvd device file (from a rooted Android device or leaked CDM)
 *
 * Usage:
 *   python3 -m pywidevine license --device device.wvd --pssh {pssh_b64} --output key.bin
 *
 * @param {Buffer} licenseResponse - Raw Widevine license response
 * @param {string} psshB64 - Base64-encoded PSSH box from the MPD
 * @param {string} wvdFile - Path to .wvd device file
 * @returns {Promise<{key: string, keyId: string}>}
 */
export async function extractKeyWithPywidevine(licenseResponse, psshB64, wvdFile) {
  const tmpDir = fs.mkdtempSync('wv-');
  const licFile = path.join(tmpDir, 'license.bin');
  fs.writeFileSync(licFile, licenseResponse);

  try {
    const output = execSync(
      `python3 -m pywidevine license --device "${wvdFile}" --pssh "${psshB64}" --license "${licFile}" --output "${tmpDir}"`,
      { timeout: 30000, encoding: 'utf-8' },
    );
    // Parse the output for content key
    const keyMatch = output.match(/Content key: ([a-f0-9]{32})/i);
    const kidMatch = output.match(/Key ID: ([a-f0-9]{32})/i);
    if (keyMatch) {
      return {
        key: keyMatch[1],
        keyId: kidMatch?.[1] || '00000000000000000000000000000000',
      };
    }
    throw new Error('Could not extract content key from pywidevine output');
  } finally {
    // Cleanup
    try { fs.rmSync(tmpDir, { recursive: true }); } catch {}
  }
}

/**
 * Setup guide for Widevine decryption prerequisites.
 */
export function printSetupGuide() {
  console.log(`
=== DSTV Archiver — Decryption Setup ===

To decrypt Widevine-encrypted streams, you need ONE of:

Option A: pywidevine (recommended)
  pip install pywidevine
  # Obtain a .wvd device file (Chrome CDM or Android CDM)
  # Extract from: /path/to/Chrome/widevinecdm.dll
  # Or use: https://github.com/da3dsoul/Widevine-L3-WEB-DL

Option B: shaka-packager
  Download from: https://github.com/shaka-project/shaka-packager
  # Place 'packager' binary in PATH

Option C: ffmpeg (remux only, no decryption)
  winget install ffmpeg
  # Captures encrypted stream as-is

Usage:
  node index.js capture -t MHD -u user@email.com -p password -o ./downloads
  # Then decrypt using pywidevine or shaka-packager with the captured license
`);
}
