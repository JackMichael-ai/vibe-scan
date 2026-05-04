'use strict';

const router = require('express').Router();
const withOrgClient = require('../db/withOrgClient');

router.get('/', async (req, res, next) => {
    try {
        const result = await withOrgClient(req.orgId, (client) =>
            client.query(
                `SELECT a.id, a.name, a.type, a.location, a.shaft_rpm, a.node_id,
                        a.created_at, a.is_active, n.node_key
                 FROM assets a
                 LEFT JOIN nodes n ON n.id = a.node_id
                 WHERE a.org_id = $1
                 ORDER BY a.name`,
                [req.orgId]
            )
        );
        res.json({ assets: result.rows });
    } catch (err) {
        next(err);
    }
});

router.post('/', async (req, res, next) => {
    try {
        const { name, type, location = null, shaft_rpm = 1500, node_key = null } = req.body;
        if (!name || !type) {
            return res.status(400).json({ error: 'name and type are required' });
        }

        const result = await withOrgClient(req.orgId, async (client) => {
            let nodeId = null;
            if (node_key) {
                const nodeResult = await client.query(
                    `SELECT id FROM nodes WHERE node_key = $1 AND org_id = $2`,
                    [node_key, req.orgId]
                );
                nodeId = nodeResult.rows[0]?.id ?? null;
            }

            return client.query(
                `INSERT INTO assets (org_id, node_id, name, type, location, shaft_rpm)
                 VALUES ($1, $2, $3, $4, $5, $6)
                 RETURNING id, name, type, location, shaft_rpm, node_id, created_at, is_active`,
                [req.orgId, nodeId, name, type, location, shaft_rpm]
            );
        });

        res.status(201).json({ asset: result.rows[0] });
    } catch (err) {
        next(err);
    }
});

router.patch('/:assetId', async (req, res, next) => {
    try {
        const { name, type, location, shaft_rpm, is_active } = req.body;
        const result = await withOrgClient(req.orgId, (client) =>
            client.query(
                `UPDATE assets
                 SET name = COALESCE($1, name),
                     type = COALESCE($2, type),
                     location = COALESCE($3, location),
                     shaft_rpm = COALESCE($4, shaft_rpm),
                     is_active = COALESCE($5, is_active)
                 WHERE id = $6 AND org_id = $7
                 RETURNING id, name, type, location, shaft_rpm, node_id, created_at, is_active`,
                [name, type, location, shaft_rpm, is_active, req.params.assetId, req.orgId]
            )
        );

        if (!result.rows.length) {
            return res.status(404).json({ error: 'Asset not found' });
        }

        res.json({ asset: result.rows[0] });
    } catch (err) {
        next(err);
    }
});

module.exports = router;
