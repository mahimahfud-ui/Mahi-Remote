import fs from 'fs';
import path from 'path';
import dotenv from 'dotenv';

// Support both running npm scripts from /backend and starting backend from
// the repository root. Deployment platforms may inject variables directly.
const candidates = [
  path.resolve(process.cwd(), '.env'),
  path.resolve(process.cwd(), '../.env'),
];
const envPath = candidates.find((candidate) => fs.existsSync(candidate));
if (envPath) dotenv.config({ path: envPath });
