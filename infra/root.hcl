locals {
  config_file       = read_tfvars_file("${path_relative_from_include()}/config.tfvars")
  config            = jsondecode(local.config_file)
  gcp_project       = local.config.gcp_project
  gcp_location      = local.config.gcp_location
  tf_state_bucket   = local.config.tf_state_bucket
  neon_project_name = local.config.neon_project_name
}

remote_state {
  backend = "gcs"
  generate = {
    path      = "backend.tf"
    if_exists = "overwrite"
  }
  config = {
    project                   = local.gcp_project
    location                  = local.gcp_location
    bucket                    = local.tf_state_bucket
    prefix                    = path_relative_to_include()
    enable_bucket_policy_only = true
  }
}
