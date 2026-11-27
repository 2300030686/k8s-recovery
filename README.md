# Kubernetes recovery console

React (Vite) + Spring Boot + MySQL. Backs up WordPress/MySQL in `kind-recovery-demo` with Velero + MinIO,
restores into `kind-recovery-target`, and verifies function, data (30 rows, SHA-256) and config against a baseline.

## Prerequisites
docker, kind, kubectl, velero CLI, Java 17, Maven, Node 18+

## Run
1. `./scripts/setup.sh`   (two kind clusters, MinIO, Velero, WordPress+MySQL, 30 seeded rows, dashboard MySQL)
2. `cd backend && mvn spring-boot:run`
3. `cd frontend && npm install && npm run dev`  → http://localhost:5173

## Demo order
Capture baseline → Create backup → Simulate failure → Restore application → read verification.
Clean up with `./scripts/teardown.sh`.

The 30 sample records live in table `recovery_records` (see `k8s/seed.sql`).
