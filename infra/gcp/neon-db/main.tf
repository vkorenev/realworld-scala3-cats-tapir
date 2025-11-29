provider "neon" {}

resource "neon_project" "this" {
  name                      = var.neon_project_name
  pg_version                = var.pg_version
  region_id                 = var.neon_region_id
  history_retention_seconds = var.history_retention_seconds
  branch {
    name          = "production"
    role_name     = "app_backend"
    database_name = "app_db"
  }
  default_endpoint_settings {
    autoscaling_limit_min_cu = 0.25
    autoscaling_limit_max_cu = 1
  }
}
