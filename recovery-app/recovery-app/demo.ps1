# Usage: .\demo.ps1 deploy | baseline | backup | fail | restore | verify | status
param([Parameter(Mandatory)][ValidateSet('deploy','baseline','backup','fail','restore','verify','status')]$Step)
$Src='kind-recovery-demo'; $Tgt='kind-recovery-target'; $NS='recovery-app'
$Here=$PSScriptRoot; $BaselineFile="$Here\baseline.json"; $BackupFile="$Here\last-backup.txt"

function Start-Proxy($name,$hostPort,$node,$nodePort){
  docker rm -f $name 2>&1 | Out-Null
  docker run -d --name $name --network kind -p "${hostPort}:${hostPort}" alpine/socat "TCP-LISTEN:${hostPort},fork,reuseaddr" "TCP:${node}:${nodePort}" | Out-Null
  Write-Host "  proxy $name -> localhost:$hostPort"
}
function Deploy-To($ctx,$kindName,$clusterLabel){
  kind load docker-image recovery-api:1 recovery-ui:1 --name $kindName
  kubectl --context $ctx create namespace $NS --dry-run=client -o yaml | kubectl --context $ctx apply -f -
  kubectl --context $ctx -n $NS create configmap mysql-init --from-file="$Here\db\init.sql" --dry-run=client -o yaml | kubectl --context $ctx apply -f -
  kubectl --context $ctx -n $NS create configmap cluster-info --from-literal=name=$clusterLabel --dry-run=client -o yaml | kubectl --context $ctx apply -f -
  kubectl --context $ctx apply -f "$Here\k8s\app.yaml"
}
function Wait-App($ctx){ foreach($d in 'mysql','api','ui'){ kubectl --context $ctx -n $NS rollout status deploy/$d --timeout=240s } }

switch($Step){
 'deploy' {
   docker build -t recovery-api:1 "$Here\backend"
   docker build -t recovery-ui:1 "$Here\frontend"
   Deploy-To $Src 'recovery-demo' $Src
   # target cluster only gets the images loaded; the app itself arrives via Velero restore
   kind load docker-image recovery-api:1 recovery-ui:1 --name recovery-target
   Wait-App $Src
   Start-Proxy 'app-ui-source'  8092 'recovery-demo-control-plane' 30092
   Start-Proxy 'app-api-source' 8093 'recovery-demo-control-plane' 30093
   Start-Proxy 'app-db-source'  8094 'recovery-demo-control-plane' 30094
   Start-Proxy 'app-ui-target'  8095 'recovery-target-control-plane' 30092
   Start-Proxy 'app-api-target' 8096 'recovery-target-control-plane' 30093
   Write-Host "Source UI: http://localhost:8092   Source API: http://172.24.48.1:8093/api/stats"
 }
 'baseline' {
   $s = Invoke-RestMethod http://172.24.48.1:8093/api/stats
   $s | Add-Member captured (Get-Date -Format o) -Force
   $s | ConvertTo-Json | Set-Content $BaselineFile
   Write-Host "Baseline saved: $($s.records) records, fingerprint $($s.fingerprint)"
 }
 'backup' {
   $name = "recovery-app-backup-$(Get-Date -Format yyyyMMddHHmmss)"
   velero --kubecontext $Src backup create $name --include-namespaces $NS --default-volumes-to-fs-backup --wait
   $name | Set-Content $BackupFile
   velero --kubecontext $Src backup describe $name | Select-String 'Phase|Items backed up|Total items'
 }
 'fail' {
   kubectl --context $Src -n $NS scale deploy --all --replicas=0
   Write-Host "Source app scaled to zero. http://localhost:8092 should now show an error."
 }
 'restore' {
   $b = (Get-Content $BackupFile).Trim()
   $r = "recovery-app-restore-$(Get-Date -Format yyyyMMddHHmmss)"
   $sw = [Diagnostics.Stopwatch]::StartNew()
   Write-Host "Waiting for target Velero to see backup $b ..."
   for($i=0;$i -lt 24;$i++){ velero --kubecontext $Tgt backup get $b 2>&1 | Out-Null; if($LASTEXITCODE -eq 0){break}; Start-Sleep 5 }
   velero --kubecontext $Tgt restore create $r --from-backup $b --wait
   Wait-App $Tgt
   $restoreSec = [math]::Round($sw.Elapsed.TotalSeconds,1)
   Write-Host "Restore + rollout: ${restoreSec}s  ($r)"
   & $PSCommandPath verify
 }
 'verify' {
   $base = Get-Content $BaselineFile | ConvertFrom-Json
   $now  = $null
   for($i=0;$i -lt 20 -and -not $now;$i++){ try{ $now = Invoke-RestMethod http://172.24.48.1:8096/api/stats }catch{ Start-Sleep 3 } }
   if(-not $now){ Write-Host "FAIL  target API unreachable"; exit 1 }
   $c1 = $now.records -eq $base.records
   $c2 = $now.fingerprint -eq $base.fingerprint
   $c3 = (kubectl --context $Tgt -n $NS get deploy -o jsonpath='{.items[*].status.readyReplicas}') -notmatch '^\s*$'
   "{0,-14} {1}  (baseline {2}, target {3})" -f 'Row count',     $(if($c1){'PASS'}else{'FAIL'}), $base.records, $now.records
   "{0,-14} {1}" -f 'Data fingerprint', $(if($c2){'PASS'}else{'FAIL'})
   "{0,-14} {1}" -f 'Deployments',      $(if($c3){'PASS'}else{'FAIL'})
   if($c1 -and $c2 -and $c3){ Write-Host "OVERALL: PASS  -> http://localhost:8095" -ForegroundColor Green } else { Write-Host "OVERALL: FAIL" -ForegroundColor Red; exit 1 }
 }
 'status' {
   foreach($c in $Src,$Tgt){ Write-Host "== $c"; kubectl --context $c -n $NS get pods 2>&1 }
   velero --kubecontext $Src backup get
 }
}

