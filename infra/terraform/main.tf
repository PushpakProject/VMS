locals {
  apis = [
    "compute.googleapis.com",
    "storage.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "secretmanager.googleapis.com",
    "artifactregistry.googleapis.com",
    "logging.googleapis.com",
    "cloudbuild.googleapis.com",
    "iap.googleapis.com",
  ]
  public_host = "${replace(google_compute_address.vms.address, ".", "-")}.sslip.io"
  registry    = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.vms.repository_id}"
}

resource "google_project_service" "apis" {
  for_each           = toset(local.apis)
  service            = each.value
  disable_on_destroy = false
}

resource "google_artifact_registry_repository" "vms" {
  repository_id = "vms"
  location      = var.region
  format        = "DOCKER"
  depends_on    = [google_project_service.apis]
}

# --- Runtime identity (the VM). Least privilege, no key files. ---------------------------
resource "google_service_account" "runtime" {
  account_id   = "vms-runtime"
  display_name = "VMS runtime"
  depends_on   = [google_project_service.apis]
}

# Signs V4 URLs through IAM signBlob as itself.
resource "google_service_account_iam_member" "runtime_self_signer" {
  service_account_id = google_service_account.runtime.name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_project_iam_member" "runtime_logs" {
  project = var.project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_artifact_registry_repository_iam_member" "runtime_pull" {
  location   = var.region
  repository = google_artifact_registry_repository.vms.name
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.runtime.email}"
}

# --- Build identity (Cloud Build pushes images). ------------------------------------------
resource "google_service_account" "builder" {
  account_id   = "vms-builder"
  display_name = "VMS Cloud Build"
  depends_on   = [google_project_service.apis]
}

resource "google_artifact_registry_repository_iam_member" "builder_push" {
  location   = var.region
  repository = google_artifact_registry_repository.vms.name
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.builder.email}"
}

resource "google_project_iam_member" "builder_roles" {
  for_each = toset(["roles/logging.logWriter", "roles/storage.objectViewer"])
  project  = var.project_id
  role     = each.value
  member   = "serviceAccount:${google_service_account.builder.email}"
}
