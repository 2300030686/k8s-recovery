package com.recovery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ClusterService {
    @Value("${recovery.namespace}") String ns;
    @Value("${recovery.mysql-password}") String dbPass;

    /** "Running" when ready replicas >= 1, otherwise "Stopped" / "Unavailable". */
    String deployState(String ctx, String name) {
        Shell.Result r = Shell.run("kubectl", "--context", ctx, "-n", ns, "get", "deploy", name,
                "-o", "jsonpath={.status.readyReplicas}/{.spec.replicas}");
        if (!r.ok()) return "Unavailable";
        String[] p = r.out().split("/");
        if (p.length < 2) return "Stopped";
        return (!p[0].isEmpty() && !p[0].equals("0")) ? "Running" : (p[1].equals("0") ? "Stopped" : "Starting");
    }

    Map<String, String> status(String ctx) {
        return Map.of("wordpress", deployState(ctx, "wordpress"), "mysql", deployState(ctx, "mysql"));
    }

    void scale(String ctx, int replicas) {
        for (String d : List.of("wordpress", "mysql"))
            Shell.run("kubectl", "--context", ctx, "-n", ns, "scale", "deploy", d, "--replicas=" + replicas);
    }

    /** Returns [rowCount, dataFingerprint] read from MySQL inside the cluster. */
    Optional<Map.Entry<Integer, String>> dataSnapshot(String ctx) {
        Shell.Result r = Shell.run("kubectl", "--context", ctx, "-n", ns, "exec", "deploy/mysql", "--",
                "mysql", "-uroot", "-p" + dbPass, "wordpress", "-N", "-B", "-e",
                "SELECT id,customer,email,title,amount,created_at FROM recovery_records ORDER BY id");
        if (!r.ok()) return Optional.empty();
        String body = r.out().lines().filter(l -> !l.contains("Using a password")).reduce("", (a, b) -> a + b + "\n");
        int rows = (int) body.lines().filter(l -> !l.isBlank()).count();
        return Optional.of(Map.entry(rows, Shell.sha256(body)));
    }

    String configFingerprint(String ctx) {
        Shell.Result r = Shell.run("kubectl", "--context", ctx, "-n", ns, "get", "deploy", "wordpress", "mysql",
                "-o", "jsonpath={range .items[*]}{.metadata.name}|{.spec.template.spec.containers[*].image}|"
                        + "{.spec.template.spec.containers[*].env}|{.spec.template.spec.containers[*].ports}{\"\\n\"}{end}");
        return r.ok() ? Shell.sha256(r.out()) : "unavailable";
    }

    /** WordPress answers HTTP and MySQL answers a query. */
    boolean functional(String ctx) {
        Shell.Result http = Shell.run("kubectl", "--context", ctx, "-n", ns, "exec", "deploy/wordpress", "--",
                "curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "-L", "http://localhost/wp-login.php");
        boolean web = http.ok() && http.out().endsWith("200");
        return web && dataSnapshot(ctx).isPresent();
    }
}
