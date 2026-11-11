# spine-backend
Backend API for Spine, built with Spring Boot and JDK 21, using PostgreSQL for data management.

## Requirements

- Java 21
- Docker (for the local database and for tests)

You do not need to install Maven. Use the Maven wrapper (`./mvnw`, or `mvnw.cmd` on Windows).

## Run locally

```bash
./mvnw spring-boot:run
```

This starts the Postgres container from `compose.yaml` for you and runs the app on http://localhost:8080.

You can also start the database yourself with `docker compose up -d`.

`spring-boot:run` uses the `local` profile, so Swagger UI is at http://localhost:8080/swagger-ui/index.html (it is always off in `prod`).

Check that it works:

```bash
curl http://localhost:8080/actuator/health
```

## Run tests

```bash
./mvnw verify
```

Tests start a temporary Postgres with Testcontainers, so Docker must be running.

## Build the Docker image

```bash
docker build -t spine-backend .
```

The image uses the `prod` profile by default, so you must pass the database and CORS variables:

```bash
docker run -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host:5432/spine \
  -e SPRING_DATASOURCE_USERNAME=... \
  -e SPRING_DATASOURCE_PASSWORD=... \
  -e APP_CORS_ALLOWED_ORIGINS=https://your-frontend.example.com \
  spine-backend
```

## Environment variables

See `.env.example`. Never commit real secrets.

| Variable | Description | Local default |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | Empty for local, `prod` in production | (empty) |
| `SPRING_DATASOURCE_URL` | JDBC URL of the database | `jdbc:postgresql://localhost:5432/spine` |
| `SPRING_DATASOURCE_USERNAME` | Database user | `spine` |
| `SPRING_DATASOURCE_PASSWORD` | Database password | `spine` |
| `SERVER_PORT` | HTTP port | `8080` |
| `APP_CORS_ALLOWED_ORIGINS` | Allowed origins for `/api/**`, comma-separated. Patterns like `https://*.vercel.app` work. | `http://localhost:3000` |
| `APP_FRONTEND_URL` | Frontend address used in email links | `http://localhost:3000` |
| `APP_MAIL_FROM` | Email sender, e.g. `Spine <no-reply@nordencapital.ca>` | `Spine <no-reply@localhost>` |
| `RESEND_API_KEY` | Resend API key. Empty = emails are printed to the console | (empty) |
| `APP_BOOTSTRAP_ADMIN_EMAIL` | First admin, invited on startup when there are no users | `admin@nordencapital.ca` |

In the `prod` profile there are no defaults: the app will not start if any of these variables is missing (and `RESEND_API_KEY` must not be empty).

## Auth

- No sign-up. An admin invites people by email; they set their own password from the link.
- The first admin is created on startup when the database has no users (`APP_BOOTSTRAP_ADMIN_EMAIL`).
  Locally the invite link is printed in the console.
- Login creates a server-side session (stored in Postgres, `SESSION` cookie, 8 hours idle).
- CSRF is on: call `GET /api/auth/csrf` once, then send the `XSRF-TOKEN` cookie value in the
  `X-XSRF-TOKEN` header on every POST/PATCH/DELETE.
- Full API list: Swagger UI (local only).

## Health checks

- `/actuator/health` - overall status
- `/actuator/health/liveness` - liveness probe
- `/actuator/health/readiness` - readiness probe
- `/actuator/info` - app info

No other actuator endpoints are exposed.

## Database migrations

Flyway runs migrations from `src/main/resources/db/migration` on startup.
Add new files as `V2__description.sql`, `V3__...`, and so on. Never edit a migration that has already run.
