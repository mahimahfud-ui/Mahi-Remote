import './config/env';
import http from 'http';
import path from 'path';
import express from 'express';
import cors from 'cors';
import { WebSocketServer } from 'ws';
import { apiRouter } from './api/routes';
import { SignalingHub } from './signaling/SignalingHub';
import { detectLanIp } from './utils/network';

const app = express();
const port = parseInt(process.env.PORT || '8080', 10);
const host = process.env.HOST || '0.0.0.0';

// Allow all origins for IP-based, LAN, and Cloudflare tunnel access
app.use(cors({
  origin: '*',
  methods: ['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
  allowedHeaders: ['Content-Type', 'Authorization', 'X-Device-Id', 'X-Operator-Fingerprint', 'X-Operator-Name', 'X-Operator-Location', 'X-Admin-Key', 'X-Client-Role']
}));

app.use(express.json({ limit: '50mb' }));

// API Endpoints
app.use('/api/v1', apiRouter);

// Serve Web Browser Controller & Live UI Static Files
const publicDir = path.join(__dirname, '../public');
app.use(express.static(publicDir));

// Fallback to index.html for SPA routing (except api, ws, or live)
app.get('*', (req, res, next) => {
  if (req.path.startsWith('/api') || req.path.startsWith('/ws') || req.path.startsWith('/live') || req.path.endsWith('.html') || req.path.endsWith('.js') || req.path.endsWith('.css')) {
    return next();
  }
  res.sendFile(path.join(publicDir, 'index.html'));
});

const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: '/ws' });

new SignalingHub(wss);

server.listen(port, host, () => {
  const lanIp = detectLanIp();

  console.log(`=======================================================`);
  console.log(`  MAHI REMOTE CONTROL — SERVER READY                  `);
  console.log(`=======================================================`);
  console.log(`Local:`);
  console.log(`http://localhost:${port}/`);
  console.log(``);
  console.log(`LAN:`);
  console.log(`http://${lanIp}:${port}/`);
  console.log(``);
  console.log(`Health:`);
  console.log(`http://${lanIp}:${port}/api/v1/health`);
  console.log(``);
  console.log(`WebSocket:`);
  console.log(`ws://${lanIp}:${port}/ws`);
  console.log(``);
  console.log(`Live Update Publisher:`);
  console.log(`http://${lanIp}:${port}/publisher.html`);
  console.log(`=======================================================`);
});
