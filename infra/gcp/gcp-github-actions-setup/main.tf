provider "github" {}

provider "google" {
  project = var.gcp_project
}

locals {
  github_repository_id     = data.github_repository.current.repo_id
  github_actions_principal = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.repository_id/${local.github_repository_id}"
}

data "github_repository" "current" {
  full_name = var.github_repository
}

resource "random_id" "default" {
  byte_length = 4
}

resource "google_project_service" "enabled" {
  for_each = toset([
    "artifactregistry",
    "cloudresourcemanager",
    "cloudtrace",
    "iam",
    "iamcredentials",
    "logging",
    "run",
    "secretmanager",
    "sts",
  ])

  service            = "${each.value}.googleapis.com"
  disable_on_destroy = false
}

resource "google_iam_workload_identity_pool" "github" {
  project                   = var.gcp_project
  workload_identity_pool_id = "github-${random_id.default.hex}"
  display_name              = "github.com"
  description               = "Workload Identity Pool for GitHub"
  depends_on                = [google_project_service.enabled]
}

resource "google_iam_workload_identity_pool_provider" "github_oidc" {
  project                            = var.gcp_project
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = "github-oidc-${random_id.default.hex}"
  display_name                       = "github.com OIDC"
  description                        = "Workload Identity Pool Provider for GitHub"
  attribute_mapping = {
    "google.subject"                = "assertion.sub"
    "attribute.sub"                 = "attribute.sub"
    "attribute.aud"                 = "attribute.aud"
    "attribute.iss"                 = "attribute.iss"
    "attribute.actor"               = "assertion.actor"
    "attribute.actor_id"            = "assertion.actor_id"
    "attribute.repository"          = "assertion.repository"
    "attribute.repository_id"       = "assertion.repository_id"
    "attribute.repository_owner"    = "assertion.repository_owner"
    "attribute.repository_owner_id" = "assertion.repository_owner_id"
  }
  attribute_condition = "assertion.repository_id == '${local.github_repository_id}'"
  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
  depends_on = [google_iam_workload_identity_pool.github]
}

resource "google_artifact_registry_repository" "default" {
  repository_id = var.artifact_registry_repository_id
  location      = var.cloud_run_location
  format        = "DOCKER"

  cleanup_policies {
    id     = "delete-older"
    action = "DELETE"
    condition {
      tag_state = "ANY"
    }
  }
  cleanup_policies {
    id     = "keep-latest-3"
    action = "KEEP"
    most_recent_versions {
      keep_count = 3
    }
  }

  docker_config {
    immutable_tags = false
  }

  depends_on = [google_project_service.enabled]
}

resource "google_storage_bucket_iam_member" "tf_state_github_actions" {
  bucket = var.tf_state_bucket
  role   = "roles/storage.objectUser"
  member = local.github_actions_principal
}

resource "google_artifact_registry_repository_iam_member" "github_actions" {
  location   = google_artifact_registry_repository.default.location
  repository = google_artifact_registry_repository.default.name
  role       = "roles/artifactregistry.writer"
  member     = local.github_actions_principal
}

resource "github_actions_variable" "gcp" {
  for_each = {
    (var.github_actions_variable_gcp_project)               = var.gcp_project
    (var.github_actions_variable_gcp_wif_provider)          = google_iam_workload_identity_pool_provider.github_oidc.name
    (var.github_actions_variable_container_repository_name) = google_artifact_registry_repository.default.registry_uri
  }

  repository    = data.github_repository.current.name
  variable_name = each.key
  value         = each.value
}

resource "google_service_account" "cloud_run" {
  account_id   = "realworld-backend-cloud-run"
  display_name = "Realworld Backend Cloud Run"
  description  = "Service Account for Cloud Run"
}

resource "google_project_iam_member" "github_actions_cloud_run" {
  project = var.gcp_project
  role    = "roles/run.admin"
  member  = local.github_actions_principal
}

resource "google_service_account_iam_member" "github_actions_cloud_run" {
  service_account_id = google_service_account.cloud_run.id
  role               = "roles/iam.serviceAccountUser"
  member             = local.github_actions_principal
}

resource "google_secret_manager_secret" "cloud_run" {
  for_each = toset([
    "database-password",
    "jwt-secret-key",
    "otel-collector-config"
  ])

  secret_id = each.value
  replication {
    auto {}
  }
  depends_on = [google_project_service.enabled]
}

resource "google_secret_manager_secret_version" "database_password" {
  secret      = google_secret_manager_secret.cloud_run["database-password"].name
  secret_data = var.database_password
}

resource "random_password" "jwt_secret_key" {
  length = 16
}

resource "google_secret_manager_secret_version" "jwt_secret_key" {
  secret      = google_secret_manager_secret.cloud_run["jwt-secret-key"].name
  secret_data = random_password.jwt_secret_key.result
}

resource "google_secret_manager_secret_version" "otel_config" {
  secret      = google_secret_manager_secret.cloud_run["otel-collector-config"].name
  secret_data = file("${path.module}/otel-collector-config.yaml")
}

resource "google_secret_manager_secret_iam_member" "secret_access" {
  for_each = google_secret_manager_secret.cloud_run

  secret_id = each.value.id
  role      = "roles/secretmanager.secretAccessor"
  member    = google_service_account.cloud_run.member
}

resource "google_project_iam_member" "cloud_run" {
  for_each = toset([
    "roles/cloudtrace.agent",
    "roles/logging.logWriter",
  ])

  project = var.gcp_project
  role    = each.key
  member  = google_service_account.cloud_run.member
}
