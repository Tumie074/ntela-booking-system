package com.booking.soc.repository;

import com.booking.soc.model.Alert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, String> {
    List<Alert> findTop100ByOrderByTimestampDesc();
}
