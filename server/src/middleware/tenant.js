'use strict';

/**
 * tenantMiddleware — validates org_id from header matches JWT claim.
 * Critical security layer: prevents a node from one org submitting
 * data to another org's asset IDs.
 */
module.exports = function tenantMiddleware(req, res, next) {
    const headerOrgId = req.headers['x-org-id'];

    // If org header provided, it must match the JWT claim
    if (headerOrgId && headerOrgId !== req.orgId) {
        return res.status(403).json({
            error: 'org_id in header does not match token',
            code:  'ORG_MISMATCH'
        });
    }

    if (!req.orgId) {
        return res.status(403).json({ error: 'org_id required', code: 'NO_ORG' });
    }

    next();
};
