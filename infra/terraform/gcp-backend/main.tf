provider "google" {
  project = var.gcp_project_id
}

resource "random_id" "default" {
  byte_length = 8
}

resource "google_storage_bucket" "tf_state" {
  name     = "terraform-state-${random_id.default.hex}"
  location = var.tf_state_bucket_location

  force_destroy               = false
  public_access_prevention    = "enforced"
  uniform_bucket_level_access = true

  versioning {
    enabled = true
  }
}

resource "local_file" "terraform_backend_config" {
  for_each = var.tf_backend_config_files

  file_permission = "0644"
  filename        = each.value

  # You can store the template in a file and use the templatefile function for
  # more modularity, if you prefer, instead of storing the template inline as
  # we do here.
  content = <<-EOT
  terraform {
    backend "gcs" {
      bucket = "${google_storage_bucket.tf_state.name}"
      prefix = "${each.key}"
    }
  }
  EOT
}
