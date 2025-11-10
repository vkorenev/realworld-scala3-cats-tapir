gcp_project_id = "realworld-conduit"

tf_state_bucket_location = "us-west1"

tf_backend_config_files = {
  state_storage  = "backend.tf"
  github_actions = "../github-actions/backend.tf"
}
