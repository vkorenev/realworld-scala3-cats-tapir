variable "gcp_project" {
  description = "GCP project ID"
  type        = string
}

variable "cloud_run_location" {
  description = "Location for Cloud Run"
  type        = string
  default     = "us-central1"
}

variable "cloud_run_service_account_email" {
  description = "Service account email for Cloud Run"
  type        = string
}

variable "container_image" {
  description = "Container image for Cloud Run service"
  type        = string
}

variable "container_port" {
  description = "Port on which the container listens"
  type        = number
  default     = 8080
}

variable "database_host" {
  description = "Database host"
  type        = string
}

variable "database_name" {
  description = "Database name"
  type        = string
}

variable "database_username" {
  description = "Database username"
  type        = string
}

variable "cloud_run_secret_names" {
  description = "Secret names for Cloud Run service (secret_id => full_name)"
  type        = map(string)
}

variable "otel_collector_image" {
  description = "OpenTelemetry Collector image"
  type        = string
  default     = "us-docker.pkg.dev/cloud-ops-agents-artifacts/google-cloud-opentelemetry-collector/otelcol-google:0.138.0"
}
