'use strict';

const router = require('express').Router();
const withOrgClient = require('../db/withOrgClient');
const resolveAssetId = require('../db/resolveAssetId');

/**
 * POST /api/v1/readings/batch
 *
 * Accepts { readings: [...] } and also tolerates a bare array payload.
 */
router.post('/batch', async (req, res, next) => {
    try {
        const readings = Array.isArray(req.body) ? req.body : req.body.readings;
        const orgId = req.orgId;
        const nodeId = req.nodeId;

        if (!Array.isArray(readings) || readings.length === 0) {
            return res.status(400).json({ error: 'readings array required' });
        }
        if (readings.length > 200) {
            return res.status(400).json({ error: 'Max 200 readings per batch' });
        }
        if (!nodeId) {
            return res.status(400).json({ error: 'X-Node-Id header required' });
        }

        for (const reading of readings) {
            if (
                reading.asset_id == null ||
                reading.health == null ||
                reading.fault_code == null ||
                reading.timestamp == null
            ) {
                return res.status(422).json({
                    error: 'Each reading requires asset_id, health, fault_code, timestamp'
                });
            }
        }

        const resolvedReadings = await withOrgClient(orgId, async (client) => {
            const nodeResult = await client.query(
                `SELECT id FROM nodes WHERE node_key = $1 AND org_id = $2 LIMIT 1`,
                [nodeId, orgId]
            );
            const nodeDbId = nodeResult.rows[0]?.id ?? null;

            const resolved = [];
            for (const reading of readings) {
                resolved.push({
                    ...reading,
                    asset_id: await resolveAssetId(client, orgId, nodeId, nodeDbId, reading.asset_id)
                });
            }

            const values = [];
            const params = [];
            let pIdx = 1;

            for (const reading of resolved) {
                values.push(
                    `($${pIdx++},$${pIdx++},$${pIdx++},$${pIdx++},$${pIdx++},$${pIdx++},$${pIdx++},$${pIdx++},$${pIdx++},$${pIdx++},$${pIdx++},$${pIdx++},$${pIdx++},$${pIdx++},$${pIdx++},$${pIdx++},to_timestamp($${pIdx++}/1000.0))`
                );
                params.push(
                    orgId,
                    reading.asset_id,
                    nodeId,
                    reading.health,
                    reading.fault_code,
                    reading.rms ?? null,
                    reading.kurtosis ?? null,
                    reading.crest ?? null,
                    reading.dominant_hz ?? null,
                    reading.ai_reliability ?? 100,
                    reading.mount_grade ?? null,
                    reading.rms_ms2 ?? 0,
                    reading.signal_confidence ?? 0,
                    reading.iso_zone ?? 'A',
                    reading.bpfo_energy ?? 0,
                    reading.bpfi_energy ?? 0,
                    reading.timestamp
                );
            }

            await client.query(
                `INSERT INTO readings (
                    org_id, asset_id, node_id, health, fault_code, rms,
                    kurtosis, crest, dominant_hz, ai_reliability, mount_grade,
                    rms_ms2, signal_confidence, iso_zone, bpfo_energy, bpfi_energy, recorded_at
                ) VALUES ${values.join(',')}`,
                params
            );

            const latest = resolved[resolved.length - 1];
            await client.query(
                `UPDATE nodes
                 SET last_seen_at = NOW(),
                     is_online = TRUE,
                     battery_level = $1,
                     battery_temp = $2,
                     mount_grade = $3
                 WHERE node_key = $4 AND org_id = $5`,
                [
                    latest.battery_level ?? null,
                    latest.battery_temp ?? null,
                    latest.mount_grade ?? null,
                    nodeId,
                    orgId
                ]
            );

            return resolved;
        });

        const latest = resolvedReadings[resolvedReadings.length - 1];
        const wss = req.app.get('wss');
        if (wss) {
            const push = JSON.stringify({
                type: 'readings_update',
                org_id: orgId,
                asset_id: latest.asset_id,
                health: latest.health,
                fault_code: latest.fault_code,
                rms: latest.rms,
                ts: latest.timestamp
            });
            wss.clients.forEach((client) => {
                if (client.orgId === orgId && client.readyState === 1) {
                    client.send(push);
                }
            });
        }

        res.status(201).json({ accepted: readings.length });
    } catch (err) {
        next(err);
    }
});

router.get('/:assetId', async (req, res, next) => {
    try {
        const result = await withOrgClient(req.orgId, (client) => {
            const limit = Math.min(parseInt(req.query.limit || '500', 10), 2000);
            const since = req.query.since
                ? new Date(parseInt(req.query.since, 10))
                : new Date(Date.now() - 24 * 60 * 60 * 1000);

            return client.query(
                `SELECT health, fault_code, rms, kurtosis, dominant_hz,
                        EXTRACT(EPOCH FROM recorded_at) * 1000 AS ts
                 FROM readings
                 WHERE asset_id = $1 AND org_id = $2 AND recorded_at > $3
                 ORDER BY recorded_at ASC
                 LIMIT $4`,
                [req.params.assetId, req.orgId, since, limit]
            );
        });

        res.json({ readings: result.rows });
    } catch (err) {
        next(err);
    }
});

module.exports = router;
