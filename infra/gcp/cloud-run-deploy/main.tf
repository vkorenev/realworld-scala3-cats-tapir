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
      name       = "realworld-backend"
      image      = var.container_image
      depends_on = ["otel-collector"]
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
            secret  = var.cloud_run_secret_names["database-password"]
            version = "latest"
          }
        }
      }
      env {
        name = "JWT_SECRET_KEY"
        value_source {
          secret_key_ref {
            secret  = var.cloud_run_secret_names["jwt-secret-key"]
            version = "latest"
          }
        }
      }
      env {
        name  = "OTEL_SERVICE_NAME"
        value = "realworld-backend"
      }
      env {
        name  = "OTEL_EXPORTER_OTLP_ENDPOINT"
        value = "http://localhost:4317"
      }
      env {
        name  = "OTEL_EXPORTER_OTLP_PROTOCOL"
        value = "grpc"
      }
      resources {
        startup_cpu_boost = true
        limits = {
          cpu    = "1"
          memory = "1Gi"
        }
      }
    }
    containers {
      name  = "otel-collector"
      image = var.otel_collector_image
      args  = ["--config=/etc/otelcol/config.yaml"]
      startup_probe {
        initial_delay_seconds = 0
        timeout_seconds       = 1
        period_seconds        = 1
        failure_threshold     = 30
        http_get {
          path = "/"
          port = 13133
        }
      }
      liveness_probe {
        timeout_seconds = 10
        period_seconds  = 30
        http_get {
          path = "/"
          port = 13133
        }
      }
      env {
        name  = "GOOGLE_CLOUD_PROJECT"
        value = var.gcp_project
      }
      resources {
        limits = {
          cpu    = "200m"
          memory = "256Mi"
        }
      }
      volume_mounts {
        name       = "otel-config"
        mount_path = "/etc/otelcol"
      }
    }

    volumes {
      name = "otel-config"
      secret {
        secret = var.cloud_run_secret_names["otel-collector-config"]
        items {
          version = "latest"
          path    = "config.yaml"
        }
      }
    }
  }
}
