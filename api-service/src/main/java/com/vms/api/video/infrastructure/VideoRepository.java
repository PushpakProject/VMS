package com.vms.api.video.infrastructure;

import com.vms.api.video.domain.Video;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface VideoRepository
    extends JpaRepository<Video, UUID>, JpaSpecificationExecutor<Video> {

  Optional<Video> findByOwnerIdAndClientUploadId(UUID ownerId, UUID clientUploadId);
}
