# Templates

Figma: TBD

## Purpose
Create VMs from built-in or custom templates; manage custom templates.

## Data shown
Template cards (name, base, CPUs/memory/disk defaults, description, built-in/custom). Create wizard: parameters, YAML preview, exact command preview.

## Actions
Create VM (operator; VM created stopped, "Start now" offered), save VM as template, edit/delete custom template (admin), import/export.

## States
- **Empty:** only built-ins listed; "Save a VM as a template".
- **Loading:** skeleton.
- **Error:** `limactl create` failed → message and log.
- **Unresponsive:** n/a.

## API
`GET/POST/PUT/DELETE /api/templates`, `POST /api/templates/{id}/create-vm?dryRun=`.

## Implementation
`TemplatesPage` in `web/.../ManagePages.kt`. Create is a wizard: parameters, dry-run preview (command, YAML, warnings), then create stopped as a tracked action. Colima templates are preview-only. Import is a pasted `lima.yaml`; export downloads the template's YAML.

v2: the import dialog has "Validate", which sends the YAML to `POST /api/lima/validate` (`limactl validate`)
and shows Lima's messages before the template is saved.
