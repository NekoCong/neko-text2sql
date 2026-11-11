# Tasks

## 1. Theme foundation

- [x] 1.1 Add the Academic Clarity token block to `neko-text2sql-frontend/src/style.css`: primary/success/warning/danger/info palette and Element Plus light/dark derivations, page/surface/text/border colors, 10px/8px radii, `--ac-shadow-sm/md`, 8pt spacing aliases, and the Chinese-first body + serif display + monospace stacks. Verify `npm run build` succeeds and DevTools shows `--el-color-primary` as `#1E5AA8` and body text at 14px.
- [x] 1.2 Add global base rules in `style.css`: page background, selection color, thin scrollbars, `:focus-visible` ring, `prefers-reduced-motion` handling, and a light-only `color-scheme`. Verify a keyboard Tab across the current pages shows a visible focus ring and the build stays green.
- [x] 1.3 Add shared page primitives (page header, page description, action bar, surface card, table wrapper, empty state, muted text) as global classes with short usage comments. Verify the shell and one data page can adopt them without local color declarations.

## 2. Application shell

- [x] 2.1 Restyle `src/App.vue` header: CSS-only brand mark, product name plus “智慧校园数据问答” descriptor, navigation active treatment, and account menu; keep the existing routes and admin visibility. Verify at 1440px and 1024px that every nav item is reachable, the active item is obvious, and the header does not overflow.
- [x] 2.2 Restyle `src/components/ConversationSidebar.vue`: paper/white surface, prominent new-conversation button, date groups, active row with a 3px primary accent, hover/focus row actions, and the shared empty state. Verify selecting, renaming, deleting, and creating a conversation still work and the list scrolls independently.
- [x] 2.3 Restyle the main canvas and footer in `src/App.vue`: `#F6F7F9` page background, 1200px content max-width with 24px gutters, full-height chat surface, and the user-facing footer copy. Verify chat and data pages align to the same gutters and the footer no longer mentions the technology stack.
- [x] 2.4 Add the narrow-viewport shell behavior: under 768px the conversation sidebar becomes an off-canvas panel toggled by the existing collapse control, with a backdrop and Escape/route-change dismissal. Verify at 375px and 768px that navigation is reachable, the sidebar closes on route change, and the page has no horizontal scroll.

## 3. Login and registration

- [x] 3.1 Restyle `src/views/LoginView.vue` as a two-panel academic card: CSS-only brand panel on the left, form panel on the right, and a compact brand header on narrow screens. Verify at 1440px and 375px that both tabs render, the card does not overflow, and the layout does not jump when validation messages appear.
- [x] 3.2 Apply the shared control, loading, and error treatment to the login/register forms. Verify a failed login shows a readable error, a valid login shows the loading state and navigates, and password confirmation validation still works.

## 4. Chat workspace

- [x] 4.1 Restyle the `ChatView` header, datasource selector, agent switch, and empty state. Verify the selector and switch still change state, the empty state shows the example prompts, and the header wraps cleanly at 1024px.
- [x] 4.2 Restyle the transcript: right-aligned primary-tinted user messages, full-width assistant surface cards with agent identity, teal step dots, readable answer typography, and distinct streaming/error treatments. Verify a live query shows steps, answer, and completion state; a forced error shows the error style without breaking the transcript.
- [x] 4.3 Restyle the SQL block, copy affordance, result table, and chart: ink code surface with monospace text, header-mounted copy button using the existing `copyText` fallback, zebra/sticky tables, and the categorical chart palette. Verify copying works over plain HTTP, a wide result scrolls inside its own region, and the chart uses the new palette.
- [x] 4.4 Restyle the composer and example chips: white rounded input surface, focus ring, clear send/loading state, and outlined example chips. Verify Enter sends, Shift+Enter inserts a newline, and the send control is disabled while streaming.

## 5. Data screens

- [x] 5.1 Restyle `src/views/DataSourcesView.vue` with the shared page header, action bar, surface card, table, tags, and dialog treatment. Verify create, edit, connection test, and delete confirmation still work and the table remains readable at 1024px.
- [x] 5.2 Restyle `src/views/ModelSettingsView.vue` with the same page and form primitives. Verify loading, saving, and model-test interactions still work and validation errors remain readable.
- [x] 5.3 Restyle `src/views/DataSourceAccessView.vue` with the shared header, alert, table, and role-checkbox treatment. Verify granting and revoking a role shows the saved or failed outcome and the matrix stays aligned.

## 6. Remove the About entry point

- [x] 6.1 Remove the About menu item from `src/App.vue`, delete the `/about` route from `src/router/index.ts`, and delete `src/views/AboutView.vue`. Add a `/about` redirect to `/` so old links do not render an empty route. Verify `npm run build`; opening `/about` lands on the chat screen; the primary nav has no About item; `grep -R "AboutView\\|/about" src` returns only the redirect.

## 7. Integration verification

- [x] 7.1 Run `npm test` and `npm run build` from `neko-text2sql-frontend`; both must pass with a clean exit code.
- [x] 7.2 Walk every route at 1440px, 1024px, 768px, and 375px: chat, data sources, model settings, datasource access (admin), and login. Confirm no horizontal page scroll, no clipped controls, and consistent gutters.
- [x] 7.3 Keyboard and accessibility pass: Tab through the header, sidebar, chat composer, dialogs, and tables; confirm visible focus, readable labels, and AA contrast for text and essential controls; enable reduced motion and confirm non-essential animations stop.
- [x] 7.4 Role pass: sign in as a student, a counselor, and an administrator; confirm the admin-only datasource-access item appears only for the administrator, About is absent for every role, and no role sees a broken layout.
- [x] 7.5 Confirm the final diff contains only frontend presentation files, the About removal, and the redirect; no backend, API, dependency, or lockfile changes.

## Workflow follow-up

- Archive this change after the project review requirements are satisfied.
- Verify the archived specs and close the change.
