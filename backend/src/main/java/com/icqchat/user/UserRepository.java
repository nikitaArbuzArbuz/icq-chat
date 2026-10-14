package com.icqchat.user;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
     * Returns users matching the search (plus the current user, always first) with the number of
     * unread messages each of them sent to the current user.
     */
    public List<ContactRow> findContacts(long currentUserId, String search) {
        String sql = """
                SELECT u.id, u.login, COALESCE(c.cnt, 0) AS unread
                FROM users u
                LEFT JOIN (
                    SELECT sender_id, COUNT(*) AS cnt
                    FROM messages
                    WHERE recipient_id = ? AND status <> 'READ' AND sender_id <> recipient_id
                    GROUP BY sender_id
                ) c ON c.sender_id = u.id
                WHERE u.id = ? OR u.login ILIKE ?
                ORDER BY (u.id = ?) DESC, u.login
                LIMIT 100
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, currentUserId);
            ps.setLong(2, currentUserId);
            ps.setString(3, "%" + (search == null ? "" : search.trim()) + "%");
            ps.setLong(4, currentUserId);
            List<ContactRow> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new ContactRow(rs.getLong("id"), rs.getString("login"), rs.getInt("unread")));
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

    public record ContactRow(long id, String login, int unread) {
    }
}
