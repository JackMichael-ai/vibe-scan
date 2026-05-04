'use strict';

const router = require('express').Router();
const withOrgClient = require('../db/withOrgClient');

router.get('/overview', async (req, res, next) => {
    try {
        const data = await withOrgClient(req.orgId, async (client) => {
            const [assets, alerts] = await Promise.all([
                client.query(
                    `SELECT *
                     FROM asset_health
                     WHERE org_id = $1
                     ORDER BY asset_name`,
                    [req.orgId]
                ),
                client.query(
                    `SELECT *
                     FROM alert_summary
                     WHERE org_id = $1`,
                    [req.orgId]
                )
            ]);

            return {
                assets: assets.rows,
                alerts: alerts.rows[0] || {
                    org_id: req.orgId,
                    total_unresolved: 0,
                    critical_count: 0,
                    warning_count: 0,
                    latest_alert_at: null
                }
            };
        });

        res.json(data);
    } catch (err) {
        next(err);
    }
});

module.exports = router;
