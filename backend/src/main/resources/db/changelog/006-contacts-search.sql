--liquibase formatted sql

--changeset icqchat:007-contacts-search
-- Диалоги пользователя для списка контактов. В паре он бывает и user_low, и user_high: по user_low
-- ищет первичный ключ, по user_high нужен свой индекс. last_message_id в индекс не входит: он меняется
-- с каждым сообщением, и такой индекс пришлось бы обновлять при каждой отправке.
CREATE INDEX ix_dialogs_user_high ON dialogs (user_high, user_low);

-- Поиск по началу логина без учёта регистра и порядок по логину для пользователей без переписки.
-- Обычный индекс в локали базы (en_US.utf8 в Docker-образе) для LIKE 'abc%' не используется, а индекс
-- с COLLATE "C" (побайтовое сравнение) используется при любой локали. Строки в нём уже лежат по логину,
-- поэтому запрос с LIMIT читает из индекса примерно столько строк, сколько отдаёт. Уникальный индекс
-- ux_users_login_lower построен в локали базы и для этого не подходит.
CREATE INDEX ix_users_login_prefix ON users ((lower(login) COLLATE "C"));
--rollback DROP INDEX ix_users_login_prefix;
--rollback DROP INDEX ix_dialogs_user_high;
