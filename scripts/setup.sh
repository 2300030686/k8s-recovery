#!/usr/bin/env bash
# Prereqs: docker, kind, kubectl, velero CLI, java 17, maven, node 18+
set -euo pipefail
cd "$(dirname "$0")/.."

kind create cluster --name recovery-demo   || true
kind create cluster --name recovery-target || true

# MinIO + dashboard MySQL (MinIO joins the 'kind' docker network so both clusters reach it)
docker rm -f minio dashboard-mysql >/dev/null 2>&1 || true
docker run -d --name minio --network kind -p 9000:9000 -p 9001:9001 \
  -e MINIO_ROOT_USER=minio -e MINIO_ROOT_PASSWORD=minio123 \
  minio/minio server /data --console-address ":9001"
docker run -d --name dashboard-mysql -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=dashboard mysql:8.0
sleep 8
docker run --rm --network kind --entrypoint sh minio/mc -c \
  "mc alias set m http://minio:9000 minio minio123 && mc mb -p m/velero"

printf "[default]\naws_access_key_id=minio\naws_secret_access_key=minio123\n" > /tmp/velero-creds
for ctx in kind-recovery-demo kind-recovery-target; do
  velero install --kubecontext $ctx --provider aws \
    --plugins velero/velero-plugin-for-aws:v1.10.0 --bucket velero \
    --secret-file /tmp/velero-creds --use-volume-snapshots=false \
    --use-node-agent --default-volumes-to-fs-backup \
    --backup-location-config region=minio,s3ForcePathStyle=true,s3Url=http://minio:9000
done

# Source app + 30 sample records
kubectl --context kind-recovery-demo apply -f k8s/app.yaml
kubectl --context kind-recovery-demo -n wordpress rollout status deploy/mysql --timeout=300s
kubectl --context kind-recovery-demo -n wordpress exec -i deploy/mysql -- \
  mysql -uroot -prootpass wordpress < k8s/seed.sql
kubectl --context kind-recovery-demo -n wordpress exec deploy/mysql -- \
  mysql -uroot -prootpass wordpress -e "SELECT COUNT(*) AS records FROM recovery_records"
echo "Done. Next: (cd backend && mvn spring-boot:run)  and  (cd frontend && npm i && npm run dev)"
