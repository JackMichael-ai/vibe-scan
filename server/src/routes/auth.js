'use strict';

const router = require('express').Router();
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const db = require('../db/pool');
const authMiddleware = require('../middleware/auth');

function signToken(user) {
    return jwt.sign(
        {
            org_id: user.org_id,
            role: user.role
        },
        process.env.JWT_SECRET,
        {
            subject: user.id,
            expiresIn: '30d'
        }
    );
}

router.post('/register', async (req, res, next) => {
    const client = await db.connect();
    try {
        const { org_name, email, password, role = 'admin' } = req.body;
        if (!org_name || !email || !password) {
            return res.status(400).json({ error: 'org_name, email, and password are required' });
        }

        await client.query('BEGIN');
        const orgResult = await client.query(
            `INSERT INTO organisations (name) VALUES ($1) RETURNING id, name`,
            [org_name]
        );
        const passwordHash = await bcrypt.hash(password, 10);
        const userResult = await client.query(
            `INSERT INTO users (org_id, email, password_hash, role)
             VALUES ($1, $2, $3, $4)
             RETURNING id, org_id, email, role`,
            [orgResult.rows[0].id, email.toLowerCase(), passwordHash, role]
        );
        await client.query('COMMIT');

        const user = userResult.rows[0];
        res.status(201).json({
            token: signToken(user),
            user,
            organisation: orgResult.rows[0]
        });
    } catch (err) {
        try {
            await client.query('ROLLBACK');
        } catch (_) {
            // Ignore rollback failures.
        }
        next(err);
    } finally {
        client.release();
    }
});

router.post('/login', async (req, res, next) => {
    try {
        const { email, password } = req.body;
        if (!email || !password) {
            return res.status(400).json({ error: 'email and password are required' });
        }

        const result = await db.query(
            `SELECT u.id, u.org_id, u.email, u.role, u.password_hash, o.name AS organisation_name
             FROM users u
             JOIN organisations o ON o.id = u.org_id
             WHERE lower(u.email) = lower($1) AND o.is_active = TRUE
             LIMIT 1`,
            [email]
        );

        if (!result.rows.length) {
            return res.status(401).json({ error: 'Invalid credentials' });
        }

        const user = result.rows[0];
        const passwordOk = await bcrypt.compare(password, user.password_hash);
        if (!passwordOk) {
            return res.status(401).json({ error: 'Invalid credentials' });
        }

        await db.query(
            `UPDATE users SET last_login = NOW() WHERE id = $1`,
            [user.id]
        );

        res.json({
            token: signToken(user),
            user: {
                id: user.id,
                org_id: user.org_id,
                email: user.email,
                role: user.role,
                organisation_name: user.organisation_name
            }
        });
    } catch (err) {
        next(err);
    }
});

router.get('/me', authMiddleware, async (req, res, next) => {
    try {
        const result = await db.query(
            `SELECT u.id, u.org_id, u.email, u.role, o.name AS organisation_name
             FROM users u
             JOIN organisations o ON o.id = u.org_id
             WHERE u.id = $1
             LIMIT 1`,
            [req.userId]
        );

        if (!result.rows.length) {
            return res.status(404).json({ error: 'User not found' });
        }

        res.json({ user: result.rows[0] });
    } catch (err) {
        next(err);
    }
});

module.exports = router;
