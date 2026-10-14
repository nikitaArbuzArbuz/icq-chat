package com.icqchat.user;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class UserRepository {
    private final DataSource dataSource;

    public UserRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public User create(String login, String passwordHash) {
        String sql = "INSERT INTO users (login, password_hash) VALUES (?, ?) " +
                "RETURNING id, login, password_hash, created_at";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, login);
            ps.setString(2, passwordHash);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return map(rs);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to create user", e);
        }
    }

    public Optional<User> findByLogin(String login) {
        return findOne("SELECT id, login, password_hash, created_at FROM users WHERE login = ?", login);
    }

    public Optional<User> findById(long id) {
        return findOne("SELECT id, login, password_hash, created_at FROM users WHERE id = ?", id);
    }

    public boolean existsById(long id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM users WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to check user", e);
        }
    }

    /**
     * Возвращает пользователей, подходящих под поиск (плюс текущего пользователя, всегда первым),
     * и для каждого количество непрочитанных сообщений от него текущему пользователю и время
     * последнего сообщения в диалоге. Сортировка как в мессенджерах: сначала «Избранное», потом
     * диалоги по последнему сообщению от новых к старым, потом пользователи без переписки по логину.
     * <p>
     * Последнее сообщение берётся из таблицы dialogs по первичному ключу, без обращения к messages.
     */
    public List<ContactRow> findContacts(long currentUserId, String search) {
        String sql = """
                SELECT u.id, u.login, COALESCE(c.cnt, 0) AS unread, d.last_message_at
                FROM users u
                LEFT JOIN (
                    SELECT sender_id, COUNT(*) AS cnt
                    FROM messages
                    WHERE recipient_id = ? AND status <> 'READ' AND sender_id <> recipient_id
                    GROUP BY sender_id
                ) c ON c.sender_id = u.id
                LEFT JOIN dialogs d ON d.user_low = LEAST(u.id, ?) AND d.user_high = GREATEST(u.id, ?)
                WHERE u.id = ? OR u.login ILIKE ?
                ORDER BY (u.id = ?) DESC, d.last_message_id DESC NULLS LAST, u.login
                LIMIT 100
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, currentUserId);
            ps.setLong(2, currentUserId);
            ps.setLong(3, currentUserId);
            ps.setLong(4, currentUserId);
            ps.setString(5, "%" + (search == null ? "" : search.trim()) + "%");
            ps.setLong(6, currentUserId);
            List<ContactRow> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    OffsetDateTime lastMessageAt = rs.getObject("last_message_at", OffsetDateTime.class);
                    result.add(new ContactRow(rs.getLong("id"), rs.getString("login"), rs.getInt("unread"),
                            lastMessageAt == null ? null : lastMessageAt.toInstant()));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load contacts", e);
        }
    }

    private Optional<User> findOne(String sql, Object param) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load user", e);
        }
    }

    private static User map(ResultSet rs) throws SQLException {
        return new User(
                rs.getLong("id"),
                rs.getString("login"),
                rs.getString("password_hash"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()
        );
    }

    /** lastMessageAt равен null, если переписки с пользователем ещё не было. */
    public record ContactRow(long id, String login, int unread, Instant lastMessageAt) {
    }
}
