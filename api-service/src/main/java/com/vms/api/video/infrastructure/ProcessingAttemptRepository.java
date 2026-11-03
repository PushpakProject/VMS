package com.vms.api.video.infrastructure;

import com.vms.api.video.domain.ProcessingAttempt;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessingAttemptRepository extends JpaRepository<ProcessingAttempt, UUID> {

  Optional<ProcessingAttempt> findByVideoIdAndRunNumberAndAttemptNumber(
      UUID videoId, int runNumber, int attemptNumber);

  List<ProcessingAttempt> findByVideoIdOrderByRunNumberAscAttemptNumberAsc(UUID videoId);
}
