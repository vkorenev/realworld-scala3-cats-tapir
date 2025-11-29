output "database_host" {
  description = "The host of the database"
  value       = neon_project.this.database_host
}

output "database_name" {
  description = "The name of the database"
  value       = neon_project.this.database_name
}

output "database_username" {
  description = "The database username"
  value       = neon_project.this.database_user
}

output "database_password" {
  description = "The database password"
  value       = neon_project.this.database_password
  sensitive   = true
}

output "connection_uri" {
  description = "The full connection URI for the database"
  value       = neon_project.this.connection_uri
  sensitive   = true
}
