'use strict';

const { v5: uuidv5, validate: uuidValidate } = require('uuid');

const ASSET_NAMESPACE = '6ba7b810-9dad-11d1-80b4-00c04fd430c8';

module.exports = async function resolveAssetId(client, orgId, nodeKey, nodeDbId, rawAssetId) {
    const assetKey = String(rawAssetId);
    const assetId = uuidValidate(assetKey)
        ? assetKey
        : uuidv5(`${orgId}:${nodeKey}:${assetKey}`, ASSET_NAMESPACE);

    await client.query(
        `INSERT INTO assets (id, org_id, node_id, name, type, shaft_rpm)
         VALUES ($1, $2, $3, $4, $5, $6)
         ON CONFLICT (id) DO NOTHING`,
        [assetId, orgId, nodeDbId, `Imported Asset ${assetKey}`, 'other', 1500]
    );

    return assetId;
};
