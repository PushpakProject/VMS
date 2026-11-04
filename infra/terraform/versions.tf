terraform {
  # Cloud Shell ships Terraform preinstalled; nothing here needs a newer core than 1.5.
  required_version = ">= 1.5.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "8.9.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "3.9.1"
    }
    local = {
      source  = "hashicorp/local"
      version = "2.9.1"
    }
  }

  # State holds generated secrets: keep it in a private GCS bucket for anything shared.
  # terraform init -backend-config="bucket=<state-bucket>" after uncommenting:
  # backend "gcs" {
  #   prefix = "vms/state"
  # }
}

provider "google" {
  project = var.project_id
  region  = var.region
  zone    = var.zone
}
