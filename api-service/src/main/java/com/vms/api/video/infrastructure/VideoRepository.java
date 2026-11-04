package com.vms.api.video.infrastructure;

import com.vms.api.video.domain.Video;
import com.vms.api.video.domain.VideoStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VideoRepository
    extends JpaRepository<Video, UUID>, JpaSpecificationExecutor<Video> {

  Optional<Video> findByOwnerIdAndClientUploadId(UUID ownerId, UUID clientUploadId);

  @Query(
      "select v.id from Video v where v.status = :status and v.createdAt < :before "
          + "and v.deletedAt is null order by v.createdAt")
  List<UUID> findIdsByStatusCreatedBefore(
      @Param("status") VideoStatus status, @Param("before") Instant before, Limit limit);
}
