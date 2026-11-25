# Language and accessibility

## Language

English only for now. All UI strings go through one `Strings` object so translation is possible later
without touching pages. Dates/numbers formatted with explicit units (GiB, %, ms).

## Accessibility

- Keyboard navigation for everything; visible focus; shortcuts (`g v` VMs, `/` search) documented in-app.
- Status never by color alone: icon + text (`Running`, `Unresponsive`).
- Contrast AA in light and dark themes; respects reduced motion (no animated charts).
- Charts have a table view and CSV export.
- Compose for Web accessibility semantics are limited; track upstream and test with VoiceOver.
