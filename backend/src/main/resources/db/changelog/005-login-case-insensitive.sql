--liquibase formatted sql

--changeset icqchat:006-login-case-insensitive
-- Логины уникальны без учёта регистра: Alice и alice один и тот же логин.
-- Если в базе уже есть логины, отличающиеся только регистром, создание индекса упадёт
-- с ошибкой duplicate key, и такие записи придётся разобрать вручную.
CREATE UNIQUE INDEX ux_users_login_lower ON users (lower(login));

-- Ограничение с учётом регистра больше не нужно: индекс выше строже.
ALTER TABLE users DROP CONSTRAINT users_login_key;
--rollback ALTER TABLE users ADD CONSTRAINT users_login_key UNIQUE (login);
--rollback DROP INDEX ux_users_login_lower;
