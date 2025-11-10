provider "github" {}

provider "google" {
  project = var.gcp_project_id
}

data "github_repository" "current" {
  full_name = var.github_repository
}

module "github-wif" {
  source     = "Cyclenerd/wif-github/google"
  version    = "~> 1.0.0"
  project_id = var.gcp_project_id
  # Restrict access to the specific GitHub repository
  attribute_condition = "assertion.repository_id == '${data.github_repository.current.repo_id}'"
}

resource "github_actions_variable" "gcp" {
  for_each = {
    (var.github_actions_variable_gcp_project_id)   = var.gcp_project_id
    (var.github_actions_variable_gcp_wif_provider) = module.github-wif.provider_name
  }

  repository    = data.github_repository.current.name
  variable_name = each.key
  value         = each.value
}
