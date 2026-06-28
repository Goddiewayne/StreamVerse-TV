#!/usr/bin/env node
import { Command } from 'commander';
import { listChannels } from './src/api.js';
import { captureLicense } from './src/auth.js';
import { downloadChannel } from './src/stream.js';

const program = new Command();

program
  .name('dstv-archiver')
  .description('DSTV Stream channel archiver — lists, captures, downloads, and decrypts live streams')
  .version('1.0.0');

program
  .command('list')
  .description('List all available DSTV Stream channels')
  .option('-c, --country <code>', 'Country code', 'NG')
  .option('-p, --package <name>', 'Subscription package', 'COMPACT-PLUS')
  .option('--json', 'Output as JSON')
  .action(async (opts) => {
    const channels = await listChannels(opts.country, opts.package);
    if (opts.json) {
      console.log(JSON.stringify(channels, null, 2));
    } else {
      for (const ch of channels) {
        console.log(`${ch.number.padStart(3)} | ${ch.tag} | ${ch.name} | ${ch.genre}`);
      }
      console.log(`\nTotal: ${channels.length} channels`);
    }
  });

program
  .command('capture')
  .description('Interactively capture DSTV Stream license + download a channel')
  .requiredOption('-t, --tag <tag>', 'Channel tag (e.g. H4N, MHD, BW4)')
  .option('-u, --username <email>', 'DSTV account email')
  .option('-p, --password <pass>', 'DSTV account password')
  .option('-o, --output <dir>', 'Output directory', './downloads')
  .option('--duration <min>', 'Recording duration in minutes (0 = live until stopped)', '0')
  .action(async (opts) => {
    await captureLicense({
      channelTag: opts.tag,
      username: opts.username || process.env.DSTV_USERNAME,
      password: opts.password || process.env.DSTV_PASSWORD,
      outputDir: opts.output,
      duration: parseInt(opts.duration) || 0,
    });
  });

program
  .command('download')
  .description('Download segments for a channel using stored tokens')
  .requiredOption('-t, --tag <tag>', 'Channel tag')
  .option('-o, --output <dir>', 'Output directory', './downloads')
  .option('--duration <min>', 'Duration in minutes', '15')
  .action(async (opts) => {
    await downloadChannel(opts.tag, opts.output, parseInt(opts.duration));
  });

program.parse();
