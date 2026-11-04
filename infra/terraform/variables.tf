variable "project_id" {
  description = "GCP project id."
  type        = string
}

variable "letsencrypt_email" {
  description = "Email for Let's Encrypt certificate notices."
  type        = string
}

variable "region" {
  description = "Region for the VM, bucket and registry."
  type        = string
  default     = "us-central1"
}

variable "zone" {
  description = "Zone for the VM."
  type        = string
  default     = "us-central1-a"
}

variable "machine_type" {
  description = "VM size (8 GB fits Kafka + Postgres + two JVMs + ffmpeg)."
  type        = string
  default     = "e2-standard-2"
}

variable "vm_name" {
  type    = string
  default = "vms-vm"
}

variable "google_client_id" {
  description = "Optional OAuth web client id for Google Sign-In."
  type        = string
  default     = ""
}

variable "raw_retention_days" {
  description = "Backstop: delete unprocessed raw uploads after this many days."
  type        = number
  default     = 14
}
