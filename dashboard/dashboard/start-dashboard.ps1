# Run from this folder: .\start-dashboard.ps1   (dashboard -> http://172.24.48.1:8097 ; its MySQL is internal only)
$ErrorActionPreference = 'Continue'
New-Item -ItemType Directory -Force -Path "$PSScriptRoot\kube" | Out-Null
# internal kubeconfigs: use the kind node names, reachable from containers on the "kind" network
kind get kubeconfig --internal --name recovery-demo   | Set-Content -Encoding ascii "$PSScriptRoot\kube\demo.yaml"
kind get kubeconfig --internal --name recovery-target | Set-Content -Encoding ascii "$PSScriptRoot\kube\target.yaml"

docker volume create dash-mysql-data | Out-Null
if (-not (docker ps -a --format "{{.Names}}" | Select-String -SimpleMatch "dash-mysql")) {
  docker run -d --name dash-mysql --network kind --restart unless-stopped -e MYSQL_ROOT_PASSWORD=dashpass -e MYSQL_DATABASE=dashboard -v dash-mysql-data:/var/lib/mysql mysql:8.0
} else { docker start dash-mysql | Out-Null }

docker build -t recovery-dashboard:1 $PSScriptRoot
docker rm -f recovery-dashboard 2>&1 | Out-Null
docker run -d --name recovery-dashboard --network kind --restart unless-stopped -p 8097:8080 `
  -v "$PSScriptRoot\kube:/kube:ro" -e DB_HOST=dash-mysql -e DB_NAME=dashboard -e DB_PASSWORD=dashpass recovery-dashboard:1
Write-Host "Dashboard: http://172.24.48.1:8097  (allow ~1-2 min for first start)"
