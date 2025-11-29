include "root" {
  path   = find_in_parent_folders("root.hcl")
  expose = true
}

dependency "db" {
  config_path = "../neon-db"
}

dependency "gcp" {
  config_path = "../gcp-github-actions-setup"
}

inputs = {
  gcp_project                     = include.root.locals.gcp_project
  cloud_run_location              = include.root.locals.gcp_location
  cloud_run_service_account_email = dependency.gcp.outputs.cloud_run_service_account_email
  container_image                 = get_env("CONTAINER_IMAGE")
  database_host                   = dependency.db.outputs.database_host
  database_name                   = dependency.db.outputs.database_name
  database_username               = dependency.db.outputs.database_username
  database_password_secret_name   = dependency.gcp.outputs.database_password_secret_name
  jwt_secret_key_secret_name      = dependency.gcp.outputs.jwt_secret_key_secret_name
}
