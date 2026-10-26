# Navigation usability correction

Replaced the hover-expanding, icon-only AppShell rail with labelled navigation at a stable244px desktop width.
Groups collapse explicitly; the current route's group opens on navigation. A user-controlled72px compact mode
shows group shortcuts, not twenty ambiguous destination icons. Existing destinations/routes remain unchanged.
Settings is pinned in the footer and real service states are available under Connections instead of anonymous
green dots or an obsolete build-number label. Demo realtime state is not counted as a healthy live connection.

Phone navigation uses a menu button, backdrop and labelled drawer with Escape, focus containment/return and
close-on-navigation. No backend API, telemetry, command, lifecycle or hardware behavior was changed.

Verification: AppShell unit tests cover route inventory, active routes, service state, explicit collapse/group
restore and mobile Escape, plus existing overlays/content sizing. Live Playwright exercises1500/1024/390px,
label visibility, group navigation, active route, compact transition, service details and mobile close/Escape.
Screenshots/report: .nexus/validation/navigation-ui. Desktop and mobile screenshots were visually inspected.
Production build and targeted NavigationSidebar ESLint passed. No full gate, commits, push or tags.
