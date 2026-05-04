/**
 * VibeScan Cloud API
 * Northmark Intelligence
 *
 * Stack:
 *   Node.js + Express  — lightweight, proven, cheap to host ($5/mo on Railway/Render)
 *   PostgreSQL         — readings time-series, multi-tenant, row-level security
 *   Redis              — alert pub/sub, WebSocket fan-out to dashboard
 *   JWT                — stateless auth, works offline (node validates locally)
 *
 * Designed to run on:
 *   - Railway.app (free tier → $5/mo)
 *   - Render.com
 *   - Any VPS (DigitalOcean $6/mo, Hetzner €4/mo)
 *   - Safaricom Cloud (for Kenya-domiciled data residency)
 */

'use strict';

const express      = require('express');
const helmet       = require('helmet');
const cors         = require('cors');
const compression  = require('compression');
const rateLimit    = require('express-rate-limit');
const { createClient } = require('redis');
const { createServer }  = require('http');
const { WebSocketServer } = require('ws');

const db           = require('./db/pool');
const authMiddleware = require('./middleware/auth');
const tenantMiddleware = require('./middleware/tenant');

const readingsRouter  = require('./routes/readings');
const alertsRouter    = require('./routes/alerts');
const nodesRouter     = require('./routes/nodes');
const assetsRouter    = require('./routes/assets');
const dashboardRouter = require('./routes/dashboard');
const authRouter      = require('./routes/auth');

const app    = express();
const server = createServer(app);

// ── Redis client ──────────────────────────────────────────────────────────────
const redis = createClient({ url: process.env.REDIS_URL || 'redis://localhost:6379' });
redis.on('error', err => console.error('Redis error:', err));
redis.connect().catch(console.error);
app.set('redis', redis);

// ── WebSocket server (real-time dashboard push) ───────────────────────────────
const wss = new WebSocketServer({ server, path: '/ws' });
app.set('wss', wss);

wss.on('connection', (ws, req) => {
    // Validate JWT from ?token= query param
    const url   = new URL(req.url, 'http://localhost');
    const token = url.searchParams.get('token');
    const orgId = url.searchParams.get('org_id');

    if (!token || !orgId) { ws.close(4001, 'Unauthorized'); return; }

    try {
        const jwt = require('jsonwebtoken');
        const payload = jwt.verify(token, process.env.JWT_SECRET);
        if (payload.org_id !== orgId) { ws.close(4003, 'Forbidden'); return; }
        ws.orgId  = orgId;
        ws.userId = payload.sub;
        console.log(`WS connected: org=${orgId} user=${payload.sub}`);
    } catch {
        ws.close(4001, 'Invalid token');
        return;
    }

    ws.on('error', console.error);
    ws.on('close', () => console.log(`WS disconnected: org=${ws.orgId}`));
});

// ── Middleware ────────────────────────────────────────────────────────────────

app.use(helmet());
app.use(compression());    // gzip all responses — critical for battery-constrained nodes
app.use(cors({
    origin: process.env.ALLOWED_ORIGINS?.split(',') || '*',
    methods: ['GET', 'POST', 'PUT', 'DELETE', 'PATCH'],
    allowedHeaders: ['Content-Type', 'Authorization', 'X-Node-Id', 'X-Org-Id', 'X-App-Version']
}));
app.use(express.json({ limit: '1mb' }));    // 1MB cap — batch of 100 readings ≈ 20KB

// Rate limiting — protects against a runaway node flooding the API
const limiter = rateLimit({
    windowMs: 60 * 1000,    // 1 minute
    max:      300,           // 300 req/min per IP — generous for batched uploads
    standardHeaders: true,
    legacyHeaders: false,
    message: { error: 'Too many requests, slow down.' }
});
app.use('/api/', limiter);

// ── Routes ────────────────────────────────────────────────────────────────────

// Public
app.use('/api/v1/auth',      authRouter);

// Protected — require valid JWT + org_id header
app.use('/api/v1/readings',  authMiddleware, tenantMiddleware, readingsRouter);
app.use('/api/v1/alerts',    authMiddleware, tenantMiddleware, alertsRouter);
app.use('/api/v1/nodes',     authMiddleware, tenantMiddleware, nodesRouter);
app.use('/api/v1/assets',    authMiddleware, tenantMiddleware, assetsRouter);
app.use('/api/v1/dashboard', authMiddleware, tenantMiddleware, dashboardRouter);

// Health check (no auth — used by Railway/Render uptime probes)
app.get('/health', (req, res) => res.json({
    status: 'ok',
    uptime: Math.floor(process.uptime()),
    ts:     Date.now()
}));

// ── Error handler ─────────────────────────────────────────────────────────────

app.use((err, req, res, next) => {
    console.error(err.stack);
    const status = err.status || 500;
    res.status(status).json({
        error:   err.message || 'Internal server error',
        code:    err.code    || 'INTERNAL_ERROR'
    });
});

// ── Start ─────────────────────────────────────────────────────────────────────

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
    console.log(`VibeScan API running on port ${PORT}`);
    console.log(`Environment: ${process.env.NODE_ENV || 'development'}`);
});

module.exports = { app, server, redis, wss };
