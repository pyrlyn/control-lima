package controllima.web

/**
 * UI strings in one place so a translation can be added later without touching pages
 * (docs/plan/i18n-accessibility.md). English only for now.
 */
object Strings {
    const val APP = "ControlLima"
    const val LOADING = "Loading…"
    const val RETRY = "Retry"
    const val CANCEL = "Cancel"
    const val SAVE = "Save"
    const val CLOSE = "Close"
    const val DELETE = "Delete"
    const val START = "Start"
    const val STOP = "Stop"
    const val FORCE_STOP = "Force stop"
    const val RESTART = "Restart"
    const val RENAME = "Rename"
    const val CLONE = "Clone"
    const val BACK_UP = "Back up"
    const val LOGS = "Logs"
    const val OPEN = "Open"
    const val COPY = "Copy"
    const val FORWARD = "Forward"
    const val SEARCH = "Search"
    const val PROTECTED = "Protected"
    const val PROTECTED_HINT = "Protected names can never be deleted (Settings > Protected names)."
    const val EXPORT_CSV = "Export CSV"
    const val TABLE_VIEW = "Table"
    const val CHART_VIEW = "Chart"
    const val NEEDS_OPERATOR = "Needs the operator role"
    const val NEEDS_ADMIN = "Needs the admin role"

    const val MISSING_TOKEN_TITLE = "Open ControlLima from its link"
    const val MISSING_TOKEN_TEXT = "This page has no valid access token. Use the URL that control-lima printed when it started " +
        "(it contains ?token=…), or a named token from Settings > Access."
    const val LOGIN_TITLE = "Sign in to ControlLima"
    const val USERNAME = "Username"
    const val PASSWORD = "Password"
    const val SIGN_IN = "Sign in"
    const val SIGN_OUT = "Sign out"
    const val DISCONNECTED = "Live updates disconnected; reconnecting…"
    const val VERSION_MISMATCH = "The server was updated. Reload the page to load the matching UI."
    const val RELOAD = "Reload"
    const val SHORTCUTS = "Shortcuts: g o Overview · g v VMs · g c Containers · g i Images · g p Ports · g m Metrics · g a Alerts · g l Logs · g t Templates · g b Backups · g s Settings · / search"
    const val SHORTCUT_HINT = "g + key to jump · / to search"

    const val NO_LIMA = "No Lima or Colima found"
    const val INSTALL_HINT = "Install them with Homebrew: brew install lima colima. Then start ControlLima again."
    const val NO_VMS = "No VMs yet"
    const val NO_VMS_HINT = "Create one from a template, or run: limactl create --name=dev template://default"
    const val NO_CONTAINERS = "No containers in profile %s"
    const val COLIMA_STOPPED = "Colima profile %s is stopped"
    const val NO_IMAGES = "No images"
    const val NO_PORTS = "No forwarded or listening ports"
    const val NO_ALERTS = "All good, no alerts"
    const val NO_LOG_LINES = "No log lines in this range"
    const val NO_BACKUPS = "No backups yet"
    const val COLLECTING = "Collecting… first points in a few seconds"
    const val VM_NOT_FOUND = "VM not found"
    const val CONTAINER_NOT_FOUND = "Container not found (deleted?)"
    const val UNRESPONSIVE_BANNER = "This VM reports Running but does not answer."
    const val UNRESPONSIVE_DELETE = "The VM is unresponsive: deleting may need a force stop first."
    const val RESTART_REQUIRED = "Restart required; ControlLima never restarts a VM on its own."
    const val TYPE_TO_CONFIRM = "Type %s to confirm"
    const val TOKEN_ONCE = "Copy this token now. It is shown only once."

    fun format(template: String, vararg args: Any?): String {
        var out = template
        args.forEach { out = out.replaceFirst("%s", it.toString()) }
        return out
    }
}
