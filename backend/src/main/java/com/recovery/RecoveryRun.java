package com.recovery;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class RecoveryRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String restoreName, backupName, result;
    public double totalSec, restoreSec, verifySec;
    public boolean functionOk, dataOk, configOk;
    @Column(length = 1000) public String detail;
    public LocalDateTime createdAt = LocalDateTime.now();
}
