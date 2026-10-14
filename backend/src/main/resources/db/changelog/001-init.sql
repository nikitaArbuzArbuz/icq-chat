--liquibase formatted sql

--changeset icqchat:001-create-users
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    login         VARCHAR(32)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
--rollback DROP TABLE users;

--changeset icqchat:002-create-messages
CREATE TABLE messages (
    id           BIGSERIAL PRIMARY KEY,
    sender_id    BIGINT       NOT NULL REFERENCES users (id),
    recipient_id BIGINT       NOT NULL REFERENCES users (id),
    client_id    VARCHAR(64)  NOT NULL,
    text         VARCHAR(4000) NOT NULL,
    status       VARCHAR(16)  NOT NULL CHECK (status IN ('SENT', 'DELIVERED', 'READ')),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ,
    read_at      TIMESTAMPTZ
);

-- Idempotent sending: one clientId per sender.
CREATE UNIQUE INDEX ux_messages_sender_client ON messages (sender_id, client_id);

-- Conversation history: both directions are looked up by (sender, recipient) and ordered by id.
CREATE INDEX ix_messages_conversation ON messages (sender_id, recipient_id, id);

-- Unread counters and undelivered messages for a recipient.
CREATE INDEX ix_messages_unread ON messages (recipient_id, sender_id) WHERE status <> 'READ';
CREATE INDEX ix_messages_undelivered ON messages (recipient_id, id) WHERE status = 'SENT';
--rollback DROP TABLE messages;
