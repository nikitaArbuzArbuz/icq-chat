package com.icqchat.user;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class UserRepository {
    /** Сколько контактов отдаёт один запрос списка, вместе с «Избранным». */
    private static final int CONTACTS_LIMIT = 100;

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

    /**
     * Ищет пользователя по логину без учёта регистра. Запрос идёт по индексу ux_users_login_lower,
     * который заодно гарантирует, что логины, отличающиеся только регистром, не появятся.
     */
    public Optional<User> findByLogin(String login) {
        return findOne("SELECT id, login, password_hash, created_at FROM users WHERE lower(login) = lower(?)", login);
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
     * Список контактов как в мессенджерах: первым «Избранное» (сам пользователь, даже если не подходит
     * под поиск), дальше диалоги по последнему сообщению от новых к старым, дальше пользователи без
     * переписки по логину. Поиск по началу логина без учёта регистра, в ответе не больше CONTACTS_LIMIT
     * контактов. Для каждого контакта считается, сколько от него непрочитанных сообщений.
     * <p>
     * Список собирается несколькими короткими запросами на одном соединении. Каждый идёт по индексу и читает
     * примерно столько строк, сколько попадёт в ответ, поэтому время не растёт с числом пользователей:
     * диалоги берутся из dialogs, пользователи без переписки — из индекса ix_users_login_prefix сразу
     * в порядке логина, непрочитанные считаются только для попавших в список.
     */
    public List<ContactRow> findContacts(long currentUserId, String search) {
        String loginPattern = loginPrefixPattern(search);
        try (Connection c = dataSource.getConnection()) {
            List<Contact> contacts = new ArrayList<>(CONTACTS_LIMIT);
            findSelf(c, currentUserId).ifPresent(contacts::add);
            contacts.addAll(findDialogPeers(c, currentUserId, loginPattern, CONTACTS_LIMIT - contacts.size()));
            if (contacts.size() < CONTACTS_LIMIT) {
                // Диалоги не заполнили список, значит, в нём уже все собеседники, подходящие под поиск,
                // и среди остальных пользователей достаточно пропустить тех, кто уже в списке.
                contacts.addAll(findOtherUsers(c, loginPattern, ids(contacts), CONTACTS_LIMIT - contacts.size()));
            }
            Map<Long, Integer> unread = countUnread(c, currentUserId, ids(contacts));
            return contacts.stream()
                    .map(contact -> new ContactRow(contact.id(), contact.login(),
                            unread.getOrDefault(contact.id(), 0), contact.lastMessageAt()))
                    .toList();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load contacts", e);
        }
    }

    /** Сам пользователь и время последнего сообщения в «Избранном». */
    private static Optional<Contact> findSelf(Connection c, long userId) throws SQLException {
        String sql = """
                SELECT u.id, u.login, d.last_message_at
                FROM users u
                LEFT JOIN dialogs d ON d.user_low = u.id AND d.user_high = u.id
                WHERE u.id = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            return readContacts(ps).stream().findFirst();
        }
    }

    /**
     * Собеседники, подходящие под поиск, от последнего сообщения к старым. Пользователь бывает в паре
     * и user_low, и user_high: первое ищется по первичному ключу dialogs, второе по ix_dialogs_user_high.
     * Диалог с самим собой пропускается: «Избранное» и так первое в списке.
     */
    private static List<Contact> findDialogPeers(Connection c, long userId, String loginPattern, int limit)
            throws SQLException {
        String sql = """
                SELECT u.id, u.login, d.last_message_at
                FROM dialogs d
                JOIN users u ON u.id = CASE WHEN d.user_low = ? THEN d.user_high ELSE d.user_low END
                WHERE (d.user_low = ? OR d.user_high = ?)
                  AND d.user_low <> d.user_high
                  AND lower(u.login) COLLATE "C" LIKE lower(?) ESCAPE '\\'
                ORDER BY d.last_message_id DESC
                LIMIT ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, userId);
            ps.setLong(3, userId);
            ps.setString(4, loginPattern);
            ps.setInt(5, limit);
            return readContacts(ps);
        }
    }

    /**
     * Пользователи, подходящие под поиск, кроме excludedIds, по логину. Условие и сортировка записаны
     * тем же выражением, что и индекс ix_users_login_prefix, поэтому PostgreSQL читает индекс с начала
     * префикса и останавливается, когда набрал limit строк.
     */
    private static List<Contact> findOtherUsers(Connection c, String loginPattern, List<Long> excludedIds,
                                                int limit) throws SQLException {
        String sql = """
                SELECT u.id, u.login, NULL::timestamptz AS last_message_at
                FROM users u
                WHERE lower(u.login) COLLATE "C" LIKE lower(?) ESCAPE '\\'
                  AND u.id <> ALL (?)
                ORDER BY lower(u.login) COLLATE "C"
                LIMIT ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, loginPattern);
            ps.setArray(2, c.createArrayOf("bigint", excludedIds.toArray()));
            ps.setInt(3, limit);
            return readContacts(ps);
        }
    }

    /** Сколько непрочитанных сообщений от каждого из senderIds, по индексу ix_messages_unread. */
    private static Map<Long, Integer> countUnread(Connection c, long recipientId, List<Long> senderIds)
            throws SQLException {
        String sql = """
                SELECT sender_id, COUNT(*) AS cnt
                FROM messages
                WHERE recipient_id = ? AND sender_id = ANY (?) AND status <> 'READ' AND sender_id <> recipient_id
                GROUP BY sender_id
                """;
        Map<Long, Integer> unread = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, recipientId);
            ps.setArray(2, c.createArrayOf("bigint", senderIds.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    unread.put(rs.getLong("sender_id"), rs.getInt("cnt"));
                }
            }
        }
        return unread;
    }

    private static List<Contact> readContacts(PreparedStatement ps) throws SQLException {
        List<Contact> contacts = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                OffsetDateTime lastMessageAt = rs.getObject("last_message_at", OffsetDateTime.class);
                contacts.add(new Contact(rs.getLong("id"), rs.getString("login"),
                        lastMessageAt == null ? null : lastMessageAt.toInstant()));
            }
        }
        return contacts;
    }

    private static List<Long> ids(List<Contact> contacts) {
        return contacts.stream().map(Contact::id).toList();
    }

    /**
     * Шаблон LIKE «логин начинается со строки поиска». Символы % и _ в строке поиска означают сами себя,
     * а не «любые символы», поэтому экранируются, как и сам символ экранирования \.
     */
    private static String loginPrefixPattern(String search) {
        String prefix = search == null ? "" : search.strip();
        return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
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

    /** Контакт до подсчёта непрочитанных. */
    private record Contact(long id, String login, Instant lastMessageAt) {
    }
}
