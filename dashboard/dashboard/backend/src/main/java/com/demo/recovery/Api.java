package com.demo.recovery;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api")
public class Api {
  static final Set<String> ACTIONS = Set.of("baseline", "backup", "fail", "heal", "restore", "verify", "reset");
  final Runner r; final JdbcTemplate db;
  public Api(Runner r, JdbcTemplate db) { this.r = r; this.db = db; }

  @GetMapping("/state") public Map<String, Object> state() {
    Map<String, Object> m = new LinkedHashMap<>(r.snapshot());
    m.put("busy", r.current());
    List<Map<String, Object>> b = db.queryForList("SELECT ts, records, fingerprint FROM baseline ORDER BY id DESC LIMIT 1");
    m.put("baseline", b.isEmpty() ? null : b.get(0));
    return m;
  }
  @GetMapping("/runs") public List<Map<String, Object>> runs() { return db.queryForList("SELECT id, ts, step, status, seconds, detail FROM runs ORDER BY id DESC LIMIT 30"); }
  @GetMapping("/recoveries") public List<Map<String, Object>> recs() { return db.queryForList("SELECT id, ts, restore_name, result, checks, restore_s, verify_s, total_s FROM recoveries ORDER BY id DESC LIMIT 50"); }
  @GetMapping("/logs") public List<Map<String, Object>> logs(@RequestParam(defaultValue = "0") long after) {
    if (after == 0) { List<Map<String, Object>> l = db.queryForList("SELECT id, ts, level, msg FROM logs ORDER BY id DESC LIMIT 120"); Collections.reverse(l); return l; }
    return db.queryForList("SELECT id, ts, level, msg FROM logs WHERE id > ? ORDER BY id LIMIT 300", after);
  }
  @PostMapping("/cancel") public ResponseEntity<String> cancel() { return r.cancel() ? ResponseEntity.ok("cancelling") : ResponseEntity.status(409).body("nothing running"); }
  @PostMapping("/actions/{name}") public ResponseEntity<String> act(@PathVariable String name) {
    if (!ACTIONS.contains(name)) return ResponseEntity.badRequest().body("unknown action");
    return r.start(name) ? ResponseEntity.accepted().body("started") : ResponseEntity.status(409).body("busy");
  }
}
