terraform {
  required_version = "~> 1.10"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 7.10"
    }

    github = {
      source  = "integrations/github"
      version = "~> 6.7"
    }

    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
  }
}
