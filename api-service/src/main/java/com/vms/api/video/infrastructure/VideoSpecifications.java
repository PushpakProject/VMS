package com.vms.api.video.infrastructure;

import com.vms.api.video.domain.Video;
import com.vms.api.video.domain.VideoStatus;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Composable list filters. Every query excludes soft-deleted rows. */
public final class VideoSpecifications {

  private VideoSpecifications() {}

  public static Specification<Video> notDeleted() {
    return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
  }

  public static Specification<Video> ownedBy(UUID ownerId) {
    return (root, query, cb) -> cb.equal(root.get("ownerId"), ownerId);
  }

  /** Input is pre-validated against the Tracking ID pattern; '_' is escaped for LIKE. */
  public static Specification<Video> trackingIdContains(String trackingId) {
    String pattern = "%" + trackingId.toLowerCase(Locale.ROOT).replace("_", "\\_") + "%";
    return (root, query, cb) -> cb.like(cb.lower(root.get("trackingId")), pattern, '\\');
  }

  public static Specification<Video> hasStatus(VideoStatus status) {
    return (root, query, cb) -> cb.equal(root.get("status"), status);
  }
}
