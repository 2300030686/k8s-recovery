package com.recovery;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

interface BaselineRepo extends JpaRepository<Baseline, Long> { Optional<Baseline> findFirstByOrderByIdDesc(); }
interface RunRepo extends JpaRepository<RecoveryRun, Long> { List<RecoveryRun> findAllByOrderByIdDesc(); }
