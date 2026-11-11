# Deploy Spine API to the VPS

The VPS (169.58.174.211, Ubuntu 24.04) also runs another app, OneBase.
**Never touch OneBase** (`/opt/onebase`, its containers, its Caddy).
Spine runs next to it, alone:

- its own Docker Compose project `spine`, its own network `spine`
- its own Postgres; the database port is **not** open to the outside
- the API is on port `8090`, plain HTTP, open to the internet (temporary)

## How traffic flows now (temporary)

```
phone browser → Vercel (HTTPS) → Vercel server proxies /api/* → http://169.58.174.211:8090
```

The browser only talks to Vercel. The OneBase Caddy owns ports 80/443, so we
can't put HTTPS in front of Spine on this server yet.
**Later:** a Cloudflare Tunnel + our own domain. Then set
`SPINE_API_BIND=127.0.0.1` in `.env` and close 8090 in ufw.

Note: ports published by Docker skip ufw. We still allow `8090/tcp` in ufw
so the rules show what is really open.

Files here:

| File | What it is |
| --- | --- |
| `compose.prod.yaml` | Postgres + API containers |
| `.env.example` | Template for the server `.env` file |

## Automatic deploy

Every push to `main`: CI passes → `.github/workflows/deploy.yml` builds the
image, pushes it to GHCR, copies `compose.prod.yaml` to `/opt/spine`, pulls,
starts and waits until the API is healthy. If not healthy, the job fails.

The server logs in to GHCR with the job's own short-lived token, pulls, and
logs out. So the image can stay **private** and the server keeps no GHCR
password. (For a manual pull you need a token with `read:packages`.)

## One-time server setup (already done)

Run as root on the VPS.

1. Check the port is free: `ss -ltnp | grep ':8090' || echo free`
2. App folder and env file:
   ```bash
   mkdir -p /opt/spine && chmod 700 /opt/spine
   # write /opt/spine/.env from .env.example; password: openssl rand -hex 32
   chmod 600 /opt/spine/.env
   ```
3. Firewall: `ufw allow 8090/tcp`
4. Deploy key for GitHub: make a separate ed25519 key, add its public key to
   `/root/.ssh/authorized_keys`, put the private key in the GitHub secret.

## GitHub secrets

Repo `Norden-Capital/spine-backend` → Settings → Secrets and variables → Actions.

| Secret | Value |
| --- | --- |
| `VPS_HOST` | `169.58.174.211` |
| `VPS_USER` | `root` |
| `VPS_PORT` | `22` |
| `VPS_SSH_KEY` | private deploy key (whole file) |
| `VPS_HOST_FINGERPRINT` | `SHA256:...` from `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` |

The job uses a GitHub environment named `production`. GitHub creates it on
the first run. You can add "required reviewers" to it later.

## Check it works

```bash
cd /opt/spine
docker compose -f compose.prod.yaml ps
curl -s http://127.0.0.1:8090/actuator/health/readiness
```

## Daily use

```bash
cd /opt/spine
docker compose -f compose.prod.yaml logs -f api      # logs
docker compose -f compose.prod.yaml restart api      # restart
```

Roll back to an older version (use its short git sha):

```bash
cd /opt/spine
IMAGE_TAG=abc1234 docker compose -f compose.prod.yaml up -d --wait
```

Images are tagged `latest` and the short git sha.
