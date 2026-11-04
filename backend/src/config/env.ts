import fs from 'fs';
import path from 'path';
import dotenv from 'dotenv';

// Resolve both supported layouts whether started from the repository root,
// backend/, ts-node src/, or the compiled dist/ directory.
const backendRoot = path.resolve(__dirname, '../..');
const repositoryRoot = path.resolve(backendRoot, '..');
const candidates = [
  path.join(backendRoot, '.env'),
  path.join(repositoryRoot, '.env'),
  path.resolve(process.cwd(), '.env'),
];
const envPath = candidates.find((candidate) => fs.existsSync(candidate));
if (envPath) dotenv.config({ path: envPath });
