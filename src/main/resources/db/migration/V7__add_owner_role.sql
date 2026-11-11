-- New OWNER role (above ADMIN). At most one owner.
-- The bootstrap user (APP_BOOTSTRAP_ADMIN_EMAIL) is made OWNER at startup by BootstrapAdmin,
-- because the email is only known from the environment.
ALTER TABLE users DROP CONSTRAINT users_role_ck;
ALTER TABLE users ADD CONSTRAINT users_role_ck CHECK (role IN ('OWNER', 'ADMIN', 'EMPLOYEE'));

CREATE UNIQUE INDEX users_one_owner_uk ON users (role) WHERE role = 'OWNER';
