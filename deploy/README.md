# Deploy Spine API to the VPS

The VPS already runs another app with Caddy and Docker.
Spine runs next to it, alone:

- its own Docker Compose project `spine`, its own network `spine`
- its own Postgres; the database port is **not** open to the outside
- the API listens only on `127.0.0.1:8090`; Caddy sends traffic to it

Files here:

| File | What it is |
| --- | --- |
| `compose.prod.yaml` | Postgres + API containers |
| `.env.example` | Template for the server `.env` file |
| `Caddyfile.spine` | Caddy site block for the API |

After the one-time setup, every push to `main` deploys by itself:
CI passes → `.github/workflows/deploy.yml` builds the image, pushes it to
GHCR, copies `compose.prod.yaml` to the server, pulls, starts and waits
until the API is healthy. If the API is not healthy, the job fails.

## One-time server setup

Run these on the VPS as a user with `sudo`.

### 1. Check the port is free

```bash
sudo ss -ltnp | grep ':8090' || echo "8090 is free"
```

If 8090 is taken, pick another port and use it in `.env` and in the Caddy block.

### 2. Make a deploy user

```bash
sudo adduser --disabled-password --gecos "" deploy
sudo usermod -aG docker deploy
```

Note: being in the `docker` group is almost the same as root. Keep this key safe.

### 3. Give GitHub an SSH key

On your own computer (not the server):

```bash
ssh-keygen -t ed25519 -f spine_deploy -C "github-actions-spine" -N ""
```

On the server, add the **public** key (`spine_deploy.pub`):

```bash
sudo mkdir -p /home/deploy/.ssh
sudo nano /home/deploy/.ssh/authorized_keys   # paste spine_deploy.pub
sudo chown -R deploy:deploy /home/deploy/.ssh
sudo chmod 700 /home/deploy/.ssh
sudo chmod 600 /home/deploy/.ssh/authorized_keys
```

The **private** key (`spine_deploy`) goes into GitHub (step 6).

### 4. Make the app folder and `.env`

```bash
sudo mkdir -p /opt/spine
sudo chown deploy:deploy /opt/spine
sudo -u deploy nano /opt/spine/.env   # paste .env.example, fill in values
sudo chmod 600 /opt/spine/.env
```

Make the database password with `openssl rand -hex 32`.
`compose.prod.yaml` is copied to `/opt/spine` by every deploy.
For the first manual start you can copy it yourself.

### 5. Add the Caddy block

1. Open `Caddyfile.spine`. Change the host name and the port if needed.
   No domain yet? Use the VPS IP, e.g. `api.203-0-113-10.sslip.io`.
2. Put it on the server, for example `/etc/caddy/spine.caddy`.
3. At the end of the main `/etc/caddy/Caddyfile` add:
   ```
   import /etc/caddy/spine.caddy
   ```
4. Check and reload (this does not stop the other app):
   ```bash
   sudo caddy validate --config /etc/caddy/Caddyfile
   sudo systemctl reload caddy
   ```

If Caddy runs in Docker, not as a system service, read the note in
`Caddyfile.spine` first: `127.0.0.1` means something else there.

### 6. GitHub secrets

In GitHub: repo `Norden-Capital/spine-backend` → Settings → Secrets and
variables → Actions → New repository secret.

| Secret | Value | Needed |
| --- | --- | --- |
| `VPS_HOST` | VPS IP or host name | yes |
| `VPS_USER` | `deploy` | yes |
| `VPS_SSH_KEY` | the private key `spine_deploy` (whole file) | yes |
| `VPS_PORT` | SSH port, if not 22 | no |
| `VPS_HOST_FINGERPRINT` | SSH host key SHA256 fingerprint | no, but good |

Get the fingerprint on the server:

```bash
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```

Copy only the `SHA256:...` part.

The job also uses a GitHub environment named `production`. GitHub creates
it on the first run. Later you can add "required reviewers" to it if you
want to approve each deploy.

### 7. Image access (GHCR)

The deploy job logs the server in to GHCR with the job's own short-lived
token, pulls, and logs out again. So the image can stay **private** and
the server keeps no GHCR password.

Only for a **manual** pull on the server (outside GitHub Actions) you need
either a public package, or a login with a personal token that has
`read:packages`.

## First deploy

Push to `main` (or re-run the last CI run). Watch the "Deploy" workflow.

Check on the server:

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

## Needs a recent Docker Compose

The deploy uses `docker compose up --wait --wait-timeout`. Old Compose
versions do not have these flags. Check on the server:

```bash
docker compose up --help | grep -- --wait
```
