package com.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class RecoveryController {
    private final ClusterService k8s;
    private final BaselineRepo baselines;
    private final RunRepo runs;
    private final ObjectMapper json = new ObjectMapper();
    @Value("${recovery.source-context}") String src;
    @Value("${recovery.target-context}") String tgt;
    @Value("${recovery.namespace}") String ns;

    public RecoveryController(ClusterService k8s, BaselineRepo b, RunRepo r) { this.k8s = k8s; this.baselines = b; this.runs = r; }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("source", Map.of("cluster", src, "apps", k8s.status(src)),
                      "target", Map.of("cluster", tgt, "apps", k8s.status(tgt)));
    }

    @GetMapping("/baseline")
    public Object baseline() { return baselines.findFirstByOrderByIdDesc().orElse(null); }

    @PostMapping("/baseline")
    public Object captureBaseline() {
        var snap = k8s.dataSnapshot(src);
        if (snap.isEmpty()) throw new IllegalStateException("Source database is not reachable. Is the source app running?");
        Baseline b = new Baseline();
        b.rowCount = snap.get().getKey();
        b.dataFingerprint = snap.get().getValue();
        b.configFingerprint = k8s.configFingerprint(src);
        b.functionality = k8s.functional(src);
        return baselines.save(b);
    }

    @GetMapping("/backups")
    public Object backups() throws Exception {
        Shell.Result r = Shell.run("kubectl", "--context", src, "-n", "velero", "get", "backups.velero.io", "-o", "json");
        if (!r.ok()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var item : json.readTree(r.out()).get("items")) {
            var st = item.path("status");
            out.add(Map.of(
                "name", item.path("metadata").path("name").asText(),
                "phase", st.path("phase").asText("New"),
                "items", st.path("progress").path("itemsBackedUp").asInt(),
                "total", st.path("progress").path("totalItems").asInt(),
                "created", item.path("metadata").path("creationTimestamp").asText(),
                "expires", st.path("expiration").asText("")));
        }
        out.sort((a, b) -> ((String) b.get("created")).compareTo((String) a.get("created")));
        return out;
    }

    @PostMapping("/backups")
    public Map<String, Object> createBackup() {
        String name = "wordpress-backup-" + System.currentTimeMillis() / 1000;
        Shell.Result r = Shell.run("velero", "--kubecontext", src, "backup", "create", name,
                "--include-namespaces", ns, "--default-volumes-to-fs-backup", "--wait");
        return Map.of("name", name, "ok", r.ok(), "log", r.out());
    }

    @PostMapping("/failure")
    public Map<String, Object> simulateFailure() {
        k8s.scale(src, 0); // PVC and data are intentionally kept
        return Map.of("ok", true);
    }

    @PostMapping("/recover-source")
    public Map<String, Object> recoverSource() { k8s.scale(src, 1); return Map.of("ok", true); }

    @PostMapping("/restore")
    public RecoveryRun restore() throws Exception {
        Baseline base = baselines.findFirstByOrderByIdDesc()
                .orElseThrow(() -> new IllegalStateException("Capture a healthy baseline first."));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bks = (List<Map<String, Object>>) backups();
        String backup = bks.stream().filter(b -> "Completed".equals(b.get("phase")))
                .map(b -> (String) b.get("name")).findFirst()
                .orElseThrow(() -> new IllegalStateException("No completed backup found."));

        String restoreName = "wordpress-restore-" + System.currentTimeMillis() / 1000;
        long t0 = System.nanoTime();
        Shell.run("kubectl", "--context", tgt, "delete", "ns", ns, "--ignore-not-found", "--wait=true");
        Shell.Result rr = Shell.run("velero", "--kubecontext", tgt, "restore", "create", restoreName,
                "--from-backup", backup, "--wait");
        // wait until MySQL answers (fs-backup data restore finishes before pod starts)
        Shell.run("kubectl", "--context", tgt, "-n", ns, "rollout", "status", "deploy/mysql", "--timeout=180s");
        Shell.run("kubectl", "--context", tgt, "-n", ns, "rollout", "status", "deploy/wordpress", "--timeout=180s");
        long t1 = System.nanoTime();

        var snap = k8s.dataSnapshot(tgt);
        boolean dataOk = snap.isPresent() && snap.get().getKey() == base.rowCount
                && snap.get().getValue().equals(base.dataFingerprint);
        boolean configOk = k8s.configFingerprint(tgt).equals(base.configFingerprint);
        boolean funcOk = k8s.functional(tgt);
        long t2 = System.nanoTime();

        RecoveryRun run = new RecoveryRun();
        run.restoreName = restoreName; run.backupName = backup;
        run.restoreSec = (t1 - t0) / 1e9; run.verifySec = (t2 - t1) / 1e9; run.totalSec = (t2 - t0) / 1e9;
        run.functionOk = funcOk; run.dataOk = dataOk; run.configOk = configOk;
        run.result = (rr.ok() && funcOk && dataOk && configOk) ? "PASS" : "FAIL";
        run.detail = "rows=" + (snap.isPresent() ? snap.get().getKey() : "n/a") + "/" + base.rowCount
                + (rr.ok() ? "" : " | velero: " + rr.out().substring(0, Math.min(300, rr.out().length())));
        return runs.save(run);
    }

    @GetMapping("/runs")
    public List<RecoveryRun> runs() { return runs.findAllByOrderByIdDesc(); }

    @ExceptionHandler(IllegalStateException.class)
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public Map<String, String> bad(IllegalStateException e) { return Map.of("error", e.getMessage()); }
}
