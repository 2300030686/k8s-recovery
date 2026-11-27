package com.recovery;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class Baseline {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "row_count") public int rowCount;
    public String dataFingerprint;
    public String configFingerprint;
    public boolean functionality;
    public LocalDateTime capturedAt = LocalDateTime.now();
}
