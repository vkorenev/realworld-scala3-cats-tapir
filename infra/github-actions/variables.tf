variable "gcp_project" {
  description = "GCP project ID"
  type        = string
}

variable "tf_state_bucket" {
  description = "The GCS bucket name for Terraform state storage"
  type        = string
}

variable "artifact_registry_location" {
  description = "Location for the Artifact Registry"
  type        = string
  default     = "us-central1"
}

variable "artifact_registry_repository_id" {
  description = "Artifact Registry repository ID for storing container images"
  type        = string
  default     = "container-registry"
}

variable "github_repository" {
  type        = string
  description = "The GitHub repository"
}

variable "github_actions_variable_gcp_project" {
  type        = string
  description = "GitHub Actions variable name for GCP project ID"
  default     = "gcp_project"
}

variable "github_actions_variable_gcp_wif_provider" {
  type        = string
  description = "GitHub Actions variable name for GCP Workload Identity Federation provider"
  default     = "gcp_wif_provider"
}

variable "github_actions_variable_contatiner_repository_name" {
  type        = string
  description = "GitHub Actions variable name for Container Registry URI"
  default     = "container_repository_name"
}
