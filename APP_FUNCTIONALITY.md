# UniMeow: функционал приложения

Документ описывает фактический функционал приложения по текущему коду: GraphQL schema, gateway-контроллеры, gRPC-контракты, feed-сервис, тестовый frontend и docker-compose окружение.

---

## 1. Назначение

UniMeow — университетская социальная сеть с пользователями, университетской верификацией, постами, комментариями, лайками, подписками, административными заявками и несколькими типами ленты.

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
| `FeedService` | Redis-backed ленты: global, trending, following, scoped university/faculty/program feeds |
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

Пользователь входит через Google OAuth2:

1. Frontend открывает `/oauth2/authorization/google`.
2. После успешного OAuth gateway создает или находит пользователя в `UserService`.
3. Gateway выдает `ACCESS_TOKEN` и `REFRESH_TOKEN` в cookies.
4. `AuthenticationFilter` читает `ACCESS_TOKEN`, валидирует JWT и прокидывает `X-User-Id` / GraphQL context `userId`.

### Auth-операции

- `POST /api/auth/refresh` — обновляет access/refresh tokens по cookie `REFRESH_TOKEN`.
- `POST /api/auth/logout` — отзывает refresh token, очищает `ACCESS_TOKEN` и `REFRESH_TOKEN`.
- `POST /graphql` доступен без HTTP auth; конкретные resolver'ы сами проверяют авторизацию.

### Матрица доступа

**Публичные операции (без авторизации):**
`trendingFeed`, `getPost`, `getUser`, `getUserByUsername`, `getUserPosts`, `getComments`

**Требуют авторизации:**
`me`, `updateProfile`, `sendVerificationCode`, `verifyEmailCode`, `subscribe`, `unsubscribe`, `followingFeed`, `createPost`, `editPost`, `deletePost`, `likePost`, `unlikePost`, `addComment`, `editComment`, `deleteComment`, `likeComment`, `unlikeComment`, `createImprovementSuggestion`, `createUniversityProposal`, `createFacultyProposal`

**Требуют прав администратора:**
`adminImprovementSuggestions`, `adminUniversityProposals`, `adminFacultyProposals`, `adminProgramProposals`, `adminReviewUniversityProposal`, `adminReviewFacultyProposal`, `adminReviewProgramProposal`, `adminCreateFaculty`, `adminGrantAdmin`, `adminBanUser`, `adminDeletePost`, `adminDeleteComment`, `adminDeleteSuggestion`

---

## 4. Пользователи и профиль

### GraphQL queries

- `me: User` — текущий пользователь по auth cookie.
- `getUser(id: ID!): User` — профиль по UUID.
- `getUserByUsername(username: String!): User` — профиль по username.
- `listUniversities: [University!]!` — список университетов для onboarding/фильтров/профиля.
- `listFaculties(universityId: ID!): [Faculty!]!` — факультеты университета.
- `listPrograms(facultyId: ID!): [Program!]!` — образовательные программы факультета.

### GraphQL mutations

- `updateProfile(input: UpdateProfileInput!): User`
- `sendVerificationCode(universityEmail: String!): VerificationResult`
- `verifyEmailCode(code: String!): VerifyResult`
- `subscribe(targetUserId: ID!): SubscribeResult`
- `unsubscribe(targetUserId: ID!): SubscribeResult`
- `createProgramProposal(input: ProgramProposalInput!): AdminActionResult!`

### Поля профиля

**Базовые:** `id`, `emailGoogle`, `username`, `name`, `surname`, `patronymic`, `avatarUrl`, `status`, `bio`, `createdAt`

**Университетские:** `emailUniversity`, `university`, `faculty`, `program`, `course`, `educationLevel`, `graduationYear`

**Верификация:** `isStudentVerified`, `isEmployeeVerified`

**Обновляемые через `updateProfile`:** `username`, `name`, `surname`, `patronymic`, `status`, `avatarUrl`, `coverUrl`, `bio`, `facultyId`, `programId`, `course`, `educationLevel` (`BACHELOR`/`MASTER`/`PHD`/`SPECIALIST`), `graduationYear`

### Типы

**`University`:** `id`, `name`, `shortName`, `subdomain`, `iconUrl`

**`Faculty`:** `id`, `name`, `shortName`

**`Program`:** `id`, `facultyId`, `name`, `shortName`

### Email-верификация

1. Пользователь вводит университетский email и запрашивает код.
2. `UserService` отправляет код; пользователь вводит его — backend выставляет `isStudentVerified`/`isEmployeeVerified` согласно домену.
3. После подтверждения frontend предлагает выбрать факультет и программу; если нужной программы нет — пользователь отправляет заявку через `createProgramProposal`; завершением сохраняется `facultyId` + `programId` через `updateProfile`.

### Подписки

- `subscribe` создает подписку текущего пользователя на другого.
- `unsubscribe` удаляет подписку.
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

**`CreatePostInput`:** `content: String!`, `mediaUrls: [String!]`, `topicId: ID` (legacy/compatibility, игнорируется backend'ом)

**`EditPostInput`:** `content: String`, `updateMediaUrls: Boolean`, `mediaUrls: [String!]`

**`Post`:** `id`, `authorId`, `content`, `mediaUrls`, `likesCount`, `commentsCount`, `likedByMe`, `createdAt`, `updatedAt`, `universityId`, `topicId`

### Поведение

- Создание, редактирование, удаление и лайки требуют авторизации.
- `likedByMe` считается относительно текущего пользователя, если он авторизован.
- Посты создаются только через главную ленту; university ленты — read-only проекции.
- Backend определяет `universityId`/`facultyId`/`programId` из профиля автора; клиент ничего не указывает.
- Если у автора нет университета, создается глобальный пост без university scope.
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

---

## 7. Ленты

### GraphQL queries

- `trendingFeed(cursor: String, size: Int, universityId: ID, facultyId: ID, programId: ID): FeedPage`
- `followingFeed(cursor: String, size: Int, universityId: ID, facultyId: ID, programId: ID): FeedPage`

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

Score обновляется при каждом лайке/анлайке. Redis key: `feed:popular` (глобальная), `feed:uni:{id}:popular`, `feed:uni:{id}:topic:{id}:popular`. Окно: последние 5000 постов (`app.feed.trending-window-size`).

---

### `followingFeed` — вкладка «Подписки»

Персональная хронологическая лента из постов авторов, на которых подписан пользователь. Требует авторизации. Доступна только в главной ленте.

**Алгоритм:**
- Score = `occurredAt` в мс. Порядок: новые выше.
- При подписке: последние 100 постов автора backfill-ятся в ленту подписчика.
- При отписке: последние 500 постов автора удаляются из ленты подписчика.
- Новый пост автора добавляется всем его подписчикам.

Redis key: `feed:user:{userId}`. При фильтре по ВУЗу: временное пересечение `ZINTERSTORE(feed:user:{id}, feed:uni:{uniId})` с TTL 60 с.

---

### Скоупы

| Параметры запроса | Читается из Redis |
|---|---|
| нет universityId | `feed:popular` / `feed:user:{id}` |
| universityId | `feed:uni:{id}` / `feed:uni:{id}:popular` |
| universityId + facultyId | `feed:uni:{id}:topic:{facId}` / `...:popular` |
| universityId + programId | `feed:uni:{id}:subtopic:{progId}` / `...:popular` |

ВУЗ-скоупы read-only: inline-compose скрыт, вкладка «Подписки» не показывается.

### Пагинация

- `size` по умолчанию = 20 (`app.feed.default-page-size`), максимум 100 (`app.feed.max-page-size`).
- `cursor` = score последнего поста. Следующий запрос берёт всё ниже этого значения.
- Для `trendingFeed`: cursor = `createdAtMs + likesCount * boost`. При одинаковых score возможен пропуск одного поста — известное ограничение.

### Внутренняя механика

**PostService** публикует в Kafka топик `post-events` через outbox:

| Событие | Когда |
|---|---|
| `POST_CREATED` | Пост создан |
| `POST_DELETED` | Пост удалён |
| `POST_LIKED` | Лайк поставлен |
| `POST_UNLIKED` | Лайк снят |

**UserService** публикует в Kafka топик `user-events` через outbox:

| Событие | Когда |
|---|---|
| `USER_FOLLOWED` | Пользователь подписался |
| `USER_UNFOLLOWED` | Пользователь отписался |

**FeedService** (`FeedKafkaConsumer` → `FeedEventService`) слушает оба топика и обновляет Redis sorted sets:

- `POST_CREATED` — добавляет пост в global feed, university/faculty/program feeds, author feed, trending feed, и во все personal feeds подписчиков автора.
- `POST_DELETED` — удаляет пост из всех feeds.
- `POST_LIKED` / `POST_UNLIKED` — пересчитывает trending score поста.
- `USER_FOLLOWED` — backfill последних 100 постов автора в personal feed подписчика.
- `USER_UNFOLLOWED` — удаляет последние 500 постов автора из personal feed подписчика.

Дедупликация: каждый Kafka-event помечается в Redis на 7 дней (`feed:processed:event:{eventId}`).

Gateway получает список post IDs из `FeedService` по gRPC, затем заполняет посты через `PostService`.

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

---

## 9. Медиа

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

**Потребители:**

- `APIGateway` принимает `POST /api/upload?bucket=...`, передает файл в `MediaService`, возвращает публичный MinIO URL.
- Frontend сначала загружает файл через `/api/upload`, затем сохраняет URL в БД/профиле/посте.
- Presigned upload URL доступен на gRPC уровне, но не выведен отдельной GraphQL mutation.

---

## 10. Администрирование

`is_admin` выставляется только через `adminGrantAdmin` — исключительно пользователем с username `kazenomi` (суперадмин, зашит в `UserService.grantAdmin()`).

### Флоу получения прав

1. `kazenomi` входит через Google OAuth2, устанавливает username через `updateProfile`.
2. Другой пользователь входит и устанавливает свой username.
3. `kazenomi` вызывает `adminGrantAdmin(targetUserId: ID!)` → `is_admin = true` в БД.

### Действия администратора

| Mutation | Описание |
|---|---|
| `adminGrantAdmin(targetUserId: ID!)` | Выдать права администратора (только kazenomi) |
| `adminBanUser(input: BanUserInput!)` | Бан навсегда или до даты с причиной |
| `adminDeletePost(postId: ID!)` | Удалить любой пост |
| `adminDeleteComment(commentId: ID!)` | Удалить любой комментарий |
| `adminDeleteSuggestion(id: ID!)` | Удалить предложение улучшения |
| `adminReviewUniversityProposal(proposalId: ID!, status: String!)` | Одобрить/отклонить заявку ВУЗа |
| `adminReviewFacultyProposal(proposalId: ID!, status: String!)` | Одобрить/отклонить заявку факультета |
| `adminReviewProgramProposal(proposalId: ID!, status: String!)` | Одобрить/отклонить заявку программы |
| `adminCreateFaculty(input: CreateFacultyInput!)` | Создать факультет в существующем ВУЗе |

**Queries только для администратора:** `adminImprovementSuggestions`, `adminUniversityProposals`, `adminFacultyProposals`, `adminProgramProposals`

**Queries для любого авторизованного пользователя:** `createImprovementSuggestion`, `createUniversityProposal`, `createFacultyProposal`, `createProgramProposal`

Бан хранится в `users.banned_permanent`, `users.banned_until`, `users.ban_reason`. Gateway блокирует все мутации для забаненных пользователей. Деструктивные действия на фронтенде подтверждаются через кастомный modal.

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
- Добавляются администратором через `adminCreateFaculty` или через заявку пользователя.
- Backend проверяет уникальность `shortName` внутри университета.

**Программы:**
- Пользователь не создаёт программу напрямую — только через `createProgramProposal`.
- При `APPROVED` создаётся запись в `university_programs`.
- Если программа с таким `shortName` уже существует — возвращается существующая запись.

---

## 11. Frontend

Основная точка входа: `Frontend/index.html`.

**Сборка:** Vite + React 18. JSX не транспилируется в браузере через Babel Standalone.

**Demo/mock режим:** `Frontend/mock.js` перехватывает GraphQL-вызовы в `Frontend/api.js`. Доступны моковые университеты, факультеты, программы, пользователи, посты, комментарии, предложения улучшений, заявки на университеты и программы — для документации и скриншотов без реального backend.

**Функции:**

- Auth: Google login, refresh, logout, status bar.
- Профиль: загрузка `me`, обновление профиля, поиск по id/username, email-верификация, аватар/баннер.
- Лента: вкладки «Рекомендации» (`trendingFeed`), «Подписки» (`followingFeed`); фильтры по университету, факультету, программе; preview активного GraphQL query/variables.
- ВУЗ-контекст: read-only лента без compose и без вкладки «Подписки»; чипы факультетов и программ.
- Контекст «Вне университета»: посты без `universityId` (поступление, общие вопросы).
- Посты: создание, редактирование, удаление, лайк/анлайк, выбор поста из ленты для операций.
- Комментарии: получение, добавление, редактирование, удаление, лайк/анлайк.
- Подписки: подписаться/отписаться.
- Медиа: загрузка аватара (`user-avatars`), баннера (`user-banners`), иконки ВУЗа (`university-icons`, svg/png), медиа постов (`post-media`).
- Заявки: предложение улучшения, заявка ВУЗа (название, shortName, subdomain, student domain, employee domain, иконка), заявка факультета, заявка программы.
- Админка: просмотр предложений улучшения, заявок ВУЗов, факультетов, программ; одобрение/отклонение; создание факультета; деструктивные действия через modal.
- Дефолтный аватар: если у пользователя нет аватарки, frontend показывает `default_pic.png`.

---

## 12. GraphQL surface

### Queries

```graphql
me: User
getUser(id: ID!): User
getUserByUsername(username: String!): User
listUniversities: [University!]!
listFaculties(universityId: ID!): [Faculty!]!
listPrograms(facultyId: ID!): [Program!]!
adminImprovementSuggestions: [ImprovementSuggestion!]!
adminUniversityProposals: [UniversityProposal!]!
adminFacultyProposals: [FacultyProposal!]!
adminProgramProposals: [ProgramProposal!]!
getUserPosts(userId: ID!, page: Int, size: Int): PostPage!
getPost(id: ID!): Post
getComments(postId: ID!, page: Int, size: Int): CommentPage!
trendingFeed(cursor: String, size: Int, universityId: ID, facultyId: ID, programId: ID): FeedPage!
followingFeed(cursor: String, size: Int, universityId: ID, facultyId: ID, programId: ID): FeedPage!
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
createProgram(facultyId: ID!, name: String!, shortName: String!): Program!
adminGrantAdmin(targetUserId: ID!): AdminActionResult!
adminBanUser(input: BanUserInput!): AdminActionResult!
adminDeletePost(postId: ID!): DeleteResult!
adminDeleteComment(commentId: ID!): DeleteResult!
adminDeleteSuggestion(id: ID!): DeleteResult!
adminReviewUniversityProposal(proposalId: ID!, status: String!): AdminActionResult!
adminReviewFacultyProposal(proposalId: ID!, status: String!): AdminActionResult!
adminReviewProgramProposal(proposalId: ID!, status: String!): AdminActionResult!
adminCreateFaculty(input: CreateFacultyInput!): AdminActionResult!
```

---

## 13. gRPC surface

### UserService

`CreateOrGetUser`, `GetUserById`, `GetUserByUsername`, `UpdateUser`, `CreateSession`, `RefreshSession`, `RevokeRefreshToken`, `SendVerificationCode`, `VerifyEmailCode`, `Subscribe`, `Unsubscribe`, `ListUniversities`, `ListFaculties`, `ListTopics`, `CreateImprovementSuggestion`, `ListImprovementSuggestions`, `DeleteImprovementSuggestion`, `ListPrograms`, `CreateProgramForUser`, `CreateUniversityProposal`, `ListUniversityProposals`, `ReviewUniversityProposal`, `CreateFaculty`, `GrantAdmin`, `BanUser`, `UploadUniversityIcon`, `ValidateTopicForUniversity`, `GetGeneralTopicForUniversity`, `ResolvePostTarget`

### PostService

`CreatePost` (клиентский `topicId` игнорируется), `GetPostById`, `GetPostsByUser`, `GetPostsByIds`, `EditPost`, `DeletePost`, `LikePost`, `UnlikePost`, `AddComment`, `GetComments`, `EditComment`, `DeleteComment`, `LikeComment`, `UnlikeComment`

### FeedService

`GetFeed`

### MediaService

`UploadFile`, `DeleteFile`, `GeneratePresignedUploadUrl`

---

## 14. Docker / local окружение

`docker-compose.yml` поднимает все сервисы и инфраструктуру.

### Порты

| Сервис | HTTP | gRPC |
|---|---|---|
| API Gateway | 8080 | — |
| Frontend | 5173 | — |
| Eureka | 8761 | — |
| UserService | 9004 | 9090 |
| PostService | 9005 | 9091 |
| FeedService | 9002 | 9092 |
| MediaService | 9003 | 9093 |
| MinIO API | 9000 | — |
| MinIO Console | 9001 | — |
| Kafka external | 9094 | — |
| Kafka UI | 8085 | — |
| User DB (PostgreSQL) | 5432 | — |
| Post DB (PostgreSQL) | 5433 | — |
| Redis | 6379 | — |
| Prometheus | 9700 | — |
| Grafana | 3000 | — |

### Volumes

`postgres_user_data`, `postgres_post_data`, `redis_data`, `kafka_data`, `minio_data`, `prometheus_data`, `grafana_data`

---

## 15. Мониторинг и метрики

Каждый сервис (APIGateway, UserService, PostService, FeedService, MediaService) предоставляет:

- `GET /actuator/health` — статус сервиса (show-details: always).
- `GET /actuator/prometheus` — метрики в формате Prometheus scrape.

### Стандартные метрики Spring Boot

- **JVM:** `jvm_memory_used_bytes`, `jvm_gc_pause_seconds`, `jvm_threads_*`, `jvm_classes_loaded`
- **HTTP:** `http_server_requests_seconds` (count/sum/max по uri, method, status) — APIGateway
- **gRPC:** `grpc_server_calls_*`
- **Kafka:** `kafka_producer_record_send_total`, `kafka_consumer_records_consumed_total`
- **Redis:** `spring_data_redis_*` — FeedService
- **DB:** `hikari_connections_*`, `jdbc_connections_*` — UserService, PostService
- **System:** `process_cpu_usage`, `system_cpu_usage`, `process_uptime_seconds`

### Кастомные метрики платформы

- `platform_users_total` — общее количество зарегистрированных пользователей (UserService).
- `platform_posts_total` — общее количество постов на платформе (PostService).

### Инфраструктура мониторинга

- **Prometheus** (`localhost:9700`) — scrape каждые 15s, retention 15 дней. Конфиг: `monitoring/prometheus.yml`.
- **Grafana** (`localhost:3000`) — credentials из env `GRAFANA_USER`/`GRAFANA_PASSWORD` (default: `admin/admin`). Prometheus datasource подключён через provisioning: `monitoring/grafana/provisioning/datasources/`.

---

## 16. Логирование

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
| CommentService | Добавление комментария (commentId, postId, authorId) | INFO |
| CommentService | Удаление комментария (commentId, requesterId, adminOverride) | INFO |
| MediaService | Загрузка файла (bucket, filename) | INFO |
| MediaService | Удаление файла (bucket, filename) | INFO |
| MediaService | Создание MinIO bucket | INFO |
| FeedEventService | Игнорирование неизвестного типа события | DEBUG |
| AdministrationService | Создание предложения улучшения (authorId) | INFO |
| AdministrationService | Подача заявки ВУЗа (name, authorId) | INFO |
| AdministrationService | Решение по заявке ВУЗа (proposalId, status, reviewerId) | INFO |

---

## 17. Сборка и качество кода

**Сборщик:** Gradle 9.1.0, multi-project build, Kotlin DSL.

### Подпроекты

`:gRPC`, `:APIGateway`, `:UserService`, `:PostService`, `:Eureka`, `:FeedService`, `:MediaService`

### Инструменты качества кода

| Инструмент | Версия | Назначение |
|---|---|---|
| **Spotless** | 8.2.1 | Автоформатирование кода (Eclipse Java formatter, trailing whitespace, newline at EOF) |
| **PMD** | 7.19.0 | Статический анализ: best practices, error-prone паттерны, дизайн |
| **SpotBugs** | 6.5.1 (плагин) / 4.9.8 (lib) | Анализ байткода на типичные баги; threshold: MEDIUM confidence |

**Конфиги:**
- `config/pmd/ruleset.xml` — набор PMD правил с исключениями (GuardLogStatement, AvoidCatchingGenericException, CyclomaticComplexity и др.).
- `config/spotbugs/exclude.xml` — исключения SpotBugs (EI_EXPOSE_REP, EI_EXPOSE_REP2).

**Полезные команды Gradle:**

```bash
./gradlew spotlessApply          # применить форматирование
./gradlew spotlessCheck          # проверить форматирование без изменений
./gradlew pmdMain                # запустить PMD
./gradlew spotbugsMain           # запустить SpotBugs
./gradlew build                  # полная сборка
```

### Тесты

Фреймворк: JUnit 5 (JUnit Platform). Моки: Mockito + `mockito-junit-jupiter`.

Дополнительно в APIGateway: `spring-security-test`, `reactor-test`.

---

## 18. Миграции базы данных

### UserService (V1–V11)

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

### PostService (V1–V5)

| Версия | Содержимое |
|---|---|
| V1 | `posts` (university_id, faculty_id, program_id, topic_id, parent_topic_id) |
| V2 | `post_likes` |
| V3 | `comments` (+ parent_comment_id) |
| V4 | `comment_likes` |
| V5 | `outbox_events` |

---
