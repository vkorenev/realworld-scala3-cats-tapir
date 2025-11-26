variable "neon_project_name" {
  description = "Name of the Neon project"
  type        = string
}

variable "neon_region_id" {
  description = "Neon region ID"
  type        = string
  default     = "aws-us-west-2"
}

variable "pg_version" {
  description = "PostgreSQL version"
  type        = number
  default     = 17
}

variable "history_retention_seconds" {
  description = "The number of seconds to retain the point-in-time restore backup history"
  type        = number
  default     = 21600
}
