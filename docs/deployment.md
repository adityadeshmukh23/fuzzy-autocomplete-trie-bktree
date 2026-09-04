# Deployment

The app deploys as **one container**: the React build is folded into the jar's static resources
and served by Spring from the same origin as the API.

Why one service and not two:

- Free tiers cap how many services you can run. One is cheaper and simpler to keep alive.
- Same-origin means **no production CORS configuration** that can drift out of sync with the
  frontend's deployed URL. (Development uses a Vite proxy, which is also same-origin — so a
  split-origin production deployment would be exercising a code path dev never touches.)
- A portfolio link should be one URL.

It costs nothing in code: Spring Boot serves `classpath:/static/**` automatically, and the
frontend uses tab state rather than a client-side router, so there is no SPA fallback route to add.

---

## What is already prepared

| File | Purpose |
|---|---|
| `Dockerfile` | Three-stage build: node builds the frontend → maven builds the jar with it inside → JRE runs it |
| `.dockerignore` | Keeps `target/`, `node_modules/` and `.git/` out of the build context |
| `render.yaml` | Render blueprint pointing at the Dockerfile |
| `pom.xml` profile `with-frontend` | Copies `frontend/dist` into the jar's static resources |

Nothing in the Dockerfile is Render-specific — the same image runs unchanged on Fly.io, Railway,
Koyeb, or any container host. That portability is the reason for containerising rather than using
a platform-native Java buildpack.

---

## Verify locally first

Build the artifact exactly as the deployment will:

```bash
cd frontend && npm ci && npm run build && cd ..
./mvnw -Pwith-frontend -DskipTests package
java -jar target/fuzzy-search-engine-*.jar
```

Open <http://localhost:8080> — the UI is served from the jar, no Vite involved. Confirm:

- the search box returns results and the comparison panel shows both engines
- the Benchmarks tab renders its charts
- `curl localhost:8080/health` reports the index size

Or with Docker, which is closer to what the host will run:

```bash
docker build -t fuzzy-autocomplete .
docker run --rm -p 8080:8080 -m 512m fuzzy-autocomplete
```

The `-m 512m` matters: it reproduces the free tier's memory limit, so an out-of-memory failure
shows up on your machine rather than in a deploy log.

---

## Deploy to Render

Render is the recommended host: 512 MB on the free tier, which fits the JVM plus the ~45 MB index
comfortably. (Fly.io's free allowance is 256 MB, which is tight for a JVM with this index.)

These steps need your account, so they are yours to run:

1. Push to GitHub if you have not already — done: `adityadeshmukh23/fuzzy-autocomplete-trie-bktree`.
2. Sign in at <https://render.com> and connect your GitHub account.
3. **New → Blueprint**, select the repository. Render reads `render.yaml` and configures the
   service itself — no manual build or start command to type.
4. Deploy. The first build takes roughly 5–10 minutes (npm install, maven dependency download,
   image build). Later builds are faster because the dependency layers cache.
5. The service appears at `https://<name>.onrender.com`.

If you would rather configure it by hand instead of via the blueprint:

| Setting | Value |
|---|---|
| Runtime | Docker |
| Dockerfile path | `./Dockerfile` |
| Health check path | `/health` |
| Environment variable | `JAVA_TOOL_OPTIONS` = `-XX:MaxRAMPercentage=70 -XX:+UseSerialGC` |

`PORT` is injected by Render and already read by `application.yml` via `${PORT:8080}`.

### Alternative: Fly.io

```bash
fly launch --dockerfile Dockerfile --no-deploy
fly deploy
```

Set the memory explicitly, because the 256 MB default is too small for this JVM:

```bash
fly scale memory 512
```

---

## Cold starts

Free tiers stop a service after inactivity and restart it on the next request. A visitor arriving
after a quiet spell waits for a container start plus a JVM boot — realistically 30–60 seconds.

The frontend handles this rather than pretending it does not happen: `useApiStatus` retries
`/api/health` with backoff for about 90 seconds and shows a "waking the server" banner explaining
the wait, instead of rendering "API unreachable" over a server that is merely asleep.

Roughly 2.3 s of that startup is ours — 171 ms to parse the dataset, 442 ms to build the trie and
BK-tree, and 525 ms of deliberate JIT warmup. The warmup is worth keeping: without it the first
comparison a visitor sees reports 12 ms instead of ~880 µs, because the JVM is still interpreting.

**A keep-alive cron pinging `/health` would hide the cold start**, but it burns free-tier hours
continuously and some providers treat it as abuse. Not set up here; the honest banner is the
better trade for a portfolio demo.

---

## After deploying

```bash
# 1. Health and index metadata
curl https://<your-app>.onrender.com/health

# 2. A real search
curl 'https://<your-app>.onrender.com/api/search?q=aple&limit=5'

# 3. The live comparison — identicalResults must be true
curl 'https://<your-app>.onrender.com/api/compare?q=recieve&limit=5'

# 4. Validation still rejects bad input
curl -i 'https://<your-app>.onrender.com/api/search?q=&limit=999'
```

Then open the URL in a browser and confirm the search page and the benchmark charts both render.

Finally, put the live URL at the top of `README.md` — a recruiter skimming for 30 seconds should
find a working link before anything else.

---

## Notes on cost and limits

- **Render free tier**: 512 MB RAM, spins down after ~15 minutes idle, 750 instance-hours/month.
  Enough for a portfolio demo.
- **Memory**: the index needs ~45 MB of heap; `MaxRAMPercentage=70` leaves headroom for the JVM's
  own overhead within 512 MB. `UseSerialGC` is chosen because parallel collectors' bookkeeping is
  a poor trade in a small single-core container.
- **No database**, no external services, no secrets. There is nothing to configure beyond the
  container, and nothing that can leak.
