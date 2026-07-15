# DanceDeck Local Backend

Локальный backend для разработки Android-приложения. Он работает без внешних зависимостей: Python + SQLite.

## Запуск

```bash
python3 backend/server.py
```

По умолчанию сервер стартует на:

```text
http://127.0.0.1:3000
```

Для Android Emulator нужно использовать:

```text
http://10.0.2.2:3000
```

Android-приложение уже настроено на этот адрес по умолчанию.

Для реального телефона телефон и Mac должны быть в одной Wi-Fi сети, а в приложении нужно указать IP Mac:

```text
http://<IP_Mac>:3000
```

## Переменные окружения

```bash
DANCEDECK_HOST=0.0.0.0
DANCEDECK_PORT=3000
DANCEDECK_DB=backend/dancedeck.db
DANCEDECK_MEDIA_DIR=backend/media
DANCEDECK_SECRET=change-this-secret
DANCEDECK_VOCAL_PROVIDER=demucs
DANCEDECK_DEMUCS_COMMAND=backend/.venv-demucs/bin/demucs
DANCEDECK_SMTP_HOST=smtp.gmail.com
DANCEDECK_SMTP_PORT=587
DANCEDECK_SMTP_USERNAME=your-email@gmail.com
DANCEDECK_SMTP_PASSWORD=your-app-password
DANCEDECK_SMTP_FROM=your-email@gmail.com
DANCEDECK_SMTP_TLS=true
```

Для разработки можно оставить значения по умолчанию. Для реального сервера `DANCEDECK_SECRET` нужно обязательно заменить.

## Основные endpoints

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
- `POST /playlists/{id}/songs/{song_id}/move`
- `GET /settings`
- `PATCH /settings`

## Email-код через SMTP

Если SMTP-переменные заданы, backend отправляет код на почту.

Для Gmail нужно создать App Password в настройках Google Account и использовать его как `DANCEDECK_SMTP_PASSWORD`. Обычный пароль от Gmail использовать не нужно.

Пример запуска:

```bash
DANCEDECK_SMTP_HOST=smtp.gmail.com \
DANCEDECK_SMTP_PORT=587 \
DANCEDECK_SMTP_USERNAME=your-email@gmail.com \
DANCEDECK_SMTP_PASSWORD=your-app-password \
DANCEDECK_SMTP_FROM=your-email@gmail.com \
python3 backend/server.py
```

Если SMTP не настроен или отправка упала, backend остается в dev-режиме: код печатается в консоль, чтобы можно было продолжать тестировать приложение:

```text
[email-code:dev] user@example.com -> 123456
```

## Rate limit для кода

Backend защищает отправку и проверку кода:

- отправка кода: 3 раза на email за 10 минут;
- отправка кода: 20 раз с одного IP за 1 час;
- проверка кода: 5 попыток на email за 10 минут;
- при превышении лимита блокировка на 30 минут.

При превышении backend возвращает HTTP `429`.

## Убрать вокал

Вырезать вокал на Android через `MediaPlayer` нельзя. Backend добавляет очередь задач и хранит обработанные файлы.

Endpoints:

- `POST /audio/vocal-removal/jobs`
- `GET /audio/vocal-removal/jobs/{job_id}`
- `GET /media/audio_jobs/{job_id}/instrumental.mp3`

Backend поддерживает два режима:

- `DANCEDECK_VOCAL_PROVIDER=ffmpeg` - быстрый fallback через center-channel cancellation;
- `DANCEDECK_VOCAL_PROVIDER=demucs` - AI-разделение источников, качество выше.

Установка:

```bash
brew install ffmpeg
/opt/homebrew/bin/python3.11 -m venv backend/.venv-demucs
backend/.venv-demucs/bin/pip install --upgrade pip demucs
```

Запуск с Demucs:

```bash
DANCEDECK_VOCAL_PROVIDER=demucs \
DANCEDECK_DEMUCS_COMMAND=backend/.venv-demucs/bin/demucs \
python3 backend/server.py
```

Demucs тяжелее и обрабатывает трек дольше, зато результат заметно ближе к настоящей instrumental-версии.
