include "root" {
  path   = find_in_parent_folders("root.hcl")
  expose = true
}

inputs = {
  gcp_project                = include.root.locals.gcp_project
  tf_state_bucket            = include.root.locals.tf_state_bucket
  github_repository          = get_env("GITHUB_REPOSITORY")
  artifact_registry_location = include.root.locals.gcp_location
}
