output "terraform_backend_bucket_name" {
  description = "Name of the GCS bucket used for Terraform backend storage."
  value       = google_storage_bucket.tf_state.name
}
