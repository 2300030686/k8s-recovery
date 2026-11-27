# Recovery demo app (React + Spring Boot + MySQL)
Runs alongside the existing recovery-watcher. Ports: 8092 UI, 8093 API, 8094 MySQL (source), 8095 UI, 8096 API (target).
Order: `.\demo.ps1 deploy` -> `baseline` -> `backup` -> `fail` -> `restore` (runs verify). Run `status` at any time.
Requires: Docker Desktop, kind, kubectl, velero CLI on PATH, Velero + node-agent already installed in both clusters pointing at the same MinIO bucket.
