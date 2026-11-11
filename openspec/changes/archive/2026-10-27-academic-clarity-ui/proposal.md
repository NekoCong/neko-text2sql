# Proposal

## Why

The authenticated frontend currently ships the stock Element Plus appearance: default blue, default radii and shadows, a developer-facing footer, and almost no visual hierarchy between navigation, conversation steps, SQL, results, and page actions. For a Smart Campus product used by students, counselors, and administrators, it reads as an internal demo rather than a trustworthy campus service. The "关于" tab is no longer needed and exposes the technology stack to end users.

The project already uses Vue 3 and Element Plus; the gap is a deliberate visual language, not a missing framework.

## What Changes

- Adopt an **Academic Clarity** visual language across the frontend: deep campus blue as the primary color, teal for data and insight accents, a warm neutral canvas, ink-colored text, a restrained type scale, an 8pt spacing rhythm, 10–12px radii, and light borders instead of heavy shadows.
- Add a shared design-token layer that overrides Element Plus CSS variables and provides common page-level primitives, so screens do not hand-roll colors, spacing, or focus states.
- Restyle the application shell: brand block, top navigation, user menu, conversation sidebar, content canvas, and footer. Replace the technology-stack footer with user-facing copy that states the product and its data-permission boundary.
- Restyle every authenticated screen and the login/register screen to the same language while preserving the current information architecture and functionality: chat workspace, data sources, model settings, and datasource access.
- Remove the "关于" navigation item, the `/about` route, and `AboutView.vue`.
- Keep the existing Vue 3 + Element Plus stack. No new UI dependency, no backend or API change, and no change to authentication, authorization, or routing behavior other than removing About.
- Preserve accessibility: WCAG AA contrast for text and controls, visible keyboard focus, and reduced-motion support.

## Capabilities

### New Capabilities

- `ui/design-system`: the shared visual contract — color tokens, typography, spacing, radii, elevation, focus treatment, Element Plus theme overrides, and reusable page/surface primitives.
- `ui/screens`: the per-screen presentation contract — app shell and navigation, login/register, chat workspace, data sources, model settings, and datasource access, including removal of the About entry point.

### Modified Capabilities

- None. This is a frontend presentation change; backend and API behavior remain the same.

## Impact

- Frontend only: `neko-text2sql-frontend/src/style.css`, `src/App.vue`, `src/router/index.ts`, `src/views/*.vue`, `src/components/ConversationSidebar.vue`, and `src/components/ResultChart.vue`; delete `src/views/AboutView.vue`.
- No backend, database, API, dependency, or build-pipeline changes.
- Verification is visual plus the existing build and clipboard tests; no new runtime feature flags.
