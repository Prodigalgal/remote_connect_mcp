// The CLI `path` reports the cache root; the paired browser lives below it.
import { launchPath } from '@camoufox/camoufox/dist/pkgman.js';
process.stdout.write(launchPath() + '\n');
