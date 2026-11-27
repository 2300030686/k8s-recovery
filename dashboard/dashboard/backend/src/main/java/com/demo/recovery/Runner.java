package com.demo.recovery;

import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the recovery steps with kubectl/velero and records runs, logs and metrics in MySQL. */
@Service
public class Runner {
  static String env(String k, String d) { String v = System.getenv(k); return v == null ? d : v; }
  static final String D = env("DEMO_KUBECONFIG", "/kube/demo.yaml"), T = env("TARGET_KUBECONFIG", "/kube/target.yaml"),
      SRC = env("SRC_API", "http://app-api-source:8093"), TGT = env("TGT_API", "http://app-api-target:8096"), NS = "recovery-app";
  record V(boolean ok, String checks) {}

  final JdbcTemplate db;
  final ObjectMapper om = new ObjectMapper();
  final AtomicReference<String> current = new AtomicReference<>();
  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
  Map<String, Object> cache; long cacheAt;
  volatile Process proc; volatile boolean cancelled;

  public Runner(JdbcTemplate db) { this.db = db; }
  public String current() { return current.get(); }

  static String k(String kc, String args) { return "kubectl --kubeconfig " + kc + " " + args; }
  static String ts() { return new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()); }
  void log(String lvl, String m) { db.update("INSERT INTO logs(level,msg) VALUES(?,?)", lvl, m.length() > 590 ? m.substring(0, 590) : m); }
  void rec(String step, String st, double s, String d) {
    db.update("INSERT INTO runs(step,status,seconds,detail) VALUES(?,?,?,?)", step, st, s, d.length() > 390 ? d.substring(0, 390) : d);
  }

  void chk() { if (cancelled) throw new RuntimeException("cancelled by user"); }
  void nap(long ms) throws InterruptedException { Thread.sleep(ms); chk(); }
  public boolean cancel() {
    if (current.get() == null) return false;
    cancelled = true; Process p = proc; if (p != null) p.destroyForcibly();
    log("ERROR", "cancel requested by user"); return true;
  }
  /** Polls a Velero backup/restore until it completes, fails or times out, logging progress. */
  void waitPhase(String kc, String kind, String name, int maxS) throws Exception {
    long t0 = System.nanoTime(); String last = "";
    for (;;) {
      String ph = quiet(k(kc, "-n velero get " + kind + " " + name + " -o jsonpath={.status.phase} 2>/dev/null"));
      long el = (System.nanoTime() - t0) / 1_000_000_000L;
      if (!ph.equals(last) || el % 20 < 5) { log("INFO", kind + " " + name + ": " + (ph.isEmpty() ? "waiting" : ph) + " (" + el + "s)"); last = ph; }
      if (ph.equals("Completed")) { log("OK", kind + " completed in " + el + "s"); return; }
      if (ph.startsWith("Failed") || ph.equals("PartiallyFailed")) throw new RuntimeException(kind + " " + name + " ended as " + ph);
      if (el > maxS) throw new RuntimeException(kind + " " + name + " still " + ph + " after " + maxS + "s");
      nap(5000);
    }
  }
  String sh(String cmd) throws Exception {
    chk();
    Process p = new ProcessBuilder("bash", "-c", cmd).redirectErrorStream(true).start();
    proc = p;
    Thread wd = new Thread(() -> { try { Thread.sleep(1_500_000); } catch (InterruptedException e) { return; } if (p.isAlive()) p.destroyForcibly(); });
    wd.setDaemon(true); wd.start();
    StringBuilder sb = new StringBuilder();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
      String l; while ((l = r.readLine()) != null) if (!l.isBlank()) { log("INFO", l); sb.append(l).append('\n'); }
    }
    int c = p.waitFor(); wd.interrupt(); proc = null; chk();
    if (c != 0) throw new RuntimeException("command failed (exit " + c + ")");
    return sb.toString();
  }
  String quiet(String cmd) throws Exception {
    Process p = new ProcessBuilder("bash", "-c", cmd).start();
    String o = new String(p.getInputStream().readAllBytes()); p.waitFor(); return o.trim();
  }
  JsonNode stats(String base) throws Exception {
    HttpResponse<String> rs = http.send(HttpRequest.newBuilder(URI.create(base + "/api/stats")).timeout(Duration.ofSeconds(4)).build(), HttpResponse.BodyHandlers.ofString());
    return om.readTree(rs.body());
  }
  boolean ready(String kc) throws Exception {
    JsonNode it = om.readTree(quiet(k(kc, "-n " + NS + " get deploy -o json 2>/dev/null"))).path("items");
    if (it.size() == 0) return false;
    for (JsonNode d : it) if (d.path("status").path("readyReplicas").asInt(0) < 1) return false;
    return true;
  }

  Map<String, Object> side(String api, String kc) {
    Map<String, Object> m = new LinkedHashMap<>();
    try { JsonNode s = stats(api); m.put("up", true); m.put("records", s.path("records").asInt()); m.put("fingerprint", s.path("fingerprint").asText()); m.put("cluster", s.path("cluster").asText()); }
    catch (Exception e) { m.put("up", false); }
    return m;
  }
  List<Map<String, Object>> backups() throws Exception {
    JsonNode items = om.readTree(quiet(k(D, "-n velero get backup -o json 2>/dev/null"))).path("items");
    List<Map<String, Object>> out = new ArrayList<>();
    for (JsonNode i : items) {
      String n = i.path("metadata").path("name").asText();
      if (!n.startsWith("recovery-app-backup-")) continue;
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("name", n); m.put("phase", i.path("status").path("phase").asText("?"));
      m.put("items", i.path("status").path("progress").path("itemsBackedUp").asInt(0));
      m.put("created", i.path("metadata").path("creationTimestamp").asText());
      out.add(m);
    }
    out.sort((a, b) -> ((String) b.get("created")).compareTo((String) a.get("created")));
    return out;
  }
  String latestBackup() throws Exception {
    for (Map<String, Object> b : backups()) if ("Completed".equals(b.get("phase"))) return (String) b.get("name");
    return null;
  }
  public synchronized Map<String, Object> snapshot() {
    if (cache != null && System.currentTimeMillis() - cacheAt < 15000) return cache;
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("source", side(SRC, D)); m.put("target", side(TGT, T));
    try { m.put("backups", backups()); } catch (Exception e) { m.put("backups", List.of()); }
    cache = m; cacheAt = System.currentTimeMillis(); return m;
  }

  public boolean start(String name) {
    if (!current.compareAndSet(null, name)) return false;
    cancelled = false;
    new Thread(() -> {
      long t0 = System.nanoTime(); log("INFO", "== " + name + " started");
      try { String d = dispatch(name); double s = (System.nanoTime() - t0) / 1e9; rec(name, "OK", s, d); log("OK", "== " + name + " finished in " + String.format("%.1f", s) + "s"); }
      catch (Throwable e) { double s = (System.nanoTime() - t0) / 1e9; rec(name, "FAIL", s, String.valueOf(e.getMessage())); log("ERROR", "== " + name + " failed: " + e.getMessage()); }
      finally { cacheAt = 0; current.set(null); }
    }).start();
    return true;
  }

  String dispatch(String n) throws Exception {
    switch (n) {
      case "baseline": {
        JsonNode s = stats(SRC);
        db.update("INSERT INTO baseline(records,fingerprint) VALUES(?,?)", s.path("records").asInt(), s.path("fingerprint").asText());
        return s.path("records").asInt() + " records";
      }
      case "backup": {
        String b = "recovery-app-backup-" + ts();
        sh("velero --kubeconfig " + D + " backup create " + b + " --include-namespaces " + NS + " --default-volumes-to-fs-backup 2>&1");
        waitPhase(D, "backup", b, 1800);
        if (!quiet(k(D, "-n velero get backup " + b + " -o jsonpath={.status.phase} 2>/dev/null")).equals("Completed")) throw new RuntimeException("backup " + b + " did not complete");
        String pv = quiet(k(D, "-n velero get podvolumebackup -l velero.io/backup-name=" + b + " -o jsonpath={.items[*].status.phase} 2>/dev/null"));
        if (!pv.contains("Completed")) throw new RuntimeException("backup completed but the MySQL volume was NOT backed up");
        return b + " (volume backed up)";
      }
      case "fail": sh(k(D, "-n " + NS + " scale deploy --all --replicas=0 2>&1")); return "source scaled to zero";
      case "heal":
        sh(k(D, "-n " + NS + " scale deploy --all --replicas=1 2>&1"));
        for (String d : new String[]{"mysql", "api", "ui"}) sh(k(D, "-n " + NS + " rollout status deploy/" + d + " --timeout=400s 2>&1"));
        return "source restarted";
      case "verify": { V v = verify(); if (!v.ok()) throw new RuntimeException("verification " + v.checks()); return v.checks(); }
      case "restore": return restore();
      case "reset": return reset();
      default: throw new RuntimeException("unknown action " + n);
    }
  }

  /** Wipes the target app and ONLY its own data folder, then recreates a clean volume + claim. */
  String reset() throws Exception {
    final String DATA = "/var/local-pv/recovery-app-mysql";
    log("INFO", "deleting namespace " + NS + " in the target cluster");
    sh(k(T, "delete namespace " + NS + " --ignore-not-found --wait=true --timeout=300s 2>&1"));
    log("INFO", "wiping " + DATA + " on the target node");
    String wipe = """
apiVersion: v1
kind: Namespace
metadata: {name: recovery-reset}
---
apiVersion: v1
kind: Pod
metadata: {name: wipe, namespace: recovery-reset}
spec:
  restartPolicy: Never
  containers:
    - name: wipe
      image: recovery-api:1
      imagePullPolicy: IfNotPresent
      command: ["sh","-c","rm -rf /data/* /data/.[!.]* /data/..?*; echo wiped; ls -A /data | wc -l"]
      volumeMounts: [{name: d, mountPath: /data}]
  volumes:
    - name: d
      hostPath: {path: %s, type: Directory}
""".formatted(DATA);
    sh("kubectl --kubeconfig " + T + " apply -f - <<'EOF'\n" + wipe + "EOF");
    try {
      sh(k(T, "-n recovery-reset wait --for=jsonpath={.status.phase}=Succeeded pod/wipe --timeout=180s 2>&1"));
      sh(k(T, "-n recovery-reset logs wipe 2>&1"));
    } finally { sh(k(T, "delete namespace recovery-reset --ignore-not-found --wait=false 2>&1")); }
    sh(k(T, "delete pv recovery-mysql-local-pv --ignore-not-found --wait=true --timeout=120s 2>&1"));
    String fresh = """
apiVersion: v1
kind: PersistentVolume
metadata: {name: recovery-mysql-local-pv}
spec:
  capacity: {storage: 1Gi}
  accessModes: [ReadWriteOnce]
  persistentVolumeReclaimPolicy: Retain
  storageClassName: local-manual
  local: {path: %s}
  nodeAffinity:
    required:
      nodeSelectorTerms:
        - matchExpressions:
            - {key: kubernetes.io/hostname, operator: In, values: [recovery-target-control-plane]}
---
apiVersion: v1
kind: Namespace
metadata: {name: recovery-app}
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata: {name: mysql-data, namespace: recovery-app}
spec:
  accessModes: [ReadWriteOnce]
  resources: {requests: {storage: 1Gi}}
  storageClassName: local-manual
  volumeName: recovery-mysql-local-pv
""".formatted(DATA);
    sh("kubectl --kubeconfig " + T + " apply -f - <<'EOF'\n" + fresh + "EOF");
    String ph = "";
    for (int i = 0; i < 12 && !ph.equals("Bound"); i++) { nap(5000); ph = quiet(k(T, "-n " + NS + " get pvc mysql-data -o jsonpath={.status.phase} 2>/dev/null")); }
    if (!ph.equals("Bound")) throw new RuntimeException("claim mysql-data is " + ph + ", expected Bound");
    log("OK", "target is clean: empty volume bound to mysql-data");
    return "target reset";
  }

  V verify() throws Exception {
    Map<String, Object> b;
    try { b = db.queryForMap("SELECT records, fingerprint FROM baseline ORDER BY id DESC LIMIT 1"); }
    catch (Exception e) { throw new RuntimeException("no baseline captured yet"); }
    JsonNode s = null;
    for (int i = 0; i < 24 && s == null; i++) { try { s = stats(TGT); } catch (Exception e) { nap(5000); } }
    if (s == null) { log("ERROR", "target API unreachable"); return new V(false, "0/3"); }
    boolean c1 = s.path("records").asInt() == ((Number) b.get("records")).intValue();
    boolean c2 = s.path("fingerprint").asText().equals(String.valueOf(b.get("fingerprint")));
    boolean c3; try { c3 = ready(T); } catch (Exception e) { c3 = false; }
    log(c1 ? "OK" : "ERROR", "Row count " + (c1 ? "PASS" : "FAIL") + " (baseline " + b.get("records") + ", target " + s.path("records").asInt() + ")");
    log(c2 ? "OK" : "ERROR", "Data fingerprint " + (c2 ? "PASS" : "FAIL"));
    log(c3 ? "OK" : "ERROR", "Deployments ready " + (c3 ? "PASS" : "FAIL"));
    int n = (c1 ? 1 : 0) + (c2 ? 1 : 0) + (c3 ? 1 : 0);
    return new V(n == 3, n + "/3");
  }

  String restore() throws Exception {
    String b = latestBackup(); if (b == null) throw new RuntimeException("no completed backup found");
    if (!quiet(k(T, "-n " + NS + " get deploy mysql -o name 2>/dev/null")).isEmpty())
      throw new RuntimeException("the target already has the app. Run 'Reset target' first, then restore");
    String r = "recovery-app-restore-" + ts(); long t0 = System.nanoTime();
    try {
      log("INFO", "using backup " + b);
      for (int i = 0; i < 24 && quiet(k(T, "-n velero get backup " + b + " -o jsonpath={.status.phase} 2>/dev/null")).isEmpty(); i++) { if (i == 0) log("INFO", "waiting for target Velero to see the backup"); nap(5000); }
      sh("velero --kubeconfig " + T + " restore create " + r + " --from-backup " + b + " 2>&1");
      waitPhase(T, "restore", r, 1500);
      String ph = quiet(k(T, "-n velero get restore " + r + " -o jsonpath={.status.phase} 2>/dev/null"));
      if (!ph.equals("Completed")) throw new RuntimeException("restore " + r + " ended as " + ph);
      sh(k(T, "-n " + NS + " patch configmap cluster-info --type merge -p '{\"data\":{\"name\":\"kind-recovery-target\"}}' 2>&1"));
      sh(k(T, "-n " + NS + " rollout restart deploy/api 2>&1"));
      for (String d : new String[]{"mysql", "api", "ui"}) sh(k(T, "-n " + NS + " rollout status deploy/" + d + " --timeout=400s 2>&1"));
    } catch (Throwable e) {
      double rs = (System.nanoTime() - t0) / 1e9;
      db.update("INSERT INTO recoveries(restore_name,result,checks,restore_s,verify_s,total_s) VALUES(?,?,?,?,?,?)", r, "FAIL", "0/3", rs, 0.0, rs);
      throw e;
    }
    double rs = (System.nanoTime() - t0) / 1e9; long t1 = System.nanoTime();
    V v = verify(); double vs = (System.nanoTime() - t1) / 1e9;
    db.update("INSERT INTO recoveries(restore_name,result,checks,restore_s,verify_s,total_s) VALUES(?,?,?,?,?,?)", r, v.ok() ? "PASS" : "FAIL", v.checks(), rs, vs, rs + vs);
    if (!v.ok()) throw new RuntimeException("verification " + v.checks());
    return r + " " + v.checks();
  }
}
