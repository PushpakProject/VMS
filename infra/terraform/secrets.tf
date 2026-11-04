# Generated once and stored in Secret Manager. Note: values are also in Terraform state, so the
# state must be kept private (see the gcs backend in versions.tf).
locals {
  secrets = {
    "vms-db-password"         = random_password.db.result
    "vms-jwt-secret"          = base64encode(random_password.jwt.result)
    "vms-demo-user-password"  = "Op-${random_password.demo_user.result}"
    "vms-demo-admin-password" = "Ad-${random_password.demo_admin.result}"
  }
}

resource "random_password" "db" {
  length  = 32
  special = false
}

resource "random_password" "jwt" {
  length  = 48
  special = false
}

resource "random_password" "demo_user" {
  length  = 16
  special = false
}

resource "random_password" "demo_admin" {
  length  = 16
  special = false
}

resource "google_secret_manager_secret" "vms" {
  for_each  = local.secrets
  secret_id = each.key
  replication {
    auto {}
  }
  depends_on = [google_project_service.apis]
}

resource "google_secret_manager_secret_version" "vms" {
  for_each    = local.secrets
  secret      = google_secret_manager_secret.vms[each.key].id
  secret_data = each.value
}

# The VM reads only these secrets (not every secret in the project).
resource "google_secret_manager_secret_iam_member" "runtime_access" {
  for_each  = local.secrets
  secret_id = google_secret_manager_secret.vms[each.key].id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.runtime.email}"
}
