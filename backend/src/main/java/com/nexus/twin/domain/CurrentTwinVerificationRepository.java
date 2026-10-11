package com.nexus.twin.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CurrentTwinVerificationRepository extends JpaRepository<CurrentTwinVerificationState,VerificationStateId> {
    List<CurrentTwinVerificationState> findByDeviceId(UUID deviceId);
    @Query("select v from CurrentTwinVerificationState v where v.verificationStatus = 'PENDING' and v.verificationDeadlineAt <= :now order by v.verificationDeadlineAt, v.deviceId")
    List<CurrentTwinVerificationState> findExpired(Instant now,Pageable limit);
}
