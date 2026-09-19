# SuretySeven Document Processing Pipeline

Full-stack take-home: a Spring Boot backend that ingests documents, runs a simulated async
extraction + validation pipeline with retries, and a React frontend to drive it.

```
backend/    Spring Boot + MySQL API — see backend/README.md
frontend/   React + Vite UI — see frontend/README.md
```

## Quickstart

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
