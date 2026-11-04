# Private video bucket: no public access, uniform IAM, CORS only for the app origin.
resource "google_storage_bucket" "videos" {
  name                        = "${var.project_id}-vms-videos"
  location                    = var.region
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false

  cors {
    origin          = ["https://${local.public_host}"]
    method          = ["PUT", "GET", "HEAD"]
    response_header = ["Content-Type", "Content-Range", "Range", "x-goog-content-length-range"]
    max_age_seconds = 3600
  }

  lifecycle_rule {
    condition {
      age            = var.raw_retention_days
      matches_prefix = ["raw/"]
    }
    action {
      type = "Delete"
    }
  }

  depends_on = [google_project_service.apis]
}

# The VM may read/write objects in this bucket only.
resource "google_storage_bucket_iam_member" "runtime_objects" {
  bucket = google_storage_bucket.videos.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.runtime.email}"
}
