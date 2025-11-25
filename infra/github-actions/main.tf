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
    "iam",
    "iamcredentials",
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
  location      = var.artifact_registry_location
  format        = "DOCKER"

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
    (var.github_actions_variable_gcp_project)                = var.gcp_project
    (var.github_actions_variable_gcp_wif_provider)           = google_iam_workload_identity_pool_provider.github_oidc.name
    (var.github_actions_variable_contatiner_repository_name) = google_artifact_registry_repository.default.registry_uri
  }

  repository    = data.github_repository.current.name
  variable_name = each.key
  value         = each.value
}
