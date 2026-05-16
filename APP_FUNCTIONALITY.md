# UniMeow: функционал приложения

Краткое описание ключевых возможностей UniMeow: GraphQL API, gateway, gRPC-контракты, сервисы, frontend и docker-compose окружение.

---

## 1. Назначение

UniMeow — университетская социальная сеть с пользователями, университетской верификацией, постами, комментариями, лайками, подписками, уведомлениями, административными заявками и несколькими типами ленты.

### Внешние точки входа

| Точка | Описание |
|---|---|
| `POST /graphql` | Основной GraphQL endpoint |
| `POST /api/auth/refresh` | Обновление токенов |
| `POST /api/auth/logout` | Выход из системы |
| `GET /oauth2/authorization/google` | OAuth2 вход через Google |
| `POST /api/upload?bucket=...` | Загрузка медиафайлов |

### Сервисы

| Сервис | Роль |
|---|---|
| `APIGateway` | GraphQL/REST API, OAuth2, JWT cookie auth, агрегация gRPC |
| `UserService` | Пользователи, сессии, refresh tokens, university email-верификация, подписки, университеты/факультеты/программы, администрирование |
| `PostService` | Посты, комментарии, лайки, CRUD и события для ленты |
| `FeedService` | Redis-backed ленты: trending, following, scoped university/faculty/program feeds и лента «Без вуза» |
| `NotificationService` | Pull-based уведомления: хранит события (лайки, комментарии, упоминания, подписки, модерация), gRPC API для APIGateway |
| `MediaService` | MinIO-backed медиа: upload, delete, presigned upload URL |
| `Eureka` | Service discovery |

### Инфраструктура

`PostgreSQL`, `Redis`, `Kafka`, `MinIO` — хранение, кэш/ленты, события, файлы.

---

## 2. Технологический стек

| Категория | Технология |
|---|---|
| Язык | Java 25 |
| Фреймворк | Spring Boot 4.0.5, Spring Cloud 2025.1.1 |
| Межсервисное взаимодействие | gRPC 1.63.0, REST (WebFlux/WebMVC) |
| Базы данных | PostgreSQL 16, Redis 7.4 |
| Брокер сообщений | Apache Kafka 4.0.0 |
| Объектное хранилище | MinIO |
| Сборка | Gradle 9.1.0 (multi-project Kotlin DSL) |
| Контейнеризация | Docker + Docker Compose |
| Мониторинг | Prometheus, Grafana |
| Frontend | Vite + React 18 |

---

## 3. Авторизация и сессии

Пользователь входит через Google OAuth2. Google используется как источник `emailGoogle`; поля профиля `name`, `surname` и `avatarUrl` заполняются через профиль приложения.

1. Frontend открывает `/oauth2/authorization/google`.
2. После успешного OAuth gateway передаёт Google email в `UserService`.
3. `UserService` создаёт или находит пользователя по `emailGoogle`.
4. Gateway выдает `ACCESS_TOKEN` и `REFRESH_TOKEN` в cookies.
5. `AuthenticationFilter` читает `ACCESS_TOKEN`, валидирует JWT и прокидывает `X-User-Id` / GraphQL context `userId`.

Аккаунт без `username` считается незавершенной регистрацией. Для такого аккаунта `name` хранится пустой строкой, `surname` и `avatarUrl` отсутствуют до заполнения профиля через `updateProfile`.

Если Google аккаунт находится в реестре бессрочно заблокированных аккаунтов, сессия не создаётся. Пользователь попадает на страницу `/banned`, где отображается причина бессрочной блокировки.

### Auth-операции

- `POST /api/auth/refresh` — обновляет access/refresh tokens по cookie `REFRESH_TOKEN`.
- `POST /api/auth/logout` — отзывает refresh token, очищает `ACCESS_TOKEN` и `REFRESH_TOKEN`.
- `POST /graphql` доступен без HTTP auth; gateway добавляет `userId` в GraphQL context при наличии валидной cookie. Авторизация resolver'ов и централизованное ограничение незавершенной регистрации выполняются на уровне GraphQL gateway.

### Конфигурация окружения (env)

Файл `.env` в корне проекта содержит два блока — PROD и DEV. Раскомментируйте нужный, второй закомментируйте, и выполните `docker compose up -d --build`. Frontend и MinIO public-URL встраиваются на этапе сборки Vite, поэтому смена окружения требует пересборки контейнера `frontend` (флаг `--build`).

Базовые env-переменные для переключения окружения:

- `APP_PUBLIC_URL` — публичный URL фронтенда / gateway (предполагается единый домен, обратный прокси раздаёт frontend и API). Из этой переменной в `docker-compose.yml` производятся:
  - `APP_FRONTEND_URL` для UserService — используется в email-ссылках (`MailService.frontendUrl`).
  - `APP_SECURITY_OAUTH2_SUCCESS_REDIRECT` для APIGateway — куда редиректить браузер после успешной Google OAuth2 авторизации.
  - `VITE_API_BASE` (build-arg для frontend) — базовый URL, в который встраиваются HTTP-вызовы из `Frontend/api.js` (`/graphql`, `/api/upload`, `/api/auth/*`, `/oauth2/authorization/google`).
- `APP_MINIO_PUBLIC_URL` — публичный URL MinIO (хранилище медиа). Используется MediaService для генерации `mediaUrl` в gRPC-ответах и frontend-сборкой через `__MINIO_PUBLIC_URL__` для отображения изображений по абсолютным ссылкам.
- `APP_SECURITY_SECURE_COOKIE` (default `false`) — выставляет `Secure` флаг на cookies `ACCESS_TOKEN` и `REFRESH_TOKEN`. В production с HTTPS должно быть `true`; иначе cookies не отправятся браузером по HTTP.
- `APP_SECURITY_ALLOWED_ORIGINS` (default `http://localhost:*,null`) — список разрешённых CORS origin'ов через запятую.

### Матрица доступа

**Публичные операции (без авторизации):**
`trendingFeed`, `getPost`, `getUser`, `getUserByUsername`, `getUserPosts`, `getComments`, `listUniversities`, `listFaculties`, `listPrograms`, `listFollowing`, `listFollowers`, `followingFeed` (только при наличии `universityId` — хронологическая лента ВУЗа)

**Требуют авторизации:**
`me`, `updateProfile`, `sendVerificationCode`, `verifyEmailCode`, `subscribe`, `unsubscribe`, `followingFeed` (без `universityId` — персональная лента подписок), `createPost`, `editPost`, `deletePost`, `likePost`, `unlikePost`, `addComment`, `editComment`, `deleteComment`, `likeComment`, `unlikeComment`, `createImprovementSuggestion`, `createUniversityProposal`, `createFacultyProposal`, `createProgramProposal`, `deleteAccount`, `getNotifications`, `getUnreadNotificationCount`, `markAllNotificationsRead`

**Требуют завершенной регистрации (`username`):**
все GraphQL mutations, кроме `updateProfile` и `deleteAccount`. До появления `username` gateway централизованно отклоняет такие mutations.

`updateProfile`, `me` и `deleteAccount` доступны авторизованному пользователю до завершения регистрации. Это позволяет странице `/complete-registration` сохранить username и позволяет удалить незавершенный аккаунт. Queries остаются доступными согласно обычным требованиям авторизации.

**Требуют прав администратора:**
`adminImprovementSuggestions`, `adminUniversityProposals`, `adminFacultyProposals`, `adminProgramProposals`, `adminReviewUniversityProposal`, `adminReviewFacultyProposal`, `adminReviewProgramProposal`, `adminBanUser`, `adminDeletePost`, `adminDeleteComment`, `adminDeleteSuggestion`

**Доступно только пользователю с username `kazenomi`:**
`adminGrantAdmin`

**Активная блокировка аккаунта:**
`me` остаётся доступным, чтобы frontend мог показать экран блокировки. GraphQL mutations, уведомления и загрузка медиа для заблокированного пользователя отклоняются. При временной блокировке пользователь видит причину и дату разблокировки; при бессрочной блокировке вход через тот же Google аккаунт невозможен.

---

## 4. Пользователи и профиль

### GraphQL queries

- `me: User` — текущий пользователь по auth cookie.
- `getUser(id: ID!): User` — профиль по UUID; UUID-ссылки поддерживаются для обратной совместимости.
- `getUserByUsername(username: String!): User` — профиль по username; используется при загрузке `/profile/{username}`.
- `listUniversities: [University!]!` — список университетов для onboarding/фильтров/профиля.
- `listFaculties(universityId: ID!): [Faculty!]!` — факультеты университета.
- `listPrograms(facultyId: ID!): [Program!]!` — образовательные программы факультета.
- `listFollowing(userId: ID!): [User!]!` — пользователи, на которых подписан профиль.
- `listFollowers(userId: ID!): [User!]!` — пользователи, подписанные на профиль.

### GraphQL mutations

- `updateProfile(input: UpdateProfileInput!): User`
- `sendVerificationCode(universityEmail: String!): VerificationResult`
- `verifyEmailCode(code: String!): VerifyResult`
- `subscribe(targetUserId: ID!): SubscribeResult`
- `unsubscribe(targetUserId: ID!): SubscribeResult`
- `createProgramProposal(input: ProgramProposalInput!): AdminActionResult!`
- `deleteAccount: DeleteResult!` — удаляет аккаунт текущего пользователя; доступно в настройках профиля для активного пользователя. При активной временной блокировке удаление аккаунта недоступно. При удалении очищаются профиль, refresh-сессии, подписки пользователя в обе стороны, посты, комментарии пользователя, комментарии под постами пользователя, лайки постов, лайки комментариев, уведомления и Redis-следы в лентах. Для чужих постов и комментариев пересчитываются денормализованные счётчики `likesCount`/`commentsCount`; для чужих постов, у которых удалён лайк пользователя, публикуется `POST_UNLIKED`, чтобы `FeedService` пересчитал score в Redis.

### Поля профиля

**Базовые:** `id`, `emailGoogle`, `username`, `name`, `surname`, `avatarUrl`, `status`, `bio`, `createdAt`

**Университетские:** `emailUniversity`, `university`, `faculty`, `program`, `course`, `educationLevel`, `graduationYear`

**Верификация:** `isStudentVerified`, `isEmployeeVerified`

**Модерация:** `isBanned`, `bannedUntil`, `banReason`

**Обновляемые через `updateProfile`:** `username`, `name`, `surname`, `status`, `avatarUrl`, `coverUrl`, `bio`, `facultyId`, `programId`, `course`, `educationLevel` (`BACHELOR`/`MASTER`/`PHD`/`SPECIALIST`), `graduationYear`

Username нормализуется при сохранении: приводится к нижнему регистру и пробелы удаляются (`strip().toLowerCase().replaceAll("\\s+", "")`). Проверка на уникальность выполняется после нормализации.
`status` хранит короткую строку профиля длиной до 80 символов. В профиле длинные слова и непрерывные последовательности символов переносятся внутри блока профиля.

### Типы

**`University`:** `id`, `name`, `shortName`, `subdomain`, `iconUrl`

**`Faculty`:** `id`, `name`, `shortName`

**`Program`:** `id`, `facultyId`, `name`, `shortName`

### Email-верификация

1. Пользователь вводит университетский email и запрашивает код.
2. `UserService` отправляет код; пользователь вводит его — backend выставляет `isStudentVerified`/`isEmployeeVerified` и автоматически привязывает пользователя к ВУЗу по `universityDomain`.
3. После подтверждения frontend предлагает выбрать факультет и программу уже внутри определённого ВУЗа; если нужной программы нет — пользователь отправляет заявку через `createProgramProposal`; завершением сохраняется `facultyId` + `programId` через `updateProfile`.

При недоступности почтового сервиса пользователь видит сообщение: «Почтовый сервис временно недоступен. Попробуйте ещё раз позже.» Неверный, истёкший или исчерпавший попытки код подтверждения отображается на русском языке.

### Подписки

- `subscribe` создает подписку текущего пользователя на другого.
- `unsubscribe` удаляет подписку.
- `listFollowing` показывает список подписок профиля.
- `listFollowers` показывает список подписчиков профиля.
- Подписки используются `FeedService` для персональной ленты `followingFeed`.

---

## 5. Посты

### GraphQL queries

- `getPost(id: ID!): Post`
- `getUserPosts(userId: ID!, page: Int, size: Int): PostPage`

### GraphQL mutations

- `createPost(input: CreatePostInput!): Post`
- `editPost(postId: ID!, input: EditPostInput!): Post`
- `deletePost(postId: ID!): DeleteResult`
- `likePost(postId: ID!): LikeResult`
- `unlikePost(postId: ID!): LikeResult`

### Типы

**`CreatePostInput`:** `content: String!`, `mediaUrls: [String!]`, `topicId: ID`; scope создаваемого поста определяется профилем автора в `UserService`

**`EditPostInput`:** `content: String`, `updateMediaUrls: Boolean`, `mediaUrls: [String!]`

**`Post`:** `id`, `authorId`, `content`, `mediaUrls`, `likesCount`, `commentsCount`, `likedByMe`, `createdAt`, `updatedAt`, `universityId`, `topicId`

### Поведение

- Создание, редактирование, удаление и лайки требуют авторизации.
- Создание, редактирование, удаление, лайки и комментарии требуют завершенной регистрации (`username`).
- `likedByMe` считается относительно текущего пользователя, если он авторизован.
- Посты создаются только через главную ленту; university ленты — read-only проекции.
- APIGateway определяет `universityId`/`facultyId`/`programId` из профиля автора в `UserService` и передаёт в PostService; клиент ничего не указывает.
- Scope поста строится по самому нижнему доступному уровню профиля автора:
  - есть программа: пост попадает в программу, факультет и ВУЗ;
  - есть факультет, но нет программы: пост попадает в факультет и ВУЗ;
  - есть только ВУЗ: пост попадает только в ВУЗ;
  - нет ВУЗа: пост попадает в ленту «Без вуза».
- Лента факультета («Факультет → Все программы») показывает все посты с этим `facultyId`, включая посты авторов без программы и посты авторов с любой программой данного факультета. Появление у автора программы не убирает его прежние посты из ленты факультета.
- Лента конкретной программы показывает только посты, созданные с указанием `programId` этой программы.
- Принадлежность автора к университету, факультету и программе показывается в карточке автора.

---

## 6. Комментарии

### GraphQL queries

- `getComments(postId: ID!, page: Int, size: Int): CommentPage`

### GraphQL mutations

- `addComment(postId: ID!, content: String!, parentCommentId: ID): Comment`
- `editComment(commentId: ID!, content: String!): Comment`
- `deleteComment(commentId: ID!): DeleteResult`
- `likeComment(commentId: ID!): LikeResult`
- `unlikeComment(commentId: ID!): LikeResult`

### Тип `Comment`

`id`, `postId`, `authorId`, `content`, `likesCount`, `likedByMe`, `createdAt`, `updatedAt`, `parentCommentId`

### Поведение

- Чтение комментариев публичное.
- Создание, редактирование, удаление и лайки требуют авторизации.
- Поддерживаются вложенные комментарии через `parentCommentId`.
- Комментарии поста возвращаются в рекомендационном порядке:

```
score = createdAtMs + likesCount × commentRecommendationLikeBoostMs
```

- `createdAtMs` — Unix timestamp создания комментария в мс.
- `commentRecommendationLikeBoostMs` — настройка `app.comments.recommendation-like-boost-ms` (по умолчанию 3 600 000 мс = 1 час).
- Score комментариев считается в `PostService` при чтении из актуальных значений `createdAt` и `likesCount`; отдельная Redis-проекция для комментариев не используется.
- При лайке/анлайке комментария меняется `likesCount`, поэтому следующий `getComments` возвращает порядок с пересчитанным score.

---

## 7. Ленты

### GraphQL queries

- `trendingFeed(cursor: String, size: Int, universityId: ID, facultyId: ID, programId: ID, topicId: ID): FeedPage`
- `followingFeed(cursor: String, size: Int, universityId: ID, facultyId: ID, programId: ID, topicId: ID): FeedPage`

### Тип `FeedPage`

`posts: [Post!]!`, `nextCursor: String`, `hasMore: Boolean!`

---

### `trendingFeed` — вкладка «Рекомендации»

Лента рекомендаций с time-decay рейтингом. Показывается всем пользователям. Не обязательно хронологическая.

**Формула оценки:**

```
score = createdAtMs + likesCount × trendingLikeBoostMs
```

- `createdAtMs` — Unix timestamp создания поста в мс.
- `trendingLikeBoostMs` — настройка `app.feed.trending-like-boost-ms` (по умолчанию 3 600 000 мс = 1 час).

Каждый лайк поднимает пост в рейтинге на `trendingLikeBoostMs` мс. Со временем новые посты без лайков превышают старые залайканные: time-decay встроен в базовый `createdAtMs`.

**Пример** (boost = 1 ч):
- Пост A: создан 3 дня назад, 100 лайков → score = now − 72h + 100h = now + 28h
- Пост B: создан только что, 0 лайков → score = now + 0h
- Пост A выше Поста B — пока не появится пост с 28 лайками той же свежести.

Score обновляется при каждом лайке/анлайке. Redis key: `feed:popular` (общая внутренняя), `feed:outside:popular` (без вуза), `feed:uni:{id}:popular`, `feed:uni:{id}:topic:{id}:popular`. Окно: последние 5000 постов на каждую trending-ленту (`app.feed.trending-window-size`). Хронологические university/faculty/program ленты ограничены `app.feed.uni-window-size` (5000), лента автора `feed:author:{id}` — `app.feed.author-window-size` (1000), персональная лента подписок `feed:user:{id}` — `app.feed.user-window-size` (1000). При превышении окна `trim` удаляет элементы с наименьшим score: для хронологических лент это самые старые посты, для trending — посты с наименьшим `createdAtMs + likesCount × boost`.

---

### `followingFeed` — вкладка «Подписки» и ленты университетов

Хронологическая лента. Доступна в двух режимах:

**Режим «Подписки» (главная лента):** персональная лента из постов авторов, на которых подписан пользователь. Требует авторизации.
- Score = `occurredAt` в мс. Порядок: новые выше.
- При подписке: последние 100 постов автора backfill-ятся в ленту подписчика.
- При отписке: все доступные посты автора (до `app.feed.author-window-size = 1000`) удаляются из ленты подписчика.
- Новый пост автора добавляется всем его подписчикам.
- Redis key: `feed:user:{userId}`. Окно: последние 1000 постов (`app.feed.user-window-size`). При фильтре по ВУЗу: временное пересечение `ZINTERSTORE(feed:user:{id}, feed:uni:{uniId})` с TTL 60 с.

**Режим «Лента ВУЗа»:** публичная лента всех постов университета. Во frontend ВУЗ-контекст использует `trendingFeed`, поэтому посты отсортированы по `createdAtMs + likesCount × trendingLikeBoostMs`. Авторизация не требуется. Лента доступна всем посетителям без фильтрации по подпискам.

GraphQL-запросы ленты: `trendingFeed` (рекомендации с time-decay; главная лента и ВУЗ-ленты), `followingFeed` (персональная лента подписок — только главная).


---

### Скоупы

| Параметры запроса | Запрос | Читается из Redis |
|---|---|---|
| нет universityId | `trendingFeed` | `feed:popular` |
| нет universityId | `followingFeed` | `feed:user:{id}` |
| `topicId = "-1"` без universityId | `trendingFeed` | `feed:outside:popular` |
| universityId | `trendingFeed` | `feed:uni:{id}:popular` |
| universityId + facultyId | `trendingFeed` | `feed:uni:{id}:topic:{facId}:popular` |
| universityId + programId | `trendingFeed` | `feed:uni:{id}:subtopic:{progId}:popular` |

ВУЗ-скоупы read-only: inline-compose скрыт, вкладки «Рекомендации»/«Подписки» не показываются; frontend читает ВУЗ, факультет и программу через `trendingFeed`.
Контекст «Без вуза» использует отдельный backend scope и не фильтрует общую ленту на клиенте.

### Пагинация

- `size` по умолчанию = 20 (`app.feed.default-page-size`), максимум 100 (`app.feed.max-page-size`).
- `cursor` = score последнего поста. Следующий запрос берёт всё ниже этого значения.
- Для `trendingFeed`: cursor = `createdAtMs + likesCount * boost`. При одинаковых score возможен пропуск одного поста — известное ограничение.

### Внутренняя механика

**PostService** публикует в Kafka топик `post-events` через outbox:

| Событие | Когда |
|---|---|
| `POST_CREATED` | Пост создан (включает `mentionedUserIds`) |
| `POST_DELETED` | Пост удалён |
| `POST_LIKED` | Лайк поставлен (включает `actorId`, `authorId`) |
| `POST_UNLIKED` | Лайк снят |
| `COMMENT_CREATED` | Комментарий создан (включает `postAuthorId`, `parentCommentId`, `parentAuthorId`, `mentionedUserIds`) |
| `COMMENT_LIKED` | Лайк на комментарий (включает `commentAuthorId`, `actorId`, `postId`) |

**UserService** публикует в Kafka топик `user-events` через outbox:

| Событие | Когда |
|---|---|
| `USER_FOLLOWED` | Пользователь подписался |
| `USER_UNFOLLOWED` | Пользователь отписался |
| `USER_DELETED` | Аккаунт удалён, внешние сервисы очищают контент и кэшированные следы |
| `USER_PERMANENT_BANNED` | Бессрочная блокировка аккаунта, внешние сервисы очищают контент и кэшированные следы |
| `ADMIN_GRANTED` | Пользователю выданы права администратора |
| `USER_BANNED` | Пользователь заблокирован |

**FeedService** (`FeedKafkaConsumer` → `FeedEventService`) слушает оба топика и обновляет Redis sorted sets:

- `POST_CREATED` — добавляет пост в university/faculty/program feeds или «Без вуза», author feed, trending feed и во все personal feeds подписчиков автора.
- `POST_DELETED` — удаляет пост из всех feeds.
- `POST_LIKED` / `POST_UNLIKED` — пересчитывает trending score поста.
- `USER_FOLLOWED` — backfill последних 100 постов автора в personal feed подписчика.
- `USER_UNFOLLOWED` — удаляет последние 500 постов автора из personal feed подписчика.
- `USER_DELETED` — убирает подписки, связанные с аккаунтом, и чистит Redis-следы пользователя.
- `USER_PERMANENT_BANNED` — убирает подписки, связанные с аккаунтом, и чистит Redis-следы пользователя.

Дедупликация: каждый Kafka-event помечается в Redis на 7 дней (`feed:processed:event:{eventId}`).

**PostService** слушает `user-events`:

- `USER_DELETED` — удаляет посты, комментарии, лайки и связанные счётчики пользователя; для чужих постов с удалёнными лайками пользователя публикует `POST_UNLIKED` с актуальным `likesCount`.
- `USER_PERMANENT_BANNED` — удаляет посты, комментарии, лайки и связанные счётчики пользователя; для чужих постов с удалёнными лайками пользователя публикует `POST_UNLIKED` с актуальным `likesCount`.

**NotificationService** слушает `post-events` и `user-events`:

- `POST_CREATED` — уведомления об упоминаниях в посте.
- `POST_LIKED` — уведомление автору поста.
- `COMMENT_CREATED` — уведомления автору поста, автору родительского комментария и упомянутым пользователям.
- `COMMENT_LIKED` — уведомление автору комментария.
- `USER_FOLLOWED` — уведомление о подписке.
- `ADMIN_GRANTED` — уведомление о выдаче прав администратора.
- `USER_BANNED` — уведомление о блокировке.
- `USER_DELETED` и `USER_PERMANENT_BANNED` — очистка уведомлений, связанных с аккаунтом.

**DLQ-топики:**

| Источник | DLQ topic | Когда используется |
|---|---|---|
| `UserService` outbox publisher | `user-events.dlq` | Ошибка публикации события `user-events` в Kafka |
| `PostService` outbox publisher | `post-events.dlq` | Ошибка публикации события `post-events` в Kafka |
| `FeedService` Kafka consumer | `feed-events.dlq` | Ошибка обработки события из `post-events` или `user-events` |
| `NotificationService` Kafka consumer | `notification-events.dlq` | Ошибка обработки события из `post-events` или `user-events` |

Gateway получает список post IDs из `FeedService` по gRPC, затем заполняет посты через `PostService`.

Комментарии не входят в Redis-ленты `FeedService`: они хранятся и сортируются в `PostService` в рамках конкретного поста.

### Redis persistence

Redis сконфигурирован с AOF (Append-Only File) + `appendfsync everysec`. Данные сохраняются на диск каждую секунду; при рестарте контейнера ленты восстанавливаются из `redis_data` volume.

---

## 8. Примеры flow ленты

### Анонимный пользователь

```graphql
query {
  trendingFeed(size: 10) {
    posts { id content authorId universityId likesCount commentsCount }
    nextCursor
    hasMore
  }
}
```

- Видит `trendingFeed`.
- При попытке `followingFeed` получает GraphQL error `UNAUTHORIZED`.
- При попытке создать пост, лайкнуть или комментировать — `UNAUTHORIZED`.

---

### Авторизованный пользователь без подписок

```graphql
query {
  followingFeed(size: 10) {
    posts { id content authorId createdAt }
    nextCursor
    hasMore
  }
}
```

- `followingFeed` доступен, но возвращает пустой список.
- Может подписаться через `subscribe`.

---

### Авторизованный пользователь с подписками

```graphql
query {
  followingFeed(size: 10, universityId: "1", topicId: "5") {
    posts { id content universityId topicId }
    nextCursor
    hasMore
  }
}
```

- `followingFeed` читает Redis feed `feed:user:{userId}`.
- При `universityId` результат дополнительно фильтруется по университету.

---

### Пользователь с университетом

```graphql
mutation {
  createPost(input: {
    content: "Новая запись в главной ленте"
    mediaUrls: []
  }) {
    id content universityId topicId
  }
}
```

Пост появляется в: university feed автора, following feed подписчиков автора, и учитывается в `trendingFeed` после лайков.

### Пользователь без ВУЗа

```graphql
query {
  trendingFeed(topicId: "-1", size: 10) {
    posts { id content universityId }
  }
}
```

Посты авторов без `universityId` читаются из отдельной ленты «Без вуза».

---

## 9. Уведомления

Уведомления реализованы по pull-модели: клиент запрашивает список при каждой навигации.

### GraphQL queries

- `getNotifications(page: Int, size: Int): NotificationPage!` — список уведомлений текущего пользователя, отсортированных по убыванию даты.
- `getUnreadNotificationCount: Int!` — количество непрочитанных уведомлений (polling каждые 60 с для отображения бейджа на иконке колокольчика).

### GraphQL mutations

- `markAllNotificationsRead: Boolean!` — помечает все уведомления прочитанными; вызывается автоматически при открытии страницы уведомлений.

### Тип `Notification`

`id`, `userId`, `actorId`, `actor: User`, `type`, `entityId`, `entityType`, `parentEntityId`, `isRead`, `createdAt`

`parentEntityId` хранит идентификатор родительской сущности: для уведомлений о комментариях (`COMMENT_ON_POST`, `REPLY_TO_COMMENT`, `LIKE_COMMENT`, `MENTION_IN_COMMENT`) это `postId` поста, к которому относится комментарий. Frontend использует его, чтобы клик по уведомлению про комментарий открывал страницу поста.

### Типы уведомлений

| Тип | Описание |
|---|---|
| `LIKE_POST` | Кто-то поставил лайк на пост пользователя |
| `LIKE_COMMENT` | Кто-то поставил лайк на комментарий |
| `COMMENT_ON_POST` | Кто-то прокомментировал пост пользователя |
| `REPLY_TO_COMMENT` | Кто-то ответил на комментарий пользователя |
| `MENTION_IN_POST` | Упоминание через @username в посте |
| `MENTION_IN_COMMENT` | Упоминание через @username в комментарии |
| `FOLLOW` | Кто-то подписался на пользователя |
| `ADMIN_GRANTED` | Пользователю выданы права администратора |
| `BANNED` | Пользователь заблокирован |

### Источники событий

**PostService** дополнительно публикует:

| Событие | Когда |
|---|---|
| `COMMENT_CREATED` | Комментарий создан (включает `postAuthorId`, `parentCommentId`, `parentAuthorId`, `mentionedUserIds`) |
| `COMMENT_LIKED` | Лайк на комментарий (включает `commentAuthorId`, `actorId`, `postId`) |
| `POST_LIKED` | Лайк поставлен (включает `actorId`, `authorId`) |

**UserService** дополнительно публикует:

| Событие | Когда |
|---|---|
| `ADMIN_GRANTED` | Пользователю выданы права администратора |
| `USER_BANNED` | Пользователь заблокирован |
| `USER_DELETED` | Аккаунт удалён |
| `USER_PERMANENT_BANNED` | Бессрочная блокировка аккаунта |

**@mentions:** PostService разбирает контент на наличие `@username` через regex, резолвит username→userId через gRPC `GetUserByUsername` в UserService и передаёт массив `mentionedUserIds` в событии.

### NotificationService

- Слушает топики `post-events` и `user-events`.
- При `USER_DELETED` и `USER_PERMANENT_BANNED` удаляет уведомления, где аккаунт был получателем, актором или user-сущностью.
- Дедупликация Kafka-событий: таблица `processed_events` с eventId.
- Дедупликация уведомлений по содержанию: для типов `LIKE_POST`, `LIKE_COMMENT`, `FOLLOW` при создании ищется существующее уведомление с тем же `(userId, actorId, type, entityId)` за последние 30 дней. Если найдено — обновляются `createdAt` и `isRead = false` (поднимает уведомление наверх и делает непрочитанным), новой записи не создаётся. Это защищает от спам-сценариев «поставил/снял лайк/подписку много раз». Для `COMMENT_ON_POST`, `REPLY_TO_COMMENT`, `MENTION_*` и других типов с уникальным контентом дедупликация не применяется — каждое событие уникально.
- Self-уведомления отфильтровываются на этапе создания: `actorId.equals(authorId)` для лайков, `parentAuthorId.equals(authorId)` для ответов, `mentionedUserId.equals(authorId)` для упоминаний, `subscriberId.equals(targetUserId)` для подписки. Пользователь не получает уведомлений о собственных действиях, даже если технически API позволяет лайкнуть свой пост или ответить на свой комментарий.
- TTL: уведомления старше 90 дней удаляются по расписанию (каждую ночь в 3:00).
- gRPC сервер: `GetNotifications`, `MarkAllRead`, `GetUnreadCount`.
- Порты: `9006` (HTTP/health), `9095` (gRPC).
- БД: отдельный `notification_db` в PostgreSQL.

### Отображение

- В боковом меню и мобильной навигации — иконка колокольчика с красным бейджем количества непрочитанных.
- Страница `/notifications` — список уведомлений с аватаром актора, типом действия, временем. Непрочитанные помечены синей точкой и выделенным фоном.

---

## 10. Медиа

`MediaService` предоставляет gRPC API: `UploadFile`, `DeleteFile`, `GeneratePresignedUploadUrl`.

**Хранилище:**

- MinIO bucket/data volume.
- Публичный URL настраивается через `MINIO_PUBLIC_URL`.
- При старте сервис создает buckets: `university-icons`, `user-avatars`, `user-banners`, `post-media`.

**Buckets:**

| Bucket | Содержимое |
|---|---|
| `university-icons` | Иконки университетов (svg, png) |
| `user-avatars` | Аватары пользователей |
| `user-banners` | Обложки/баннеры профилей |
| `post-media` | Медиафайлы постов |

Поддерживаемые изображения: SVG, PNG, JPEG, WEBP, GIF. GIF-файлы загружаются без сжатия, чтобы сохранялась анимация. Для `post-media` также поддерживаются MP4, WEBM, MOV, M4V, OGG/OGV.

**Потребители:**

- `APIGateway` принимает `POST /api/upload?bucket=...`, передает файл в `MediaService`, возвращает публичный MinIO URL.
- Frontend сначала загружает файл через `/api/upload`, затем сохраняет URL в БД/профиле/посте.
- Загрузка медиа доступна активному авторизованному пользователю; при активной блокировке gateway возвращает ошибку доступа.
- Presigned upload URL доступен на gRPC уровне, но не выведен отдельной GraphQL mutation.

---

## 11. Администрирование

`is_admin` выставляется через `adminGrantAdmin`. Доступ к этой операции ограничен проверкой username `kazenomi` в `UserService.grantAdmin()` — только `kazenomi` может выдавать права администратора, в том числе самому себе.

Правила блокировки пользователей:

- Любой администратор может заблокировать пользователя без прав администратора.
- Только `kazenomi` может заблокировать другого администратора.
- Пользователя с username `kazenomi` нельзя заблокировать.
- Временная блокировка хранится в `users.banned_until` и `users.ban_reason`. Срок временной блокировки должен быть датой в будущем; в интерфейсе администратора срок задаётся целым количеством дней от 1 до 3650.
- Бессрочная блокировка хранится в `banned_google_accounts`: `email_google`, `reason`, `moderator_id`, `banned_at`. Запись пользователя в `users` удаляется; refresh-сессии, подписки, посты, комментарии, лайки, уведомления и Redis-следы очищаются через локальные операции и события. Повторный вход или регистрация через тот же Google аккаунт невозможны.
Пустой срок в форме блокировки означает бессрочную блокировку.

### Действия администратора

| Mutation | Описание |
|---|---|
| `adminGrantAdmin(targetUserId: ID!)` | Выдать права администратора любому пользователю (включая себя); доступно только `kazenomi` |
| `adminBanUser(input: BanUserInput!)` | Блокировка навсегда или до даты с причиной |
| `adminDeletePost(postId: ID!)` | Удалить любой пост |
| `adminDeleteComment(commentId: ID!)` | Удалить любой комментарий |
| `adminDeleteSuggestion(id: ID!)` | Удалить предложение улучшения |
| `adminReviewUniversityProposal(proposalId: ID!, status: String!)` | Одобрить/отклонить заявку ВУЗа |
| `adminReviewFacultyProposal(proposalId: ID!, status: String!)` | Одобрить/отклонить заявку факультета |
| `adminReviewProgramProposal(proposalId: ID!, status: String!)` | Одобрить/отклонить заявку программы |

**Queries только для администратора:** `adminImprovementSuggestions`, `adminUniversityProposals`, `adminFacultyProposals`, `adminProgramProposals`

**Mutations для любого авторизованного пользователя:** `createImprovementSuggestion`, `createUniversityProposal`, `createFacultyProposal`, `createProgramProposal`

Gateway блокирует действия активного временно заблокированного пользователя: mutations, уведомления и загрузку медиа. Frontend показывает экран блокировки с причиной и сроком разблокировки. Публичный профиль временно заблокированного пользователя показывает причину и дату разблокировки. После окончания срока пользователь снова считается активным.

### Флоу заявки на университет

1. Авторизованный пользователь отправляет `createUniversityProposal`.
2. Заявка получает статус `NEW` и сохраняется в `university_proposals`.
3. Администратор просматривает `adminUniversityProposals`.
4. При `APPROVED` backend создает запись университета и student/employee email domains.
5. При `REJECTED` меняется только статус заявки.

### Хранение административных данных

- `improvement_suggestions` — `author_id`, `text`, `status`, `created_at`.
- `university_proposals` — `author_id`, `name`, `short_name`, `subdomain`, `student_domain`, `employee_domain`, `city`, `description`, `icon_url`, `status`, `reviewed_by`, `reviewed_at`.
- `faculty_proposals` — `author_id`, `university_id`, `name`, `short_name`, `status`, `reviewed_by`, `reviewed_at`.

### Управление университетами, факультетами и программами

**Университеты:**
- Стартовые данные не зашиты в миграции; всё через approve-flow.
- Иконка загружается в MinIO `university-icons`; в БД хранится только `icon_url`.

**Факультеты:**
- Создаются при одобрении заявки пользователя.
- Backend проверяет уникальность `shortName` внутри университета.

**Программы:**
- Пользователь не создаёт программу напрямую — только через `createProgramProposal`.
- При `APPROVED` создаётся запись в `university_programs`.
- Если программа с таким `shortName` уже существует — возвращается существующая запись.

---

## 12. Frontend

Основная точка входа: `Frontend/index.html`.

**Сборка:** Vite + React 18.

**Demo/mock режим:** `Frontend/mock.js` перехватывает GraphQL-вызовы в `Frontend/api.js`. Доступны моковые университеты, факультеты, программы, пользователи, посты, комментарии, предложения улучшений, заявки на университеты и программы — для документации и скриншотов без реального backend.

**Функции:**

- Auth: Google login, refresh, logout, status bar.
- Внешний вид: светлая тема, тёмная тема и режим «Как в системе». Режим «Как в системе» использует `prefers-color-scheme` браузера и реагирует на смену системной темы.
- Профиль: загрузка `me`, обновление профиля, просмотр подписок и подписчиков, поиск по username (`/explore`), email-верификация, аватар/баннер. URL профиля — `/profile/{username}`; поддерживается также `/profile/{userId}` (UUID) для обратной совместимости.
- Блокировка аккаунта: активный временно заблокированный пользователь видит отдельный экран с причиной и датой разблокировки; бессрочно заблокированный Google аккаунт видит поп-ап на странице входа с причиной блокировки.
- Лента: вкладки «Рекомендации» (`trendingFeed`), «Подписки» (`followingFeed`); фильтры по университету, факультету, программе.
- ВУЗ-контекст: read-only лента (`trendingFeed`) без compose и без вкладки «Подписки»; чипы факультетов и программ. URL — `/{subdomain}/` (если задан subdomain) или генерируется из shortName.
- Контекст «Без ВУЗа»: отдельная trending-лента постов авторов без `universityId` (поступление, общие вопросы). URL — `/outside/`.
- Посты: создание, редактирование, удаление, лайк/анлайк, share (копирует ссылку на пост в буфер обмена). Страница поста `/post/{id}` содержит кнопки лайк, комментарий, share.
- Комментарии: получение в рекомендационном порядке, создание, редактирование, удаление, лайк/анлайк; после лайка/анлайка страница поста синхронизирует состояние лайка и порядок комментариев с backend.
- Подписки: подписаться, отписаться, просмотреть подписки и подписчиков профиля.
- Медиа: загрузка аватара (`user-avatars`), баннера (`user-banners`), иконки ВУЗа (`university-icons`, svg/png), медиа постов (`post-media`).
- Заявки: предложение улучшения, заявка ВУЗа (название, shortName, subdomain, student domain, employee domain, иконка), заявка факультета, заявка программы.
- Админка: просмотр предложений улучшения, заявок ВУЗов, факультетов, программ; одобрение/отклонение заявок с подтверждением. В заявке на ВУЗ — ссылка на ленту по subdomain. Кнопка блокировки показывается администраторам на профилях пользователей без прав администратора; `kazenomi` видит кнопку блокировки на любых чужих профилях.
- Уведомления: страница `/notifications` со списком уведомлений, бейдж непрочитанных на иконке колокольчика в боковом меню и мобильной навигации. Время отображается в таймзоне пользователя.
- Дефолтный аватар: если у пользователя нет аватарки, frontend показывает цветной аватар с инициалами.
- Email подтверждения: письмо содержит preview text «Ваш код для подтверждения университетского email...» и HTML-шаблон с кодом подтверждения.
- GraphQL-таймаут запроса: 30 секунд на frontend. При сетевой ошибке, abort или временной ошибке апстрима (`UPSTREAM_ERROR`, `SERVICE_UNAVAILABLE`, `GATEWAY_TIMEOUT`, `UNAVAILABLE`, `INTERNAL`, `UNKNOWN`, `DEADLINE_EXCEEDED`, `end-of-stream`, `http2 exception`, `rst_stream`, `goaway`, и т.д.) клиент автоматически повторяет GraphQL-запрос до 3 раз с задержкой 200 мс. При истекшем access token выполняется один auth-retry после успешного `/api/auth/refresh`.
- gRPC-вызовы из API Gateway имеют client-side дедлайн (см. `GrpcDeadlineInterceptor`, `APP_GRPC_DEADLINE_MS`, default 10 секунд). Чтение профилей пользователей для GraphQL (`GetUserById`/`GetUserByUsername`) имеет отдельный короткий дедлайн `APP_GRPC_USER_READ_DEADLINE_MS` (default 2 секунды), чтобы вторичная гидрация авторов не держала ленту до глобального дедлайна. Длительные и неуспешные gRPC-вызовы логируются на gateway, длительные GraphQL-запросы (>1с) логируются с операцией.
- gRPC transport в API Gateway использует `grpc-netty-shaded`, чтобы gRPC HTTP/2 Netty был изолирован от Reactor Netty, используемого WebFlux HTTP-сервером.
- gRPC keep-alive: клиент пингует канал раз в 30 секунд (включая idle), таймаут пинга 5 секунд. Сервер разрешает idle-пинги, но не чаще чем раз в 20 секунд. Keepalive используется только для обнаружения мёртвого TCP/HTTP2 соединения, а не как retry-механизм.

---

## 13. GraphQL surface

### Queries

```graphql
me: User
getUser(id: ID!): User
getUserByUsername(username: String!): User
listUniversities: [University!]!
listFaculties(universityId: ID!): [Faculty!]!
listPrograms(facultyId: ID!): [Program!]!
listFollowing(userId: ID!): [User!]!
listFollowers(userId: ID!): [User!]!
adminImprovementSuggestions: [ImprovementSuggestion!]!
adminUniversityProposals: [UniversityProposal!]!
adminFacultyProposals: [FacultyProposal!]!
adminProgramProposals: [ProgramProposal!]!
getUserPosts(userId: ID!, page: Int, size: Int): PostPage!
getPost(id: ID!): Post
getComments(postId: ID!, page: Int, size: Int): CommentPage!
trendingFeed(cursor: String, size: Int, universityId: ID, facultyId: ID, programId: ID, topicId: ID): FeedPage!
followingFeed(cursor: String, size: Int, universityId: ID, facultyId: ID, programId: ID, topicId: ID): FeedPage!
getNotifications(page: Int, size: Int): NotificationPage!
getUnreadNotificationCount: Int!
```

### Mutations

```graphql
updateProfile(input: UpdateProfileInput!): User
sendVerificationCode(universityEmail: String!): VerificationResult!
verifyEmailCode(code: String!): VerifyResult!
subscribe(targetUserId: ID!): SubscribeResult!
unsubscribe(targetUserId: ID!): SubscribeResult!
createPost(input: CreatePostInput!): Post!
editPost(postId: ID!, input: EditPostInput!): Post!
deletePost(postId: ID!): DeleteResult!
likePost(postId: ID!): LikeResult!
unlikePost(postId: ID!): LikeResult!
addComment(postId: ID!, content: String!, parentCommentId: ID): Comment!
editComment(commentId: ID!, content: String!): Comment!
deleteComment(commentId: ID!): DeleteResult!
likeComment(commentId: ID!): LikeResult!
unlikeComment(commentId: ID!): LikeResult!
createImprovementSuggestion(text: String!): AdminActionResult!
createUniversityProposal(input: UniversityProposalInput!): AdminActionResult!
createFacultyProposal(input: FacultyProposalInput!): AdminActionResult!
createProgramProposal(input: ProgramProposalInput!): AdminActionResult!
deleteAccount: DeleteResult!
adminGrantAdmin(targetUserId: ID!): User
adminBanUser(input: BanUserInput!): AdminActionResult!
adminDeletePost(postId: ID!): DeleteResult!
adminDeleteComment(commentId: ID!): DeleteResult!
adminDeleteSuggestion(id: ID!): DeleteResult!
adminReviewUniversityProposal(proposalId: ID!, status: String!): AdminActionResult!
adminReviewFacultyProposal(proposalId: ID!, status: String!): AdminActionResult!
adminReviewProgramProposal(proposalId: ID!, status: String!): AdminActionResult!
markAllNotificationsRead: Boolean!
```

---

## 14. gRPC surface

### Межсервисные gRPC-взаимодействия

| Клиент | Сервер | RPC |
|---|---|---|
| `APIGateway` | `UserService` | `CreateOrGetUser`, `GetUserById`, `GetUserByUsername`, `UpdateUser`, `DeleteAccount`, `CreateSession`, `RefreshSession`, `RevokeRefreshToken`, `SendVerificationCode`, `VerifyEmailCode`, `Subscribe`, `Unsubscribe`, `IsSubscribed`, `ListFollowing`, `ListFollowers`, `ListUniversities`, `ListFaculties`, `ListPrograms`, `BanUser`, `GrantAdmin`, `CreateImprovementSuggestion`, `ListImprovementSuggestions`, `DeleteImprovementSuggestion`, `CreateUniversityProposal`, `ListUniversityProposals`, `ReviewUniversityProposal`, `CreateFacultyProposal`, `ListFacultyProposals`, `ReviewFacultyProposal`, `CreateProgramProposal`, `ListProgramProposals`, `ReviewProgramProposal` |
| `APIGateway` | `PostService` | `CreatePost`, `GetPostById`, `GetPostsByUser`, `GetPostsByIds`, `EditPost`, `DeletePost`, `LikePost`, `UnlikePost`, `AddComment`, `GetComments`, `EditComment`, `DeleteComment`, `LikeComment`, `UnlikeComment` |
| `APIGateway` | `FeedService` | `GetFeed` (`TRENDING`, `FOLLOWING`) |
| `APIGateway` | `MediaService` | `UploadFile` |
| `APIGateway` | `NotificationService` | `GetNotifications`, `MarkAllRead`, `GetUnreadCount` |
| `PostService` | `UserService` | `GetUserByUsername` для резолвинга `@username` в постах и комментариях |

### Kafka-взаимодействия

| Producer | Topic | Consumer | Назначение |
|---|---|---|---|
| `UserService` | `user-events` | `FeedService` | Подписки, отписки, удаление аккаунта, бессрочная блокировка |
| `UserService` | `user-events` | `NotificationService` | Подписки, выдача прав администратора, блокировки, очистка уведомлений |
| `UserService` | `user-events` | `PostService` | Очистка контента при удалении аккаунта и бессрочной блокировке |
| `PostService` | `post-events` | `FeedService` | Создание/удаление постов, пересчёт рейтинга при лайках |
| `PostService` | `post-events` | `NotificationService` | Лайки, комментарии, ответы, упоминания |
| `UserService` | `user-events.dlq` | — | Ошибки публикации `user-events` |
| `PostService` | `post-events.dlq` | — | Ошибки публикации `post-events` |
| `FeedService` | `feed-events.dlq` | — | Ошибки обработки событий ленты |
| `NotificationService` | `notification-events.dlq` | — | Ошибки обработки событий уведомлений |

Kafka-события сохраняются в outbox-таблицах сервисов-источников и публикуются асинхронным publisher'ом. `FeedService` дедуплицирует обработанные события в Redis, `NotificationService` дедуплицирует Kafka-события в таблице `processed_events`.

### Сервисы и RPC

### UserService

`CreateOrGetUser`, `GetUserById`, `GetUserByUsername`, `UpdateUser`, `DeleteAccount`, `CreateSession`, `RefreshSession`, `RevokeRefreshToken`, `SendVerificationCode`, `VerifyEmailCode`, `Subscribe`, `Unsubscribe`, `IsSubscribed`, `ListFollowing`, `ListFollowers`, `ValidateTopicForUniversity`, `GetGeneralTopicForUniversity`, `ResolvePostTarget`, `ListUniversities`, `ListFaculties`, `ListPrograms`, `BanUser`, `GrantAdmin`, `CreateImprovementSuggestion`, `ListImprovementSuggestions`, `DeleteImprovementSuggestion`, `CreateUniversityProposal`, `ListUniversityProposals`, `ReviewUniversityProposal`, `CreateFacultyProposal`, `ListFacultyProposals`, `ReviewFacultyProposal`, `CreateProgramProposal`, `ListProgramProposals`, `ReviewProgramProposal`

### PostService

`CreatePost`, `GetPostById`, `GetPostsByUser`, `GetPostsByIds`, `EditPost`, `DeletePost`, `LikePost`, `UnlikePost`, `AddComment`, `GetComments`, `EditComment`, `DeleteComment`, `LikeComment`, `UnlikeComment`

### FeedService

`GetFeed`

### MediaService

`UploadFile`, `DeleteFile`, `GeneratePresignedUploadUrl`

### NotificationService

`GetNotifications`, `MarkAllRead`, `GetUnreadCount`

---

## 15. Docker / local окружение

`docker-compose.yml` поднимает все сервисы и инфраструктуру.

### Порты

| Сервис | HTTP | gRPC |
|---|---|---|
| API Gateway | 8081 | — |
| Frontend | 5173 | — |
| Eureka | 8761 | — |
| UserService | 9004 | 9090 |
| PostService | 9005 | 9091 |
| FeedService | 9002 | 9092 |
| MediaService | 9003 | 9093 |
| NotificationService | 9007 | 9095 |
| MinIO API | 9000 | — |
| MinIO Console | 9001 | — |
| Kafka external | 9094 | — |
| Kafka UI | 8085 | — |
| User DB (PostgreSQL) | 5432 | — |
| Post DB (PostgreSQL) | 5433 | — |
| Notification DB (PostgreSQL) | 5435 | — |
| Redis | 6379 | — |
| Prometheus | 9700 | — |
| Grafana | 3000 | — |

### Volumes

`postgres_user_data`, `postgres_post_data`, `postgres_notification_data`, `redis_data`, `kafka_data`, `minio_data`, `prometheus_data`, `grafana_data`

---

## 16. Мониторинг и метрики

Сервисы приложения предоставляют actuator endpoints:

- `GET /actuator/health` — статус сервиса (show-details: always).
- `GET /actuator/prometheus` — метрики в формате Prometheus scrape.

В `APIGateway` служебные `/actuator/*` endpoints проходят через gateway-auth без пользовательской сессии, чтобы Docker healthcheck и Prometheus scrape не зависели от `ACCESS_TOKEN`.

Prometheus собирает метрики каждые 15 секунд из внутренних docker-compose endpoints:

| Prometheus job | Endpoint |
|---|---|
| `api-gateway` | `api-gateway:8080/actuator/prometheus` |
| `user-service` | `user-service:9000/actuator/prometheus` |
| `post-service` | `post-service:9001/actuator/prometheus` |
| `feed-service` | `feed-service:9002/actuator/prometheus` |
| `media-service` | `media-service:9003/actuator/prometheus` |
| `notification-service` | `notification-service:9006/actuator/prometheus` |

### Стандартные метрики Spring Boot

Prometheus собирает стандартные Micrometer/Spring Boot метрики сервисов:

- **Доступность targets:** `up`, `scrape_duration_seconds`, `scrape_samples_scraped`, `scrape_samples_post_metric_relabeling`.
- **JVM:** `jvm_memory_used_bytes`, `jvm_memory_committed_bytes`, `jvm_memory_max_bytes`, `jvm_gc_pause_seconds`, `jvm_threads_*`, `jvm_classes_loaded_classes`, `jvm_classes_unloaded_classes`.
- **HTTP:** `http_server_requests_seconds_*` с лейблами `uri`, `method`, `status`, `exception`.
- **gRPC:** `grpc_server_calls_*` для серверных gRPC-вызовов.
- **Kafka:** `kafka_producer_*`, `kafka_consumer_*`, `spring_kafka_listener_*` для producers, consumers и listener-контейнеров.
- **Redis:** `spring_data_redis_*` и Redis client metrics для FeedService.
- **PostgreSQL/HikariCP:** `hikaricp_*`, `jdbc_connections_*` для сервисов с базой данных.
- **Процесс и система:** `process_cpu_usage`, `process_uptime_seconds`, `process_start_time_seconds`, `system_cpu_usage`, `system_load_average_1m`, `disk_free_bytes`, `disk_total_bytes`.

### Кастомные метрики платформы

Платформенные метрики отражают текущее состояние доменных данных. Значения читаются из актуального состояния сервисных хранилищ при Prometheus scrape.

**UserService:**

- `platform_users` — количество зарегистрированных пользователей.
- `platform_users_student_verified` — количество пользователей со студенческой верификацией.
- `platform_users_employee_verified` — количество пользователей с верификацией сотрудника.
- `platform_users_admin` — количество администраторов.
- `platform_users_with_university` — количество пользователей с привязанным ВУЗом.
- `platform_users_active_bans` — количество активных временных блокировок.
- `platform_subscriptions` — количество активных подписок.
- `platform_universities` — количество ВУЗов.
- `platform_faculties` — количество факультетов.
- `platform_programs` — количество образовательных программ.

**PostService:**

- `platform_posts` — количество постов.
- `platform_posts_last_24h` — количество постов за последние 24 часа.
- `platform_posts_with_university` — количество постов с привязкой к ВУЗу.
- `platform_post_likes` — количество записей лайков постов.
- `platform_post_like_count` — суммарное денормализованное количество лайков постов.
- `platform_comments` — количество комментариев.
- `platform_comments_last_24h` — количество комментариев за последние 24 часа.
- `platform_comment_replies` — количество ответов на комментарии.
- `platform_comment_likes` — количество записей лайков комментариев.
- `platform_comment_like_count` — суммарное денормализованное количество лайков комментариев.

**NotificationService:**

- `platform_notifications` — количество сохранённых уведомлений.
- `platform_notifications_unread` — количество непрочитанных уведомлений.
- `platform_notification_processed_events` — количество обработанных Kafka-event IDs для дедупликации уведомлений.

### Инфраструктура мониторинга

- **Prometheus** (`localhost:9700`) — хранит time-series метрики сервисов. Конфиг: `Monitoring/prometheus.yml`.
- **Grafana** (`localhost:3000`) — визуализация метрик. Credentials берутся из env `GRAFANA_USER`/`GRAFANA_PASSWORD` (default: `admin/admin`). Prometheus datasource подключён через provisioning: `Monitoring/grafana/provisioning/datasources/`.
- **UniMeow Spring Overview** — dashboard технического состояния сервисов: availability, latency, HTTP/gRPC, JVM, Kafka, Redis, PostgreSQL/HikariCP, process/system metrics.
- **UniMeow Platform Metrics** — dashboard продуктовых показателей: пользователи, верификация, подписки, ВУЗы/факультеты/программы, посты, комментарии, лайки, уведомления.

---

## 17. Логирование

Все сервисы используют SLF4J + Logback (стандартный Spring Boot). `@Slf4j` (Lombok) расставлен на сервисах.

| Сервис | Событие | Уровень |
|---|---|---|
| UserService | Создание нового пользователя | INFO |
| UserService | Обновление профиля | INFO |
| UserService | Подписка / отписка | INFO |
| UserService | Выдача прав администратора | WARN |
| UserService | Бан пользователя (moderatorId, targetId, until, reason) | WARN |
| PostService | Создание поста (postId, authorId) | INFO |
| PostService | Удаление поста (postId, requesterId, adminOverride) | INFO |
| CommentService | Создание комментария (commentId, postId, authorId) | INFO |
| CommentService | Удаление комментария (commentId, requesterId, adminOverride) | INFO |
| MediaService | Загрузка файла (bucket, filename) | INFO |
| MediaService | Удаление файла (bucket, filename) | INFO |
| MediaService | Создание MinIO bucket | INFO |
| FeedEventService | Игнорирование неизвестного типа события | DEBUG |
| AdministrationService | Создание предложения улучшения (authorId) | INFO |
| AdministrationService | Подача заявки ВУЗа (name, authorId) | INFO |
| AdministrationService | Решение по заявке ВУЗа (proposalId, status, reviewerId) | INFO |

---

## 18. Сборка и качество кода

**Сборщик:** Gradle 9.1.0, multi-project build, Kotlin DSL.

### Подпроекты

`:gRPC`, `:APIGateway`, `:UserService`, `:PostService`, `:Eureka`, `:FeedService`, `:MediaService`, `:NotificationService`

### Инструменты качества кода

| Инструмент | Версия | Назначение |
|---|---|---|
| **Spotless** | 8.2.1 | Автоформатирование кода (Eclipse Java formatter, trailing whitespace, newline at EOF) |
| **PMD** | 7.19.0 | Статический анализ: best practices, error-prone паттерны, дизайн |
| **SpotBugs** | 6.5.1 (плагин) / 4.9.8 (lib) | Анализ байткода на типичные баги; threshold: MEDIUM confidence |

**Конфиги:**
- `config/pmd/ruleset.xml` — набор PMD правил с исключениями (GuardLogStatement, AvoidCatchingGenericException, CyclomaticComplexity и др.).
- `config/spotbugs/exclude.xml` — исключения SpotBugs (EI_EXPOSE_REP, EI_EXPOSE_REP2).

**Линтеры:**

```bash
./gradlew spotlessApply          # применить форматирование
./gradlew spotlessCheck          # проверить форматирование
./gradlew pmdMain                # запустить PMD
./gradlew spotbugsMain           # запустить SpotBugs
```

### Тесты

Фреймворк: JUnit 5 (JUnit Platform). Моки: Mockito + `mockito-junit-jupiter`.

Дополнительно в APIGateway: `spring-security-test`, `reactor-test`.

---

## 19. Миграции базы данных

### UserService (V1–V9)

| Версия | Содержимое |
|---|---|
| V1 | `universities` (+ subdomain), `university_domains`, `university_faculties`, `university_programs`, `users` |
| V2 | `refresh_sessions` |
| V3 | `email_verification_codes` |
| V4 | `subscriptions` |
| V5 | `outbox_events` |
| V6 | `improvement_suggestions`, `university_proposals` |
| V7 | `faculty_proposals` |
| V8 | `program_proposals` |
| V9 | `idempotency_keys` |

### PostService (V1–V6)

| Версия | Содержимое |
|---|---|
| V1 | `posts` (university_id, faculty_id, program_id, topic_id, parent_topic_id) |
| V2 | `post_likes` |
| V3 | `comments` (+ parent_comment_id) |
| V4 | `comment_likes` |
| V5 | `outbox_events` |
| V6 | `idempotency_keys` |

### NotificationService (V1–V2)

| Версия | Содержимое |
|---|---|
| V1 | `notifications` (user_id, actor_id, type, entity_id, entity_type, parent_entity_id, is_read, created_at) |
| V2 | `processed_events` (event_id, processed_at) |

---

## 20. Известные проблемы и ограничения

### Курсорная пагинация trending feed

Cursor — это `score = createdAtMs + likesCount * trendingLikeBoostMs`. При совпадающих score у двух постов один из них может быть пропущен на границе страниц — известное ограничение, не критичное при типичном размере страницы 20.
