'use strict';

const router = require('express').Router();
const withOrgClient = require('../db/withOrgClient');
const resolveAssetId = require('../db/resolveAssetId');

router.post('/batch', async (req, res, next) => {
    try {
        const alerts = Array.isArray(req.body) ? req.body : req.body.alerts;
        const orgId = req.orgId;
        const nodeId = req.nodeId;

        if (!Array.isArray(alerts) || alerts.length === 0) {
            return res.status(400).json({ error: 'alerts array required' });
        }
        if (!nodeId) {
            return res.status(400).json({ error: 'X-Node-Id header required' });
        }
        for (const alert of alerts) {
            if (
                alert.asset_id == null ||
                !alert.severity ||
                alert.timestamp == null
            ) {
                return res.status(422).json({
                    error: 'Each alert requires asset_id, severity, and timestamp'
                });
            }
        }

        const insertedAlerts = await withOrgClient(orgId, async (client) => {
            const nodeResult = await client.query(
                `SELECT id FROM nodes WHERE node_key = $1 AND org_id = $2 LIMIT 1`,
                [nodeId, orgId]
            );
            const nodeDbId = nodeResult.rows[0]?.id ?? null;
            const inserted = [];
            for (const alert of alerts) {
                const assetId = await resolveAssetId(client, orgId, nodeId, nodeDbId, alert.asset_id);
                const result = await client.query(
                    `INSERT INTO alerts (
                        org_id, asset_id, node_id, severity, fault_label, action_text, created_at
                     ) VALUES ($1, $2, $3, $4, $5, $6, to_timestamp($7 / 1000.0))
                     RETURNING id`,
                    [
                        orgId,
                        assetId,
                        nodeId,
                        alert.severity,
                        alert.fault_label,
                        alert.action,
                        alert.timestamp
                    ]
                );
                inserted.push({ ...alert, id: result.rows[0].id, asset_id: assetId });
            }
            return inserted;
        });

        const wss = req.app.get('wss');
        if (wss) {
            insertedAlerts.forEach((alert) => {
                const push = JSON.stringify({
                    type: 'new_alert',
                    org_id: orgId,
                    alert_id: alert.id,
                    asset_id: alert.asset_id,
                    severity: alert.severity,
                    fault_label: alert.fault_label,
                    action: alert.action,
                    ts: alert.timestamp
                });
                wss.clients.forEach((client) => {
                    if (client.orgId === orgId && client.readyState === 1) {
                        client.send(push);
                    }
                });
            });
        }

        res.status(201).json({ accepted: alerts.length });
    } catch (err) {
        next(err);
    }
});

router.get('/', async (req, res, next) => {
    try {
        const result = await withOrgClient(req.orgId, (client) =>
            client.query(
                `SELECT al.id, a.name AS asset_name, al.severity, al.fault_label,
                        al.action_text, al.resolved,
                        EXTRACT(EPOCH FROM al.created_at) * 1000 AS ts
                 FROM alerts al
                 JOIN assets a ON a.id = al.asset_id
                 WHERE al.org_id = $1 AND al.resolved = $2
                 ORDER BY al.created_at DESC
                 LIMIT 100`,
                [req.orgId, req.query.resolved === 'true']
            )
        );
        res.json({ alerts: result.rows });
    } catch (err) {
        next(err);
    }
});

router.patch('/:id/resolve', async (req, res, next) => {
    try {
        await withOrgClient(req.orgId, (client) =>
            client.query(
                `UPDATE alerts
                 SET resolved = TRUE, resolved_by = $1, resolved_at = NOW()
                 WHERE id = $2 AND org_id = $3`,
                [req.userId, req.params.id, req.orgId]
            )
        );
        res.json({ ok: true });
    } catch (err) {
        next(err);
    }
});

module.exports = router;
