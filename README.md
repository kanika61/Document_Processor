# SuretySeven Document Processing Pipeline

Full-stack take-home: a Spring Boot backend that ingests documents, runs a simulated async
extraction + validation pipeline with retries, and a React frontend to drive it.

```
backend/    Spring Boot + MySQL API — see backend/README.md
frontend/   React + Vite UI — see frontend/README.md
```

## Quickstart (Docker)

Only Docker with Compose is needed — no JDK, Maven, Node or MySQL.

```bash
docker compose up --build
```

- UI: http://localhost:5173
- API: http://localhost:8080
- Stop with `Ctrl+C`; `docker compose down` keeps your data, `docker compose down -v` wipes it.

Ports 5173 and 8080 must be free (stop any local dev servers first). The browser calls the API
at `http://localhost:8080`, so run the stack on the same machine as the browser; to host it
elsewhere, rebuild the frontend with `VITE_API_BASE_URL` set and update the CORS origins in
`backend/.../config/WebConfig.java`. The database password (`root`) is for local demo use only.

## Run without Docker

```bash
# 1. Database
mysql -u root -e "CREATE DATABASE IF NOT EXISTS document_processor;"

# 2. Backend (localhost:8080)
cd backend
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # if JDK 21 isn't your default
mvn clean package
java -jar target/document-processor-1.0.0.jar

# 3. Frontend (localhost:5173), in a second terminal
cd frontend
npm install
npm run dev
```

See `backend/README.md` and `backend/API_CONTRACTS.md` for full API docs, and
`frontend/README.md` for frontend-specific notes.
