package com.icqchat.message;

import com.icqchat.user.DataAccessException;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class MessageRepository {
    private static final String COLUMNS = "id, sender_id, recipient_id, client_id, text, status, created_at";

    private final DataSource dataSource;

    public MessageRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Inserts a message unless the sender already sent one with the same clientId.
     *
     * @return the inserted message, or empty if it is a duplicate
     */
    public Optional<Message> insertIfAbsent(long senderId, long recipientId, String clientId,
                                            String text, MessageStatus status) {
        String sql = "INSERT INTO messages (sender_id, recipient_id, client_id, text, status) " +
                "VALUES (?, ?, ?, ?, ?) ON CONFLICT (sender_id, client_id) DO NOTHING RETURNING " + COLUMNS;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, senderId);
            ps.setLong(2, recipientId);
            ps.setString(3, clientId);
            ps.setString(4, text);
            ps.setString(5, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to save message", e);
        }
    }

    public Optional<Message> findBySenderAndClientId(long senderId, String clientId) {
        String sql = "SELECT " + COLUMNS + " FROM messages WHERE sender_id = ? AND client_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, senderId);
            ps.setString(2, clientId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load message", e);
        }
    }

    /**
     * Loads messages between two users, newest first. The dialog key (lower and higher user id)
     * is the same for both directions, so the query uses ix_messages_dialog and reads only
     * {@code limit} rows.
     *
     * @param before only messages with id lower than this one, or null for the latest messages
     */
    public List<Message> findConversation(long userId, long peerId, Long before, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM messages " +
                "WHERE dialog_user_low = ? AND dialog_user_high = ?");
        if (before != null) {
            sql.append(" AND id < ?");
        }
        sql.append(" ORDER BY id DESC LIMIT ?");

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setLong(i++, Math.min(userId, peerId));
            ps.setLong(i++, Math.max(userId, peerId));
            if (before != null) {
                ps.setLong(i++, before);
            }
            ps.setInt(i, limit);
            List<Message> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load conversation", e);
        }
    }

    /**
     * Marks messages addressed to the recipient as DELIVERED. Only SENT messages are updated, so
     * READ never goes back to DELIVERED and repeated confirmations change nothing.
     *
     * @return ids of actually updated messages grouped by sender id
     */
    public Map<Long, List<Long>> markDelivered(long recipientId, List<Long> messageIds) {
        String sql = "UPDATE messages SET status = 'DELIVERED', delivered_at = now() " +
                "WHERE recipient_id = ? AND id = ANY(?) AND status = 'SENT' RETURNING id, sender_id";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            Array ids = c.createArrayOf("bigint", messageIds.toArray());
            ps.setLong(1, recipientId);
            ps.setArray(2, ids);
            Map<Long, List<Long>> bySender = new LinkedHashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    bySender.computeIfAbsent(rs.getLong("sender_id"), k -> new ArrayList<>())
                            .add(rs.getLong("id"));
                }
            }
            return bySender;
        } catch (SQLException e) {
            throw new DataAccessException("Failed to mark messages as delivered", e);
        }
    }

    /**
     * Marks all incoming messages from the sender up to the given id as READ in a single update.
     *
     * @return number of messages that changed status
     */
    public int markRead(long recipientId, long senderId, long upToMessageId) {
        String sql = "UPDATE messages SET status = 'READ', read_at = now() " +
                "WHERE recipient_id = ? AND sender_id = ? AND id <= ? AND status <> 'READ'";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, recipientId);
            ps.setLong(2, senderId);
            ps.setLong(3, upToMessageId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to mark messages as read", e);
        }
    }

    public List<Message> findUndelivered(long recipientId) {
        String sql = "SELECT " + COLUMNS + " FROM messages WHERE recipient_id = ? AND status = 'SENT' ORDER BY id";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, recipientId);
            List<Message> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load undelivered messages", e);
        }
    }

    private static Message map(ResultSet rs) throws SQLException {
        return new Message(
                rs.getLong("id"),
                rs.getLong("sender_id"),
                rs.getLong("recipient_id"),
                rs.getString("client_id"),
                rs.getString("text"),
                MessageStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()
        );
    }
}
