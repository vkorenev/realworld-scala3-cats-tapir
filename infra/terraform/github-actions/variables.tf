variable "gcp_project_id" {
  description = "GCP project ID"
  type        = string
}

variable "github_repository" {
  type        = string
  description = "The GitHub repository"
}

variable "github_actions_variable_gcp_project_id" {
  type        = string
  description = "GitHub Actions variable name for GCP project ID"
  default     = "gcp_project_id"
}

variable "github_actions_variable_gcp_wif_provider" {
  type        = string
  description = "GitHub Actions variable name for GCP Workload Identity Federation provider"
  default     = "gcp_wif_provider"
}
