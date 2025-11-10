terraform {
  backend "gcs" {
    bucket = "terraform-state-04485fa79cd3ce9b"
    prefix = "github_actions"
  }
}
