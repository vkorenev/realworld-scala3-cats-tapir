output "cloud_run_service_account_email" {
  description = "The service account email for Cloud Run"
  value       = google_service_account.cloud_run.email
}

output "cloud_run_secret_names" {
  description = "Secret names for Cloud Run service (secret_id => full_name)"
  value       = { for id, secret in google_secret_manager_secret.cloud_run : id => secret.name }
}
