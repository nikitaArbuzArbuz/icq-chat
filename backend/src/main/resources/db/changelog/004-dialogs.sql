--liquibase formatted sql

--changeset icqchat:005-dialogs
-- Последнее сообщение каждого диалога, чтобы сортировать список контактов без перебора сообщений.
-- Обновляется тем же запросом, который сохраняет сообщение, поэтому не расходится с messages.
CREATE TABLE dialogs (
    user_low        BIGINT      NOT NULL REFERENCES users (id),
    user_high       BIGINT      NOT NULL REFERENCES users (id),
    last_message_id BIGINT      NOT NULL REFERENCES messages (id),
    last_message_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_low, user_high)
);

-- Заполняем по уже существующим сообщениям.
INSERT INTO dialogs (user_low, user_high, last_message_id, last_message_at)
SELECT DISTINCT ON (dialog_user_low, dialog_user_high) dialog_user_low, dialog_user_high, id, created_at
FROM messages
ORDER BY dialog_user_low, dialog_user_high, id DESC;
--rollback DROP TABLE dialogs;
