'use strict';

const jwt = require('jsonwebtoken');

/**
 * authMiddleware — validates JWT from Authorization: Bearer <token>
 * Attaches req.userId, req.orgId, req.role to the request.
 */
module.exports = function authMiddleware(req, res, next) {
    const header = req.headers['authorization'];
    if (!header || !header.startsWith('Bearer ')) {
        return res.status(401).json({ error: 'Authorization header required' });
    }

    const token = header.slice(7);
    try {
        const payload = jwt.verify(token, process.env.JWT_SECRET);
        req.userId  = payload.sub;
        req.orgId   = payload.org_id;
        req.role    = payload.role;
        req.nodeId  = req.headers['x-node-id'] || null;
        next();
    } catch (err) {
        if (err.name === 'TokenExpiredError') {
            return res.status(401).json({ error: 'Token expired', code: 'TOKEN_EXPIRED' });
        }
        return res.status(401).json({ error: 'Invalid token' });
    }
};
