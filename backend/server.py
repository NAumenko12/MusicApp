#!/usr/bin/env python3
import base64
import hashlib
import hmac
import json
import os
import secrets
import shutil
import smtplib
import sqlite3
import subprocess
import threading
import time
import urllib.request
from email.message import EmailMessage
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from queue import Queue
from urllib.parse import urlparse


ROOT_DIR = Path(__file__).resolve().parent
DB_PATH = Path(os.getenv("DANCEDECK_DB", ROOT_DIR / "dancedeck.db"))
MEDIA_DIR = Path(os.getenv("DANCEDECK_MEDIA_DIR", ROOT_DIR / "media"))
HOST = os.getenv("DANCEDECK_HOST", "127.0.0.1")
PORT = int(os.getenv("DANCEDECK_PORT", "3000"))
SECRET = os.getenv("DANCEDECK_SECRET", "dev-secret-change-me")
VOCAL_PROVIDER = os.getenv("DANCEDECK_VOCAL_PROVIDER", "ffmpeg").lower()
DEMUCS_COMMAND = os.getenv("DANCEDECK_DEMUCS_COMMAND", str(ROOT_DIR / ".venv-demucs" / "bin" / "demucs"))
SMTP_HOST = os.getenv("DANCEDECK_SMTP_HOST", "")
SMTP_PORT = int(os.getenv("DANCEDECK_SMTP_PORT", "587"))
SMTP_USERNAME = os.getenv("DANCEDECK_SMTP_USERNAME", "")
SMTP_PASSWORD = os.getenv("DANCEDECK_SMTP_PASSWORD", "")
SMTP_FROM = os.getenv("DANCEDECK_SMTP_FROM", SMTP_USERNAME)
SMTP_TLS = os.getenv("DANCEDECK_SMTP_TLS", "true").lower() != "false"
TOKEN_TTL_SECONDS = 60 * 60 * 24 * 30
CODE_TTL_SECONDS = 60 * 10
CODE_SEND_LIMIT = 3
CODE_SEND_WINDOW_SECONDS = 60 * 10
CODE_VERIFY_LIMIT = 5
CODE_VERIFY_WINDOW_SECONDS = 60 * 10
CODE_BLOCK_SECONDS = 60 * 30
IP_CODE_SEND_LIMIT = 20
IP_CODE_SEND_WINDOW_SECONDS = 60 * 60
AUDIO_JOB_QUEUE: Queue[str] = Queue()


def now() -> int:
    return int(time.time())


def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def init_db() -> None:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    with db() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                email TEXT NOT NULL UNIQUE,
                phone TEXT NOT NULL DEFAULT '',
                password_hash TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS login_codes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                email TEXT NOT NULL,
                code_hash TEXT NOT NULL,
                expires_at INTEGER NOT NULL,
                used INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS songs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL DEFAULT '',
                duration_seconds INTEGER NOT NULL DEFAULT 30,
                genre TEXT NOT NULL DEFAULT '',
                cover_url TEXT NOT NULL DEFAULT '',
                preview_url TEXT NOT NULL DEFAULT '',
                local_path TEXT NOT NULL DEFAULT '',
                favorite INTEGER NOT NULL DEFAULT 0,
                downloaded INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
                UNIQUE(user_id, title, artist)
            );

            CREATE TABLE IF NOT EXISTS playlists (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                cover_url TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS playlist_songs (
                playlist_id INTEGER NOT NULL,
                song_id INTEGER NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                PRIMARY KEY(playlist_id, song_id),
                FOREIGN KEY(playlist_id) REFERENCES playlists(id) ON DELETE CASCADE,
                FOREIGN KEY(song_id) REFERENCES songs(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS user_settings (
                user_id INTEGER PRIMARY KEY,
                volume REAL NOT NULL DEFAULT 0.75,
                speed REAL NOT NULL DEFAULT 1.0,
                bpm INTEGER NOT NULL DEFAULT 120,
                vocal_cut INTEGER NOT NULL DEFAULT 0,
                voice_count INTEGER NOT NULL DEFAULT 0,
                beat_overlay INTEGER NOT NULL DEFAULT 0,
                repeat_mode TEXT NOT NULL DEFAULT 'off',
                shuffle INTEGER NOT NULL DEFAULT 0,
                language TEXT NOT NULL DEFAULT 'ru',
                FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS auth_rate_limits (
                rate_key TEXT NOT NULL,
                action TEXT NOT NULL,
                window_start INTEGER NOT NULL,
                count INTEGER NOT NULL DEFAULT 0,
                blocked_until INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(rate_key, action)
            );

            CREATE TABLE IF NOT EXISTS audio_jobs (
                id TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL,
                song_id INTEGER,
                source_url TEXT NOT NULL,
                status TEXT NOT NULL,
                result_path TEXT NOT NULL DEFAULT '',
                error TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
                FOREIGN KEY(song_id) REFERENCES songs(id) ON DELETE SET NULL
            );
            """
        )


def b64url_encode(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def b64url_decode(value: str) -> bytes:
    padding = "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode(value + padding)


def json_dumps(payload: dict) -> bytes:
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def password_hash(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 120_000)
    return f"pbkdf2_sha256${b64url_encode(salt)}${b64url_encode(digest)}"


def verify_password(password: str, stored: str) -> bool:
    try:
        algo, salt64, digest64 = stored.split("$", 2)
        if algo != "pbkdf2_sha256":
            return False
        salt = b64url_decode(salt64)
        expected = b64url_decode(digest64)
        actual = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 120_000)
        return hmac.compare_digest(actual, expected)
    except Exception:
        return False


def code_hash(email: str, code: str) -> str:
    message = f"{email.lower()}:{code}".encode("utf-8")
    return hmac.new(SECRET.encode("utf-8"), message, hashlib.sha256).hexdigest()


def issue_token(user_id: int) -> str:
    payload = {"sub": user_id, "exp": now() + TOKEN_TTL_SECONDS}
    body = b64url_encode(json_dumps(payload))
    sig = hmac.new(SECRET.encode("utf-8"), body.encode("ascii"), hashlib.sha256).digest()
    return f"{body}.{b64url_encode(sig)}"


def verify_token(token: str) -> int | None:
    try:
        body, sig64 = token.split(".", 1)
        expected = hmac.new(SECRET.encode("utf-8"), body.encode("ascii"), hashlib.sha256).digest()
        if not hmac.compare_digest(expected, b64url_decode(sig64)):
            return None
        payload = json.loads(b64url_decode(body).decode("utf-8"))
        if int(payload["exp"]) < now():
            return None
        return int(payload["sub"])
    except Exception:
        return None


def normalize_email(email: str) -> str:
    return email.strip().lower()


def smtp_configured() -> bool:
    return bool(SMTP_HOST and SMTP_USERNAME and SMTP_PASSWORD and SMTP_FROM)


def send_login_code(email: str, code: str) -> bool:
    if not smtp_configured():
        print(f"[email-code:dev] {email} -> {code}")
        return False

    message = EmailMessage()
    message["Subject"] = "DanceDeck login code"
    message["From"] = SMTP_FROM
    message["To"] = email
    message.set_content(
        "\n".join(
            [
                "DanceDeck Music",
                "",
                f"Your login code: {code}",
                "",
                "The code is valid for 10 minutes.",
                "If you did not request this code, ignore this email.",
            ]
        )
    )

    with smtplib.SMTP(SMTP_HOST, SMTP_PORT, timeout=15) as smtp:
        if SMTP_TLS:
            smtp.starttls()
        smtp.login(SMTP_USERNAME, SMTP_PASSWORD)
        smtp.send_message(message)
    return True


def row_to_user(row: sqlite3.Row) -> dict:
    return {
        "id": row["id"],
        "name": row["name"],
        "email": row["email"],
        "phone": row["phone"],
        "createdAt": row["created_at"],
    }


def row_to_song(row: sqlite3.Row) -> dict:
    return {
        "id": row["id"],
        "title": row["title"],
        "artist": row["artist"],
        "album": row["album"],
        "durationSeconds": row["duration_seconds"],
        "genre": row["genre"],
        "coverUrl": row["cover_url"],
        "previewUrl": row["preview_url"],
        "localPath": row["local_path"],
        "favorite": bool(row["favorite"]),
        "downloaded": bool(row["downloaded"]),
        "createdAt": row["created_at"],
    }


def row_to_playlist(row: sqlite3.Row) -> dict:
    return {
        "id": row["id"],
        "name": row["name"],
        "coverUrl": row["cover_url"],
        "songCount": row["song_count"] if "song_count" in row.keys() else 0,
        "createdAt": row["created_at"],
    }


def row_to_settings(row: sqlite3.Row) -> dict:
    return {
        "volume": row["volume"],
        "speed": row["speed"],
        "bpm": row["bpm"],
        "vocalCut": bool(row["vocal_cut"]),
        "voiceCount": bool(row["voice_count"]),
        "beatOverlay": bool(row["beat_overlay"]),
        "repeatMode": row["repeat_mode"],
        "shuffle": bool(row["shuffle"]),
        "language": row["language"],
    }


def row_to_audio_job(row: sqlite3.Row) -> dict:
    result_url = ""
    if row["result_path"]:
        result_url = f"/media/audio_jobs/{row['id']}/instrumental.mp3"
    return {
        "id": row["id"],
        "songId": row["song_id"],
        "sourceUrl": row["source_url"],
        "status": row["status"],
        "resultUrl": result_url,
        "error": row["error"],
        "createdAt": row["created_at"],
        "updatedAt": row["updated_at"],
    }


class ApiError(Exception):
    def __init__(self, status: int, message: str):
        self.status = status
        self.message = message


def check_rate_limit(rate_key: str, action: str, limit: int, window_seconds: int, block_seconds: int) -> None:
    current = now()
    with db() as conn:
        row = conn.execute(
            """
            SELECT * FROM auth_rate_limits
            WHERE rate_key = ? AND action = ?
            """,
            (rate_key, action),
        ).fetchone()
        if row is not None and row["blocked_until"] > current:
            retry_after = row["blocked_until"] - current
            raise ApiError(429, f"Too many attempts. Try again in {retry_after} seconds")

        if row is None or row["window_start"] + window_seconds <= current:
            conn.execute(
                """
                INSERT OR REPLACE INTO auth_rate_limits(rate_key, action, window_start, count, blocked_until)
                VALUES(?, ?, ?, ?, 0)
                """,
                (rate_key, action, current, 1),
            )
            return

        new_count = row["count"] + 1
        blocked_until = current + block_seconds if new_count > limit else 0
        conn.execute(
            """
            UPDATE auth_rate_limits
            SET count = ?, blocked_until = ?
            WHERE rate_key = ? AND action = ?
            """,
            (new_count, blocked_until, rate_key, action),
        )
        if blocked_until:
            raise ApiError(429, f"Too many attempts. Try again in {block_seconds} seconds")


def reset_rate_limit(rate_key: str, action: str) -> None:
    with db() as conn:
        conn.execute(
            "DELETE FROM auth_rate_limits WHERE rate_key = ? AND action = ?",
            (rate_key, action),
        )


def update_audio_job(job_id: str, status: str, result_path: str = "", error: str = "") -> None:
    with db() as conn:
        conn.execute(
            """
            UPDATE audio_jobs
            SET status = ?, result_path = COALESCE(NULLIF(?, ''), result_path), error = ?, updated_at = ?
            WHERE id = ?
            """,
            (status, result_path, error, now(), job_id),
        )


def process_audio_job(job_id: str) -> None:
    with db() as conn:
        row = conn.execute("SELECT * FROM audio_jobs WHERE id = ?", (job_id,)).fetchone()
    if row is None:
        return

    job_dir = MEDIA_DIR / "audio_jobs" / job_id
    job_dir.mkdir(parents=True, exist_ok=True)
    input_path = job_dir / "input.audio"
    output_path = job_dir / "instrumental.mp3"

    try:
        update_audio_job(job_id, "processing")
        request = urllib.request.Request(
            row["source_url"],
            headers={"User-Agent": "DanceDeckBackend/0.1"},
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            input_path.write_bytes(response.read())

        if VOCAL_PROVIDER == "demucs":
            run_demucs_vocal_removal(input_path, output_path, job_dir)
        else:
            run_ffmpeg_vocal_reduction(input_path, output_path)
        update_audio_job(job_id, "done", result_path=str(output_path))
    except subprocess.CalledProcessError as exc:
        update_audio_job(job_id, "failed", error=(exc.stderr or "ffmpeg failed")[-1000:])
    except Exception as exc:
        update_audio_job(job_id, "failed", error=str(exc))


def run_ffmpeg_vocal_reduction(input_path: Path, output_path: Path) -> None:
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        raise RuntimeError("ffmpeg is not installed")
    # Fallback method: center-channel cancellation. It reduces centered vocals, but is not AI separation.
    subprocess.run(
        [
            ffmpeg,
            "-y",
            "-i",
            str(input_path),
            "-af",
            "pan=stereo|c0=c0-c1|c1=c1-c0,volume=1.8",
            "-codec:a",
            "libmp3lame",
            "-b:a",
            "192k",
            str(output_path),
        ],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        text=True,
    )


def run_demucs_vocal_removal(input_path: Path, output_path: Path, job_dir: Path) -> None:
    demucs = DEMUCS_COMMAND if Path(DEMUCS_COMMAND).exists() else shutil.which("demucs")
    if demucs is None:
        raise RuntimeError("demucs is not installed")
    separated_dir = job_dir / "separated"
    subprocess.run(
        [
            demucs,
            "--two-stems",
            "vocals",
            "--mp3",
            "--mp3-bitrate",
            "192",
            "-o",
            str(separated_dir),
            str(input_path),
        ],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        text=True,
    )
    candidates = list(separated_dir.rglob("no_vocals.mp3"))
    if not candidates:
        raise RuntimeError("demucs did not produce no_vocals.mp3")
    shutil.copyfile(candidates[0], output_path)


def audio_worker() -> None:
    while True:
        job_id = AUDIO_JOB_QUEUE.get()
        try:
            process_audio_job(job_id)
        finally:
            AUDIO_JOB_QUEUE.task_done()


def start_audio_workers() -> None:
    thread = threading.Thread(target=audio_worker, daemon=True, name="audio-worker")
    thread.start()


class Handler(BaseHTTPRequestHandler):
    server_version = "DanceDeckBackend/0.1"

    def log_message(self, fmt: str, *args) -> None:
        print(f"[api] {self.address_string()} {fmt % args}")

    def send_json(self, status: int, payload: dict) -> None:
        raw = json_dumps(payload)
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS")
        self.end_headers()
        self.wfile.write(raw)

    def send_file(self, path: Path, content_type: str = "audio/mpeg") -> None:
        if not path.exists() or not path.is_file():
            raise ApiError(404, "File not found")
        raw = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(raw)

    def read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", "0"))
        if length == 0:
            return {}
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except json.JSONDecodeError:
            raise ApiError(400, "Invalid JSON")

    def path_parts(self) -> list[str]:
        return [part for part in urlparse(self.path).path.split("/") if part]

    def current_user(self) -> sqlite3.Row:
        header = self.headers.get("Authorization", "")
        if not header.startswith("Bearer "):
            raise ApiError(401, "Missing token")
        user_id = verify_token(header.removeprefix("Bearer ").strip())
        if user_id is None:
            raise ApiError(401, "Invalid token")
        with db() as conn:
            row = conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
            if row is None:
                raise ApiError(401, "User not found")
            return row

    def do_OPTIONS(self) -> None:
        self.send_json(204, {})

    def do_GET(self) -> None:
        self.handle_request("GET")

    def do_POST(self) -> None:
        self.handle_request("POST")

    def do_PATCH(self) -> None:
        self.handle_request("PATCH")

    def do_DELETE(self) -> None:
        self.handle_request("DELETE")

    def handle_request(self, method: str) -> None:
        try:
            parts = self.path_parts()
            if method == "GET" and parts == ["health"]:
                self.send_json(200, {"ok": True})
            elif parts[:1] == ["auth"]:
                self.handle_auth(method, parts[1:])
            elif parts == ["me"] and method == "GET":
                self.send_json(200, {"user": row_to_user(self.current_user())})
            elif parts[:1] == ["library"]:
                self.handle_library(method, parts[1:])
            elif parts[:1] == ["playlists"]:
                self.handle_playlists(method, parts[1:])
            elif parts[:1] == ["settings"]:
                self.handle_settings(method, parts[1:])
            elif parts[:1] == ["audio"]:
                self.handle_audio(method, parts[1:])
            elif parts[:1] == ["media"]:
                self.handle_media(method, parts[1:])
            else:
                raise ApiError(404, "Route not found")
        except ApiError as exc:
            self.send_json(exc.status, {"ok": False, "error": exc.message})
        except Exception as exc:
            print(f"[api:error] {exc}")
            self.send_json(500, {"ok": False, "error": "Internal server error"})

    def handle_auth(self, method: str, parts: list[str]) -> None:
        data = self.read_json()
        if method == "POST" and parts == ["register"]:
            name = str(data.get("name", "")).strip()
            email = normalize_email(str(data.get("email", "")))
            phone = str(data.get("phone", "")).strip()
            password = str(data.get("password", ""))
            if not name or not email or not password:
                raise ApiError(400, "Name, email and password are required")
            if len(password) < 6:
                raise ApiError(400, "Password must contain at least 6 characters")
            with db() as conn:
                try:
                    cur = conn.execute(
                        """
                        INSERT INTO users(name, email, phone, password_hash, created_at)
                        VALUES(?, ?, ?, ?, ?)
                        """,
                        (name, email, phone, password_hash(password), now()),
                    )
                except sqlite3.IntegrityError:
                    raise ApiError(409, "User already exists")
                user_id = cur.lastrowid
                conn.execute("INSERT INTO user_settings(user_id) VALUES(?)", (user_id,))
                user = conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
            self.send_json(201, {"ok": True, "user": row_to_user(user)})
            return

        if method == "POST" and parts == ["login"]:
            email = normalize_email(str(data.get("email", "")))
            password = str(data.get("password", ""))
            with db() as conn:
                user = conn.execute("SELECT * FROM users WHERE email = ?", (email,)).fetchone()
            if user is None or not verify_password(password, user["password_hash"]):
                raise ApiError(401, "Invalid email or password")
            self.check_code_send_limits(email)
            delivered = self.create_email_code(email)
            self.send_json(200, {"ok": True, "requiresCode": True, "codeDelivered": delivered, "user": row_to_user(user)})
            return

        if method == "POST" and parts == ["send-code"]:
            email = normalize_email(str(data.get("email", "")))
            if not email:
                raise ApiError(400, "Email is required")
            self.check_code_send_limits(email)
            delivered = self.create_email_code(email)
            self.send_json(
                200,
                {
                    "ok": True,
                    "codeDelivered": delivered,
                    "message": "Code was sent by email" if delivered else "Code was printed in backend console",
                },
            )
            return

        if method == "POST" and parts == ["verify-code"]:
            email = normalize_email(str(data.get("email", "")))
            code = str(data.get("code", "")).strip()
            if len(code) != 6 or not code.isdigit():
                raise ApiError(400, "Code must contain 6 digits")
            check_rate_limit(f"email:{email}", "verify-code", CODE_VERIFY_LIMIT, CODE_VERIFY_WINDOW_SECONDS, CODE_BLOCK_SECONDS)
            with db() as conn:
                code_row = conn.execute(
                    """
                    SELECT * FROM login_codes
                    WHERE email = ? AND used = 0 AND expires_at >= ?
                    ORDER BY id DESC
                    LIMIT 1
                    """,
                    (email, now()),
                ).fetchone()
                if code_row is None or code_row["code_hash"] != code_hash(email, code):
                    raise ApiError(401, "Invalid or expired code")
                user = conn.execute("SELECT * FROM users WHERE email = ?", (email,)).fetchone()
                if user is None:
                    raise ApiError(404, "User not found")
                conn.execute("UPDATE login_codes SET used = 1 WHERE id = ?", (code_row["id"],))
            reset_rate_limit(f"email:{email}", "verify-code")
            self.send_json(200, {"ok": True, "token": issue_token(user["id"]), "user": row_to_user(user)})
            return

        raise ApiError(404, "Auth route not found")

    def check_code_send_limits(self, email: str) -> None:
        ip = self.client_address[0]
        check_rate_limit(f"email:{email}", "send-code", CODE_SEND_LIMIT, CODE_SEND_WINDOW_SECONDS, CODE_BLOCK_SECONDS)
        check_rate_limit(f"ip:{ip}", "send-code", IP_CODE_SEND_LIMIT, IP_CODE_SEND_WINDOW_SECONDS, CODE_BLOCK_SECONDS)

    def create_email_code(self, email: str) -> bool:
        code = f"{secrets.randbelow(1_000_000):06d}"
        with db() as conn:
            conn.execute(
                """
                INSERT INTO login_codes(email, code_hash, expires_at, created_at)
                VALUES(?, ?, ?, ?)
                """,
                (email, code_hash(email, code), now() + CODE_TTL_SECONDS, now()),
            )
        try:
            return send_login_code(email, code)
        except Exception as exc:
            print(f"[email-code:error] {email}: {exc}")
            print(f"[email-code:dev] {email} -> {code}")
            return False

    def handle_library(self, method: str, parts: list[str]) -> None:
        user = self.current_user()
        if method == "GET" and parts == ["songs"]:
            with db() as conn:
                rows = conn.execute(
                    "SELECT * FROM songs WHERE user_id = ? ORDER BY created_at DESC, id DESC",
                    (user["id"],),
                ).fetchall()
            self.send_json(200, {"ok": True, "songs": [row_to_song(row) for row in rows]})
            return

        if method == "POST" and parts == ["songs"]:
            data = self.read_json()
            title = str(data.get("title", "")).strip()
            artist = str(data.get("artist", "")).strip()
            if not title or not artist:
                raise ApiError(400, "Title and artist are required")
            with db() as conn:
                try:
                    cur = conn.execute(
                        """
                        INSERT INTO songs(
                            user_id, title, artist, album, duration_seconds, genre, cover_url,
                            preview_url, local_path, favorite, downloaded, created_at
                        )
                        VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        (
                            user["id"],
                            title,
                            artist,
                            str(data.get("album", "")).strip(),
                            int(data.get("durationSeconds", 30)),
                            str(data.get("genre", "")).strip(),
                            str(data.get("coverUrl", "")).strip(),
                            str(data.get("previewUrl", "")).strip(),
                            str(data.get("localPath", "")).strip(),
                            1 if data.get("favorite", False) else 0,
                            1 if data.get("downloaded", False) else 0,
                            now(),
                        ),
                    )
                except sqlite3.IntegrityError:
                    raise ApiError(409, "Song already exists")
                row = conn.execute("SELECT * FROM songs WHERE id = ?", (cur.lastrowid,)).fetchone()
            self.send_json(201, {"ok": True, "song": row_to_song(row)})
            return

        if len(parts) == 2 and parts[0] == "songs" and method in {"PATCH", "DELETE"}:
            song_id = int(parts[1])
            if method == "DELETE":
                with db() as conn:
                    cur = conn.execute("DELETE FROM songs WHERE id = ? AND user_id = ?", (song_id, user["id"]))
                if cur.rowcount == 0:
                    raise ApiError(404, "Song not found")
                self.send_json(200, {"ok": True})
                return

            data = self.read_json()
            updates = []
            params = []
            field_map = {
                "title": "title",
                "artist": "artist",
                "album": "album",
                "durationSeconds": "duration_seconds",
                "genre": "genre",
                "coverUrl": "cover_url",
                "previewUrl": "preview_url",
                "localPath": "local_path",
                "favorite": "favorite",
                "downloaded": "downloaded",
            }
            for api_name, column in field_map.items():
                if api_name in data:
                    updates.append(f"{column} = ?")
                    value = data[api_name]
                    if api_name in {"favorite", "downloaded"}:
                        value = 1 if value else 0
                    params.append(value)
            if not updates:
                raise ApiError(400, "No fields to update")
            params.extend([song_id, user["id"]])
            with db() as conn:
                cur = conn.execute(
                    f"UPDATE songs SET {', '.join(updates)} WHERE id = ? AND user_id = ?",
                    params,
                )
                if cur.rowcount == 0:
                    raise ApiError(404, "Song not found")
                row = conn.execute("SELECT * FROM songs WHERE id = ?", (song_id,)).fetchone()
            self.send_json(200, {"ok": True, "song": row_to_song(row)})
            return

        raise ApiError(404, "Library route not found")

    def handle_playlists(self, method: str, parts: list[str]) -> None:
        user = self.current_user()
        if method == "GET" and parts == []:
            with db() as conn:
                rows = conn.execute(
                    """
                    SELECT playlists.*, COUNT(playlist_songs.song_id) AS song_count
                    FROM playlists
                    LEFT JOIN playlist_songs ON playlist_songs.playlist_id = playlists.id
                    WHERE playlists.user_id = ?
                    GROUP BY playlists.id
                    ORDER BY playlists.created_at DESC, playlists.id DESC
                    """,
                    (user["id"],),
                ).fetchall()
            self.send_json(200, {"ok": True, "playlists": [row_to_playlist(row) for row in rows]})
            return

        if method == "POST" and parts == []:
            data = self.read_json()
            name = str(data.get("name", "")).strip()
            if not name:
                raise ApiError(400, "Playlist name is required")
            with db() as conn:
                cur = conn.execute(
                    "INSERT INTO playlists(user_id, name, cover_url, created_at) VALUES(?, ?, ?, ?)",
                    (user["id"], name, str(data.get("coverUrl", "")).strip(), now()),
                )
                row = conn.execute("SELECT * FROM playlists WHERE id = ?", (cur.lastrowid,)).fetchone()
            self.send_json(201, {"ok": True, "playlist": row_to_playlist(row)})
            return

        if len(parts) == 1 and method in {"PATCH", "DELETE"}:
            playlist_id = int(parts[0])
            if method == "DELETE":
                with db() as conn:
                    cur = conn.execute(
                        "DELETE FROM playlists WHERE id = ? AND user_id = ?",
                        (playlist_id, user["id"]),
                    )
                if cur.rowcount == 0:
                    raise ApiError(404, "Playlist not found")
                self.send_json(200, {"ok": True})
                return

            data = self.read_json()
            name = data.get("name")
            cover_url = data.get("coverUrl")
            updates = []
            params = []
            if name is not None:
                updates.append("name = ?")
                params.append(str(name).strip())
            if cover_url is not None:
                updates.append("cover_url = ?")
                params.append(str(cover_url).strip())
            if not updates:
                raise ApiError(400, "No fields to update")
            params.extend([playlist_id, user["id"]])
            with db() as conn:
                cur = conn.execute(
                    f"UPDATE playlists SET {', '.join(updates)} WHERE id = ? AND user_id = ?",
                    params,
                )
                if cur.rowcount == 0:
                    raise ApiError(404, "Playlist not found")
                row = conn.execute("SELECT * FROM playlists WHERE id = ?", (playlist_id,)).fetchone()
            self.send_json(200, {"ok": True, "playlist": row_to_playlist(row)})
            return

        if len(parts) >= 2 and parts[1] == "songs":
            playlist_id = int(parts[0])
            self.ensure_playlist_owner(playlist_id, user["id"])
            if method == "GET" and len(parts) == 2:
                with db() as conn:
                    rows = conn.execute(
                        """
                        SELECT songs.* FROM playlist_songs
                        JOIN songs ON songs.id = playlist_songs.song_id
                        WHERE playlist_songs.playlist_id = ? AND songs.user_id = ?
                        ORDER BY playlist_songs.sort_order ASC, playlist_songs.created_at ASC
                        """,
                        (playlist_id, user["id"]),
                    ).fetchall()
                self.send_json(200, {"ok": True, "songs": [row_to_song(row) for row in rows]})
                return

            if method == "POST" and len(parts) == 2:
                data = self.read_json()
                song_id = int(data.get("songId", 0))
                self.ensure_song_owner(song_id, user["id"])
                with db() as conn:
                    max_order = conn.execute(
                        "SELECT COALESCE(MAX(sort_order), -1) AS max_order FROM playlist_songs WHERE playlist_id = ?",
                        (playlist_id,),
                    ).fetchone()["max_order"]
                    conn.execute(
                        """
                        INSERT OR IGNORE INTO playlist_songs(playlist_id, song_id, sort_order, created_at)
                        VALUES(?, ?, ?, ?)
                        """,
                        (playlist_id, song_id, int(max_order) + 1, now()),
                    )
                self.send_json(200, {"ok": True})
                return

            if method == "DELETE" and len(parts) == 3:
                song_id = int(parts[2])
                with db() as conn:
                    conn.execute(
                        "DELETE FROM playlist_songs WHERE playlist_id = ? AND song_id = ?",
                        (playlist_id, song_id),
                    )
                self.send_json(200, {"ok": True})
                return

            if method == "POST" and len(parts) == 4 and parts[3] == "move":
                song_id = int(parts[2])
                sort_order = int(self.read_json().get("sortOrder", 0))
                with db() as conn:
                    cur = conn.execute(
                        """
                        UPDATE playlist_songs SET sort_order = ?
                        WHERE playlist_id = ? AND song_id = ?
                        """,
                        (sort_order, playlist_id, song_id),
                    )
                if cur.rowcount == 0:
                    raise ApiError(404, "Playlist song not found")
                self.send_json(200, {"ok": True})
                return

        raise ApiError(404, "Playlist route not found")

    def ensure_playlist_owner(self, playlist_id: int, user_id: int) -> None:
        with db() as conn:
            row = conn.execute(
                "SELECT id FROM playlists WHERE id = ? AND user_id = ?",
                (playlist_id, user_id),
            ).fetchone()
        if row is None:
            raise ApiError(404, "Playlist not found")

    def ensure_song_owner(self, song_id: int, user_id: int) -> None:
        with db() as conn:
            row = conn.execute(
                "SELECT id FROM songs WHERE id = ? AND user_id = ?",
                (song_id, user_id),
            ).fetchone()
        if row is None:
            raise ApiError(404, "Song not found")

    def handle_settings(self, method: str, parts: list[str]) -> None:
        user = self.current_user()
        if parts != []:
            raise ApiError(404, "Settings route not found")
        if method == "GET":
            with db() as conn:
                row = conn.execute("SELECT * FROM user_settings WHERE user_id = ?", (user["id"],)).fetchone()
                if row is None:
                    conn.execute("INSERT INTO user_settings(user_id) VALUES(?)", (user["id"],))
                    row = conn.execute("SELECT * FROM user_settings WHERE user_id = ?", (user["id"],)).fetchone()
            self.send_json(200, {"ok": True, "settings": row_to_settings(row)})
            return
        if method == "PATCH":
            data = self.read_json()
            field_map = {
                "volume": "volume",
                "speed": "speed",
                "bpm": "bpm",
                "vocalCut": "vocal_cut",
                "voiceCount": "voice_count",
                "beatOverlay": "beat_overlay",
                "repeatMode": "repeat_mode",
                "shuffle": "shuffle",
                "language": "language",
            }
            updates = []
            params = []
            for api_name, column in field_map.items():
                if api_name in data:
                    value = data[api_name]
                    if api_name in {"vocalCut", "voiceCount", "beatOverlay", "shuffle"}:
                        value = 1 if value else 0
                    updates.append(f"{column} = ?")
                    params.append(value)
            if not updates:
                raise ApiError(400, "No fields to update")
            params.append(user["id"])
            with db() as conn:
                conn.execute(
                    f"UPDATE user_settings SET {', '.join(updates)} WHERE user_id = ?",
                    params,
                )
                row = conn.execute("SELECT * FROM user_settings WHERE user_id = ?", (user["id"],)).fetchone()
            self.send_json(200, {"ok": True, "settings": row_to_settings(row)})
            return
        raise ApiError(405, "Method not allowed")

    def handle_audio(self, method: str, parts: list[str]) -> None:
        user = self.current_user()
        if parts == ["vocal-removal", "jobs"] and method == "POST":
            data = self.read_json()
            song_id = data.get("songId")
            source_url = str(data.get("sourceUrl", "")).strip()
            if song_id:
                with db() as conn:
                    song = conn.execute(
                        "SELECT * FROM songs WHERE id = ? AND user_id = ?",
                        (int(song_id), user["id"]),
                    ).fetchone()
                if song is None:
                    raise ApiError(404, "Song not found")
                source_url = source_url or song["preview_url"]
            if not source_url.startswith(("http://", "https://")):
                raise ApiError(400, "sourceUrl must be an http or https URL")

            job_id = secrets.token_urlsafe(12)
            with db() as conn:
                conn.execute(
                    """
                    INSERT INTO audio_jobs(id, user_id, song_id, source_url, status, created_at, updated_at)
                    VALUES(?, ?, ?, ?, 'queued', ?, ?)
                    """,
                    (job_id, user["id"], int(song_id) if song_id else None, source_url, now(), now()),
                )
                row = conn.execute("SELECT * FROM audio_jobs WHERE id = ?", (job_id,)).fetchone()
            AUDIO_JOB_QUEUE.put(job_id)
            self.send_json(202, {"ok": True, "job": row_to_audio_job(row)})
            return

        if len(parts) == 3 and parts[:2] == ["vocal-removal", "jobs"] and method == "GET":
            job_id = parts[2]
            with db() as conn:
                row = conn.execute(
                    "SELECT * FROM audio_jobs WHERE id = ? AND user_id = ?",
                    (job_id, user["id"]),
                ).fetchone()
            if row is None:
                raise ApiError(404, "Audio job not found")
            self.send_json(200, {"ok": True, "job": row_to_audio_job(row)})
            return

        raise ApiError(404, "Audio route not found")

    def handle_media(self, method: str, parts: list[str]) -> None:
        if method != "GET":
            raise ApiError(405, "Method not allowed")
        if len(parts) == 3 and parts[0] == "audio_jobs" and parts[2] == "instrumental.mp3":
            job_id = parts[1]
            with db() as conn:
                row = conn.execute("SELECT * FROM audio_jobs WHERE id = ?", (job_id,)).fetchone()
            if row is None or not row["result_path"]:
                raise ApiError(404, "Audio result not found")
            self.send_file(Path(row["result_path"]))
            return
        raise ApiError(404, "Media route not found")


def main() -> None:
    init_db()
    MEDIA_DIR.mkdir(parents=True, exist_ok=True)
    start_audio_workers()
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"DanceDeck backend started: http://{HOST}:{PORT}")
    print(f"SQLite database: {DB_PATH}")
    print("Android emulator base URL: http://10.0.2.2:3000")
    server.serve_forever()


if __name__ == "__main__":
    main()
