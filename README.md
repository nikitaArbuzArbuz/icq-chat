# ICQ Chat

Чат один на один в стиле ICQ: пользователи регистрируются, переписываются в реальном времени и видят статусы сообщений — отправлено, доставлено, прочитано. Все сообщения хранятся в PostgreSQL.

## Возможности

- Регистрация и вход по логину и паролю, авторизация через JWT (живёт сутки).
- Список контактов с поиском по логину, онлайн-индикатором и счётчиком непрочитанных.
- «Избранное» — переписка с самим собой, всегда первая в списке.
- История переписки с курсорной пагинацией: старые сообщения подгружаются при прокрутке вверх.
- Отправка сообщений через WebSocket, без дублей при повторной отправке (clientId).
- Статусы: SENT → DELIVERED → READ, обновляются у отправителя в реальном времени.
- Досылка недоставленных сообщений, когда получатель подключается.
- Несколько вкладок одного пользователя: сообщения, статусы и прочтение синхронизируются во всех.
- Онлайн-статус рассылается всем при входе и выходе; мёртвые соединения закрываются по пингу.

## Стек

| Часть | Технологии |
|---|---|
| Бэкенд | Java 21, Javalin 6 (REST + WebSocket), Gradle (Kotlin DSL) |
| БД | PostgreSQL 16, Liquibase (миграции при старте), чистый JDBC + HikariCP |
| Авторизация | JWT (HS256, java-jwt), BCrypt |
| Фронтенд | React 18, Vite, без UI-библиотек |
| Инфраструктура | Docker, docker-compose, nginx |

## Структура репозитория

```
.
├── backend/                 # Javalin-сервер
│   ├── src/main/java/com/icqchat/
│   │   ├── App.java         # точка входа, сборка зависимостей
│   │   ├── config/          # конфигурация из переменных окружения
│   │   ├── db/              # пул соединений и миграции
│   │   ├── auth/            # регистрация, вход, JWT, фильтр авторизации
│   │   ├── user/            # пользователи и список контактов
│   │   ├── message/         # сообщения: репозиторий, сервис, REST
│   │   ├── ws/              # WebSocket: обработчик, реестр сессий, события
│   │   └── common/          # ошибки и JSON
│   ├── src/main/resources/  # logback.xml, миграции Liquibase
│   └── Dockerfile
├── frontend/                # React + Vite
│   ├── src/
│   ├── nginx.conf
│   └── Dockerfile
├── docker-compose.yml
├── .env
└── README.md
```

## Быстрый старт через Docker

Нужны Docker и Docker Compose.

```bash
docker compose up --build
```

После запуска:

- веб-интерфейс: http://localhost:3000
- API бэкенда напрямую: http://localhost:8080
- PostgreSQL: localhost:5432

Переменные для docker-compose берутся из файла `.env` в корне:

| Переменная | Значение по умолчанию | Назначение |
|---|---|---|
| `POSTGRES_DB` | `chat` | имя базы |
| `POSTGRES_USER` | `chat` | пользователь БД |
| `POSTGRES_PASSWORD` | `chat` | пароль БД |
| `JWT_SECRET` | — | секрет подписи JWT, замените на длинную случайную строку |

Остановить и удалить данные:

```bash
docker compose down -v
```

## Тестовые пользователи

Создаются миграцией при первом запуске:

| Логин | Пароль |
|---|---|
| `alice` | `password123` |
| `bob` | `password123` |

## Как быстро проверить чат

1. Запустите проект: `docker compose up --build`.
2. Откройте http://localhost:3000 в обычном окне браузера и войдите как `alice` / `password123`.
3. Откройте http://localhost:3000 в окне инкогнито (или в другом браузере) и войдите как `bob` / `password123`.
4. У Алисы в списке контактов Боб отмечен зелёной точкой — он в сети.
5. Выберите собеседника и напишите сообщение. У отправителя сначала появится значок ожидания, затем одна серая галочка (сохранено), две серые (доставлено) и две синие, когда получатель откроет переписку.
6. Закройте окно Боба и напишите ему: сообщение останется с одной галочкой. Войдите за Боба снова — сообщение будет дослано, и у Алисы галочки обновятся.
7. Откройте вторую вкладку за Алису: отправленные сообщения и прочтения синхронизируются между вкладками.

## Локальный запуск без Docker

### Требования

- JDK 21
- Gradle 8.x
- Node.js 20+
- PostgreSQL 16 (можно поднять только базу: `docker compose up -d postgres`)

### Переменные окружения бэкенда

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `PORT` | `8080` | порт HTTP-сервера |
| `DB_URL` | `jdbc:postgresql://localhost:5432/chat` | JDBC URL базы |
| `DB_USER` | `chat` | пользователь БД |
| `DB_PASSWORD` | `chat` | пароль БД |
| `DB_POOL_SIZE` | `10` | размер пула соединений |
| `JWT_SECRET` | dev-значение | секрет подписи JWT (обязательно задайте в проде) |
| `JWT_TTL_HOURS` | `24` | срок жизни токена в часах |

### Бэкенд

```bash
cd backend
export JWT_SECRET=my-local-secret-at-least-32-characters
gradle run
```

Сборка дистрибутива и запуск:

```bash
cd backend
gradle installDist
./build/install/backend/bin/backend
```

Миграции Liquibase применяются автоматически при старте.

### Фронтенд

```bash
cd frontend
npm install
npm run dev
```

Откройте http://localhost:5173. Dev-сервер Vite проксирует `/api` и `/ws` на `localhost:8080`.

Продакшен-сборка: `npm run build`, результат — в `frontend/dist`.

## REST API

Все ответы — JSON. Ошибки приходят в едином формате:

```json
{"code": "LOGIN_TAKEN", "message": "Логин уже занят"}
```

Все эндпоинты, кроме регистрации, входа и `/health`, требуют заголовок `Authorization: Bearer <token>`.

### Регистрация

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"login": "charlie", "password": "password123"}'
```

Ответ `201`:

```json
{"token": "eyJ...", "user": {"id": 3, "login": "charlie"}}
```

Ошибки: `400 VALIDATION_ERROR`, `409 LOGIN_TAKEN`.

### Вход

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"login": "alice", "password": "password123"}'
```

Удобно сохранить токен в переменную (нужен `jq`):

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"login": "alice", "password": "password123"}' | jq -r .token)
```

### Текущий пользователь

```bash
curl http://localhost:8080/api/me -H "Authorization: Bearer $TOKEN"
```

### Список контактов

```bash
curl "http://localhost:8080/api/users?search=bo" -H "Authorization: Bearer $TOKEN"
```

```json
[
  {"id": 1, "login": "alice", "self": true, "online": false, "unread": 0},
  {"id": 2, "login": "bob", "self": false, "online": true, "unread": 3}
]
```

Первым всегда идёт текущий пользователь (`self: true`) — это «Избранное».

### История переписки

```bash
# последние сообщения
curl "http://localhost:8080/api/messages/2?limit=50" -H "Authorization: Bearer $TOKEN"

# сообщения старше id 120
curl "http://localhost:8080/api/messages/2?before=120&limit=50" -H "Authorization: Bearer $TOKEN"
```

Параметры: `before` — id сообщения, старше которого грузить (необязательный), `limit` — размер страницы (по умолчанию 50, максимум 100).

```json
{
  "messages": [
    {"id": 101, "senderId": 1, "recipientId": 2, "clientId": "c-1", "text": "Привет", "status": "READ", "createdAt": "2026-10-14T10:00:00Z"}
  ],
  "hasMore": true
}
```

Сообщения внутри страницы идут от старых к новым, `hasMore` показывает, есть ли ещё более старые.

### Проверка живости

```bash
curl http://localhost:8080/health
```

## WebSocket

Подключение: `ws://localhost:8080/ws?token=<JWT>` (через nginx — `ws://localhost:3000/ws?token=<JWT>`). Если токен невалидный или истёк, сервер закрывает соединение с кодом `4401`.

Все сообщения — JSON с полем `type`.

### Клиент → сервер

**Отправка сообщения**

```json
{"type": "send", "clientId": "6b1f…", "to": 2, "text": "Привет!"}
```

`clientId` генерирует клиент (например, UUID). Повторная отправка с тем же `clientId` не создаёт дубль.

**Подтверждение доставки**

```json
{"type": "delivered", "messageIds": [101, 102]}
```

**Прочтение**

```json
{"type": "read", "peerId": 1, "lastMessageId": 102}
```

Помечает прочитанными все входящие сообщения от `peerId` с id ≤ `lastMessageId`.

### Сервер → клиент

**Подтверждение сохранения** (только отправителю)

```json
{"type": "ack", "clientId": "6b1f…", "id": 101, "createdAt": "2026-10-14T10:00:00Z"}
```

**Новое сообщение** (получателю и в другие вкладки отправителя)

```json
{"type": "message", "message": {"id": 101, "clientId": "6b1f…", "senderId": 1, "recipientId": 2, "text": "Привет!", "status": "SENT", "createdAt": "2026-10-14T10:00:00Z"}}
```

**Доставлено** (отправителю)

```json
{"type": "delivered", "recipientId": 2, "messageIds": [101, 102]}
```

**Прочитано** (отправителю и в другие вкладки читателя)

```json
{"type": "read", "readerId": 2, "senderId": 1, "upToMessageId": 102}
```

**Онлайн-статус** (всем остальным пользователям)

```json
{"type": "presence", "userId": 2, "online": true}
```

**Ошибка**

```json
{"type": "error", "clientId": "6b1f…", "code": "EMPTY_MESSAGE", "message": "Сообщение не может быть пустым"}
```

`clientId` есть, если ошибка относится к конкретному отправленному сообщению.

### Проверка через websocat

```bash
websocat "ws://localhost:8080/ws?token=$TOKEN"
```

После подключения вставьте строку и нажмите Enter:

```json
{"type": "send", "clientId": "test-1", "to": 2, "text": "Привет из websocat"}
```

В ответ придёт `ack`. Если в соседнем терминале подключиться за Боба, ему придёт `message`, а после отправки `{"type": "delivered", "messageIds": [<id>]}` Алиса получит `delivered`.

## Известные ограничения

- JWT нельзя отозвать до истечения срока: выход из аккаунта только удаляет токен на клиенте.
- Онлайн-статус рассылается всем подключённым пользователям, а не только контактам.
- Сервер хранит сессии в памяти, поэтому рассчитан на один экземпляр бэкенда.
