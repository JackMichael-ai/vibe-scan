'use strict';

const pool = require('./pool');

module.exports = async function withOrgClient(orgId, work) {
    const client = await pool.connect();
    try {
        await client.query('BEGIN');
        await client.query(
            "SELECT set_config('app.current_org_id', $1, true)",
            [orgId]
        );
        const result = await work(client);
        await client.query('COMMIT');
        return result;
    } catch (err) {
        try {
            await client.query('ROLLBACK');
        } catch (_) {
            // Ignore rollback failures on already-closed transactions.
        }
        throw err;
    } finally {
        client.release();
    }
};
