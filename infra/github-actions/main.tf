provider "github" {}

provider "google" {
  project = var.gcp_project
}

locals {
  github_repository_id     = data.github_repository.current.repo_id
  github_actions_principal = "principalSet://iam.googleapis.com/${module.github-wif.pool_name}/attribute.repository_id/${local.github_repository_id}"
}

data "github_repository" "current" {
  full_name = var.github_repository
}

resource "random_id" "default" {
  byte_length = 4
}

module "github-wif" {
  source      = "Cyclenerd/wif-github/google"
  version     = "~> 1.0.0"
  project_id  = var.gcp_project
  pool_id     = "github-${random_id.default.hex}"
  provider_id = "github-oidc-${random_id.default.hex}"
  # Restrict access to the specific GitHub repository
  attribute_condition = "assertion.repository_id == '${local.github_repository_id}'"
}

resource "google_project_service" "enabled" {
  for_each = toset([
    "artifactregistry.googleapis.com",
  ])

  service = each.value
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

resource "google_artifact_registry_repository_iam_member" "member" {
  location   = google_artifact_registry_repository.default.location
  repository = google_artifact_registry_repository.default.name
  role       = "roles/artifactregistry.writer"
  member     = local.github_actions_principal
}

resource "github_actions_variable" "gcp" {
  for_each = {
    (var.github_actions_variable_gcp_project)                = var.gcp_project
    (var.github_actions_variable_gcp_wif_provider)           = module.github-wif.provider_name
    (var.github_actions_variable_contatiner_repository_name) = google_artifact_registry_repository.default.registry_uri
  }

  repository    = data.github_repository.current.name
  variable_name = each.key
  value         = each.value
}
