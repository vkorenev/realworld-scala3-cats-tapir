output "service_uri" {
  description = "The URI of the deployed Cloud Run service"
  value       = google_cloud_run_v2_service.default.uri
}
