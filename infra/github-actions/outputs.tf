output "cloud_run_service_account_email" {
  description = "The service account email for Cloud Run"
  value       = google_service_account.cloud_run.email
}

output "database_password_secret_name" {
  description = "Secret name for the database password"
  value       = google_secret_manager_secret.cloud_run["database-password"].name
}

output "jwt_secret_key_secret_name" {
  description = "Secret name for the JWT secret key"
  value       = google_secret_manager_secret.cloud_run["jwt-secret-key"].name
}
