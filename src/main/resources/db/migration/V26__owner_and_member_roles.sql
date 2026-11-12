-- Two roles only: OWNER (one person) and MEMBER. ADMIN and EMPLOYEE become MEMBER.
ALTER TABLE users DROP CONSTRAINT users_role_ck;
UPDATE users SET role = 'MEMBER', updated_at = now() WHERE role IN ('ADMIN', 'EMPLOYEE');
ALTER TABLE users ADD CONSTRAINT users_role_ck CHECK (role IN ('OWNER', 'MEMBER'));

-- Saved sessions hold the old role names: everyone logs in again once.
DELETE FROM spring_session_attributes;
DELETE FROM spring_session;
