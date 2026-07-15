# MusicApp / DanceDeck

Android-приложение для работы с музыкой и тренировочными треками. Проект включает мобильное приложение на Kotlin + Jetpack Compose и локальный backend на Python + SQLite.

## Возможности

- регистрация и вход пользователя;
- авторизация через email/password и email-код;
- библиотека треков пользователя;
- поиск и добавление музыки;
- работа с плейлистами;
- управление настройками воспроизведения;
- локальное воспроизведение аудио;
- загрузка и хранение пользовательских треков;
- backend-задачи для удаления вокала из аудио;
- поддержка FFmpeg fallback и Demucs для более качественного разделения вокала и инструментала.

## Стек

### Android

- Kotlin
- Android SDK
- Jetpack Compose
- Material 3
- Gradle Kotlin DSL
- MediaPlayer
- REST API integration

### Backend

- Python 3
- SQLite
- ThreadingHTTPServer
- REST API
- PBKDF2 password hashing
- HMAC token signing
- email-коды для подтверждения
- rate limiting для auth endpoints
- FFmpeg / Demucs для обработки аудио

## Структура проекта

```text
.
├── app/                    # Android-приложение
│   └── src/main/java/...   # Kotlin-код приложения
├── backend/                # Локальный Python backend
│   ├── server.py           # REST API и бизнес-логика
│   ├── README.md           # Инструкция по backend
│   └── .env.example        # Пример переменных окружения
├── gradle/                 # Gradle wrapper metadata
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew
```

## Backend API

Основные endpoints:

- `GET /health`
- `POST /auth/register`
- `POST /auth/login`
- `POST /auth/send-code`
- `POST /auth/verify-code`
- `GET /me`
- `GET /library/songs`
- `POST /library/songs`
- `PATCH /library/songs/{id}`
- `DELETE /library/songs/{id}`
- `GET /playlists`
- `POST /playlists`
- `PATCH /playlists/{id}`
- `DELETE /playlists/{id}`
- `GET /playlists/{id}/songs`
- `POST /playlists/{id}/songs`
- `DELETE /playlists/{id}/songs/{song_id}`
- `POST /audio/vocal-removal/jobs`
- `GET /audio/vocal-removal/jobs/{job_id}`

## Запуск Android-приложения

1. Открыть проект в Android Studio.
2. Дождаться Gradle Sync.
3. Запустить приложение на emulator или реальном устройстве.

Для Android Emulator backend должен быть доступен по адресу:

```text
http://10.0.2.2:3000
```

## Запуск backend

Из корня проекта:

```bash
python3 backend/server.py
```

По умолчанию backend стартует на:

```text
http://127.0.0.1:3000
```

Для запуска на реальном телефоне нужно указать host:

```bash
DANCEDECK_HOST=0.0.0.0 python3 backend/server.py
```

## Переменные окружения

```bash
DANCEDECK_HOST=127.0.0.1
DANCEDECK_PORT=3000
DANCEDECK_DB=backend/dancedeck.db
DANCEDECK_MEDIA_DIR=backend/media
DANCEDECK_SECRET=change-this-secret
DANCEDECK_VOCAL_PROVIDER=ffmpeg
DANCEDECK_DEMUCS_COMMAND=backend/.venv-demucs/bin/demucs
DANCEDECK_SMTP_HOST=smtp.gmail.com
DANCEDECK_SMTP_PORT=587
DANCEDECK_SMTP_USERNAME=your-email@gmail.com
DANCEDECK_SMTP_PASSWORD=your-app-password
DANCEDECK_SMTP_FROM=your-email@gmail.com
DANCEDECK_SMTP_TLS=true
```

Для production-режима нужно заменить `DANCEDECK_SECRET` и не хранить реальные секреты в репозитории.

## Удаление вокала

Backend поддерживает два режима:

- `ffmpeg` — быстрый fallback через center-channel cancellation;
- `demucs` — более качественное разделение вокала и инструментала.

Пример запуска с Demucs:

```bash
DANCEDECK_VOCAL_PROVIDER=demucs \
DANCEDECK_DEMUCS_COMMAND=backend/.venv-demucs/bin/demucs \
python3 backend/server.py
```

## Что не хранится в Git

В репозиторий не добавляются:

- локальная база `backend/dancedeck.db`;
- медиафайлы `backend/media/`;
- виртуальное окружение `backend/.venv-demucs/`;
- Gradle/Android Studio кэши;
- `local.properties`;
- build-артефакты.

## Автор

Науменко Евгений  
GitHub: https://github.com/NAumenko12
