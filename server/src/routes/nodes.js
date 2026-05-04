'use strict';

const router = require('express').Router();
const withOrgClient = require('../db/withOrgClient');

router.get('/:nodeId/commands', async (req, res, next) => {
    try {
        const result = await withOrgClient(req.orgId, (client) =>
            client.query(
                `SELECT id, type, payload
                 FROM node_commands
                 WHERE node_id = (
                     SELECT id FROM nodes WHERE node_key = $1 AND org_id = $2
                 )
                   AND acked = FALSE
                   AND expires_at > NOW()
                 ORDER BY created_at ASC`,
                [req.params.nodeId, req.orgId]
            )
        );
        res.json({ commands: result.rows });
    } catch (err) {
        next(err);
    }
});

router.post('/:nodeId/commands/:cmdId/ack', async (req, res, next) => {
    try {
        await withOrgClient(req.orgId, (client) =>
            client.query(
                `UPDATE node_commands
                 SET acked = TRUE, acked_at = NOW()
                 WHERE id = $1 AND org_id = $2`,
                [req.params.cmdId, req.orgId]
            )
        );
        res.json({ ok: true });
    } catch (err) {
        next(err);
    }
});

router.post('/:nodeId/heartbeat', async (req, res, next) => {
    try {
        const { battery_level, battery_temp, android_version, app_version } = req.body;
        await withOrgClient(req.orgId, (client) =>
            client.query(
                `UPDATE nodes
                 SET last_seen_at = $1,
                     is_online = TRUE,
                     battery_level = $2,
                     battery_temp = $3,
                     android_version = COALESCE($4, android_version),
                     app_version = COALESCE($5, app_version)
                 WHERE node_key = $6 AND org_id = $7`,
                [
                    new Date(),
                    battery_level,
                    battery_temp,
                    android_version,
                    app_version,
                    req.params.nodeId,
                    req.orgId
                ]
            )
        );
        res.json({ ok: true });
    } catch (err) {
        next(err);
    }
});

router.get('/', async (req, res, next) => {
    try {
        const result = await withOrgClient(req.orgId, (client) =>
            client.query(
                `SELECT id, node_key, name, device_model, android_version,
                        app_version, kiosk_status, mount_grade, is_online,
                        battery_level, battery_temp, last_seen_at
                 FROM nodes
                 WHERE org_id = $1
                 ORDER BY name`,
                [req.orgId]
            )
        );
        res.json({ nodes: result.rows });
    } catch (err) {
        next(err);
    }
});

router.post('/:nodeId/commands', async (req, res, next) => {
    try {
        const { type, value } = req.body;
        const validTypes = ['reset_baseline', 'update_rpm', 'update_endpoint'];
        if (!validTypes.includes(type)) {
            return res.status(400).json({ error: 'Invalid command type' });
        }

        const result = await withOrgClient(req.orgId, async (client) => {
            const nodeResult = await client.query(
                `SELECT id FROM nodes WHERE node_key = $1 AND org_id = $2`,
                [req.params.nodeId, req.orgId]
            );
            if (!nodeResult.rows.length) {
                const err = new Error('Node not found');
                err.status = 404;
                throw err;
            }

            return client.query(
                `INSERT INTO node_commands (org_id, node_id, type, payload)
                 VALUES ($1, $2, $3, $4)
                 RETURNING id`,
                [req.orgId, nodeResult.rows[0].id, type, value ? { value } : {}]
            );
        });

        res.status(201).json({ command_id: result.rows[0].id });
    } catch (err) {
        next(err);
    }
});

module.exports = router;
