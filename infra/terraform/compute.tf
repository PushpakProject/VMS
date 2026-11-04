resource "google_compute_address" "vms" {
  name       = "vms-ip"
  region     = var.region
  depends_on = [google_project_service.apis]
}

# 80/443 open to everyone; SSH only through Identity-Aware Proxy.
resource "google_compute_firewall" "web" {
  name          = "vms-allow-web"
  network       = "default"
  source_ranges = ["0.0.0.0/0"]
  target_tags   = ["vms-web"]
  allow {
    protocol = "tcp"
    ports    = ["80", "443"]
  }
  depends_on = [google_project_service.apis]
}

resource "google_compute_firewall" "iap_ssh" {
  name          = "vms-allow-iap-ssh"
  network       = "default"
  source_ranges = ["35.235.240.0/20"]
  target_tags   = ["vms-web"]
  allow {
    protocol = "tcp"
    ports    = ["22"]
  }
  depends_on = [google_project_service.apis]
}

resource "google_compute_instance" "vms" {
  name         = var.vm_name
  machine_type = var.machine_type
  zone         = var.zone
  tags         = ["vms-web"]

  boot_disk {
    initialize_params {
      image = "ubuntu-os-cloud/ubuntu-2404-lts-amd64"
      size  = 30
      type  = "pd-balanced"
    }
  }

  network_interface {
    network = "default"
    access_config {
      nat_ip = google_compute_address.vms.address
    }
  }

  service_account {
    email  = google_service_account.runtime.email
    scopes = ["cloud-platform"]
  }

  metadata = {
    enable-oslogin = "TRUE"
    vms-region     = var.region
  }
  metadata_startup_script = file("${path.module}/../scripts/bootstrap-vm.sh")

  shielded_instance_config {
    enable_secure_boot          = true
    enable_vtpm                 = true
    enable_integrity_monitoring = true
  }

  depends_on = [
    google_storage_bucket_iam_member.runtime_objects,
    google_secret_manager_secret_iam_member.runtime_access,
    google_artifact_registry_repository_iam_member.runtime_pull,
  ]
}
