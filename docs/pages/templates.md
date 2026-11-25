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
