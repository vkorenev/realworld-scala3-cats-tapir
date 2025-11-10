variable "gcp_project_id" {
  description = "GCP project ID"
  type        = string
}

variable "tf_state_bucket_location" {
  description = "Location for the Terraform state bucket"
  type        = string
  default     = "us-central1"
}

variable "tf_backend_config_files" {
  description = "Terraform backend config files to generate"
  type        = map(string)
}
