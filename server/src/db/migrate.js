'use strict';

const fs = require('fs');
const path = require('path');
const pool = require('./pool');

async function main() {
    const client = await pool.connect();
    try {
        const schemaPath = path.join(__dirname, 'schema.sql');
        const schema = fs.readFileSync(schemaPath, 'utf8');
        await client.query(schema);
        console.log('Schema migration completed successfully.');
    } finally {
        client.release();
        await pool.end();
    }
}

main().catch((err) => {
    console.error('Schema migration failed:', err);
    process.exitCode = 1;
});
