output "app_url" {
  value = "https://${local.public_host}"
}

output "public_host" {
  value = local.public_host
}

output "bucket" {
  value = google_storage_bucket.videos.name
}

output "registry" {
  value = local.registry
}

output "ssh_command" {
  value = "gcloud compute ssh ${var.vm_name} --zone ${var.zone} --tunnel-through-iap"
}

# Non-secret deployment settings consumed by infra/scripts/deploy.sh.
resource "local_file" "deploy_env" {
  filename        = "${path.module}/../.deploy.env"
  file_permission = "0600"
  content         = <<-EOT
    PROJECT_ID=${var.project_id}
    REGION=${var.region}
    ZONE=${var.zone}
    VM_NAME=${var.vm_name}
    VMS_GCS_BUCKET=${google_storage_bucket.videos.name}
    REGISTRY=${local.registry}
    BUILD_SERVICE_ACCOUNT=${google_service_account.builder.email}
    PUBLIC_HOST=${local.public_host}
    LETSENCRYPT_EMAIL=${var.letsencrypt_email}
    VMS_GOOGLE_CLIENT_ID=${var.google_client_id}
  EOT
}
