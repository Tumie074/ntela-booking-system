package com.booking.soc.repository;

import com.booking.soc.model.SecurityEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface SecurityEventRepository extends JpaRepository<SecurityEvent, String> {

    List<SecurityEvent> findAllByEventTypeAndUserIdAndTimestampAfter(String eventType, String userId, Instant after);

    List<SecurityEvent> findAllByEventTypeAndSourceIpAndTimestampAfter(String eventType, String sourceIp, Instant after);

    List<SecurityEvent> findAllByServiceNameAndStatusCodeInAndTimestampAfter(String serviceName, List<Integer> statusCodes, Instant after);

    List<SecurityEvent> findAllByServiceNameAndTimestampAfter(String serviceName, Instant after);

    List<SecurityEvent> findTop100ByOrderByTimestampDesc();

    List<SecurityEvent> findAllByProcessedFalseAndEventTypeAndTimestampAfter(String eventType, Instant after);

    List<SecurityEvent> findAllByProcessedFalseAndEventTypeInAndTimestampAfter(List<String> eventTypes, Instant after);
}
