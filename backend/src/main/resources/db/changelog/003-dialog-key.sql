--liquibase formatted sql

--changeset icqchat:004-dialog-key
-- Dialog key: the same pair of values for both directions of a conversation.
-- Generated columns are filled by PostgreSQL itself, so inserts in the code do not change.
ALTER TABLE messages
    ADD COLUMN dialog_user_low  BIGINT GENERATED ALWAYS AS (LEAST(sender_id, recipient_id)) STORED,
    ADD COLUMN dialog_user_high BIGINT GENERATED ALWAYS AS (GREATEST(sender_id, recipient_id)) STORED;

-- History is read by dialog key and id descending: the index returns rows already in the right
-- order, so a page reads only LIMIT rows instead of the whole conversation.
CREATE INDEX ix_messages_dialog ON messages (dialog_user_low, dialog_user_high, id);

-- The old index was used only for history and is replaced by ix_messages_dialog.
DROP INDEX ix_messages_conversation;
--rollback CREATE INDEX ix_messages_conversation ON messages (sender_id, recipient_id, id);
--rollback DROP INDEX ix_messages_dialog;
--rollback ALTER TABLE messages DROP COLUMN dialog_user_high, DROP COLUMN dialog_user_low;
