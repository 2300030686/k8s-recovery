package com.demo.recovery;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController @RequestMapping("/api") @CrossOrigin
public class CustomerController {
  private final CustomerRepository repo;
  public CustomerController(CustomerRepository repo){ this.repo=repo; }

  @GetMapping("/records") public List<Customer> all(){ return repo.findAllByOrderByIdAsc(); }
  @PostMapping("/records") public Customer add(@RequestBody Customer c){ c.setId(null); return repo.save(c); }
  @PutMapping("/records/{id}") public Customer update(@PathVariable Integer id,@RequestBody Customer c){ c.setId(id); return repo.save(c); }
  @DeleteMapping("/records/{id}") public void delete(@PathVariable Integer id){ repo.deleteById(id); }

  /** Row count + SHA-256 fingerprint of all rows; compared against the baseline after restore. */
  @GetMapping("/stats") public Map<String,Object> stats() throws Exception {
    StringBuilder sb=new StringBuilder();
    List<Customer> rows=repo.findAllByOrderByIdAsc();
    for(Customer c:rows) sb.append(c.getId()).append('|').append(c.getName()).append('|').append(c.getEmail()).append('|')
      .append(c.getCity()).append('|').append(c.getAmount().setScale(2)).append('\n');
    byte[] h=MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
    StringBuilder hex=new StringBuilder(); for(byte b:h) hex.append(String.format("%02x",b));
    Map<String,Object> m=new LinkedHashMap<>();
    m.put("records",rows.size()); m.put("fingerprint",hex.toString());
    m.put("cluster",System.getenv().getOrDefault("CLUSTER_NAME","unknown"));
    return m;
  }
}
