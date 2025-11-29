provider "google" {
  project = var.gcp_project
}

resource "google_cloud_run_v2_service" "default" {
  name                 = "realworld-cloudrun-service"
  location             = var.cloud_run_location
  deletion_protection  = false
  ingress              = "INGRESS_TRAFFIC_ALL"
  invoker_iam_disabled = true

  scaling {
    min_instance_count = 0
    max_instance_count = 3
  }

  template {
    service_account = var.cloud_run_service_account_email
    containers {
      name  = "realworld-backend"
      image = var.container_image
      ports {
        container_port = var.container_port
      }
      startup_probe {
        initial_delay_seconds = 1
        timeout_seconds       = 1
        period_seconds        = 1
        failure_threshold     = 30
        http_get {
          path = "/__health/liveness"
          port = var.container_port
        }
      }
      liveness_probe {
        timeout_seconds = 30
        period_seconds  = 30
        http_get {
          path = "/__health/liveness"
          port = var.container_port
        }
      }
      env {
        name  = "JDBC_URL"
        value = "jdbc:postgresql://${var.database_host}/${var.database_name}?sslmode=require"
      }
      env {
        name  = "DB_USERNAME"
        value = var.database_username
      }
      env {
        name = "DB_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = var.database_password_secret_name
            version = "latest"
          }
        }
      }
      env {
        name = "JWT_SECRET_KEY"
        value_source {
          secret_key_ref {
            secret  = var.jwt_secret_key_secret_name
            version = "latest"
          }
        }
      }
      resources {
        startup_cpu_boost = true
        limits = {
          cpu    = "1"
          memory = "1Gi"
        }
      }
    }
  }
}
