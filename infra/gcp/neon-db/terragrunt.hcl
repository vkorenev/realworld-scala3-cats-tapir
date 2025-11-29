include "root" {
  path   = find_in_parent_folders("root.hcl")
  expose = true
}

inputs = {
  neon_project_name = include.root.locals.neon_project_name
}
