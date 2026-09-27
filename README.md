# UniMeow

Университетская социальная сеть — место, где студенты и преподаватели общаются внутри своего университета и за его пределами.

**[unimeow.ru](https://unimeow.ru)**

---

## Что умеет UniMeow

- Вход через Google, верификация по университетскому email
- Посты с медиафайлами, лайки, вложенные комментарии
- Три типа ленты: рекомендации, подписки, лента университета
- Подписки на других пользователей
- Заявки на добавление университетов, факультетов и образовательных программ
- Административная панель для модерации

---

## Архитектура

![architecture](architecture.jpg)

## Стек

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

## Документация

Подробное описание функционала, GraphQL/gRPC API, алгоритмов лент, схемы БД и ограничений — в [APP_FUNCTIONALITY.md](APP_FUNCTIONALITY.md).

## Локальный запуск

Нужен Docker Compose. Для разработки используются `docker-compose.dev.yaml` и сохранённый в репозитории `.env.dev` с тестовыми значениями. Образы собираются по одному, чтобы несколько Gradle-процессов не занимали память одновременно. Команды ниже — для Bash, в том числе Git Bash на Windows:

```sh
for service in eureka-server media-service user-service post-service feed-service notification-service api-gateway frontend; do
  docker compose -f docker-compose.dev.yaml --env-file .env.dev build "$service" || exit 1
done
for service in eureka-server media-service user-service post-service feed-service notification-service api-gateway frontend proxy prometheus grafana; do
  docker compose -f docker-compose.dev.yaml --env-file .env.dev up -d --wait "$service" || exit 1
done
```

Frontend: http://localhost:5173, API: http://localhost:8081. Для настоящего Google OAuth передайте свои `GOOGLE_CLIENT_ID` и `GOOGLE_CLIENT_SECRET` через окружение процесса.

На сервере используются `docker-compose.prod.yaml` и приватный `.env.prod`. Production Compose запускает готовые образы из GHCR; секций сборки в нём нет. Публичные адреса frontend читает при запуске контейнера, поэтому смена адреса не требует пересборки образа.

GitHub собирает образы без production-секретов: настройки подключения нужны приложениям при запуске, а не при сборке. Во время Release Ansible вызывает серверный Compose с `-f docker-compose.prod.yaml --env-file .env.prod`, и тот передаёт настройки контейнерам. `.env.prod` не загружается в GitHub и не включается в образы; следующий релиз сохраняет его значения, меняя только `VERSION`.

MinIO закреплён как `ghcr.io/coollabsio/minio:RELEASE.2025-10-15T17-29-55Z`: это готовая [community-сборка из исходников MinIO](https://github.com/coollabsio/minio). Прежний образ `minio/minio` недоступен для скачивания; новый тег проверен настоящим `docker pull`.

## Сборка и выпуск

**Build** собирает восемь образов приложения, запускает Java-тесты и проверку восстановления обработки событий на настоящем Redis. После успеха публикует образы в GHCR и небольшой архив `unimeow.tar.gz` в GitHub Releases. Архив содержит `docker-compose.prod.yaml`, SQL-миграции и конфигурацию Prometheus. Для PR выполняются только сборка и тесты. Версии имеют вид `v1.0.1`; при ручном запуске Build можно указать версию, иначе используется `v1.0.<номер запуска>`. Опубликованную версию нельзя перезаписать.

Чтобы установить выпуск: **GitHub → Actions → Release → Run workflow → version**. Публикация сборки сама по себе сервер не обновляет.

Release запускает [Ansible](deploy/release.yml). Это готовый инструмент, который подключается по SSH и выполняет перечисленные в YAML шаги: скачивает образы, останавливает приложения, проверяет бэкап PostgreSQL, Redis и MinIO, запускает Flyway и последовательно поднимает сервисы через `docker-compose.prod.yaml`. Образы на сервере не собираются. Для первоначальной настройки нужны environment `production`, secrets `SSH_PRIVATE_KEY`, `SSH_KNOWN_HOSTS` и variables `SSH_HOST`, `SSH_USER`, `SSH_PORT`.

## Возврат версии и бэкап

В том же Action **Release** можно выбрать ранее опубликованную версию. Возвращается код; данные остаются текущими. Старый код должен быть совместим с текущей схемой БД. Другой вариант — сделать `git revert`, пройти Build и выпустить новую версию с отменёнными изменениями.

Перед каждым выпуском работающей установки Ansible останавливает приложения и сохраняет три PostgreSQL-базы, Redis и MinIO. Redis и MinIO ненадолго останавливаются для полной копии их данных. Восстановление проверяется в отдельных временных контейнерах. Только после успешной проверки новый `/var/backups/unimeow/latest.tar.gz` заменяет предыдущий архив. Если установка нездорова, Release сохраняет последний проверенный бэкап. При ошибке выпуска Ansible пытается запустить предыдущие приложения; данные автоматически не восстанавливаются.

PostgreSQL сохранён в формате `pg_dump`, Redis — вместе с RDB/AOF, MinIO — вместе с файлами и служебными данными. Восстановление выполняется вручную: `pg_restore` для PostgreSQL, полная замена данных остановленных Redis/MinIO из копии. Проверять восстановление сначала следует на отдельных volumes. Kafka в архив не входит: после возврата к старым данным её очередь и позиции чтения проверяются отдельно. Архив хранится на том же сервере и не защищает от потери всего диска.
