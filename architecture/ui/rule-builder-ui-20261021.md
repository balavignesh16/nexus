# Visual rule builder UI correction

The viewport-fixed editor inside AppShell's lower stacking context overlapped navigation and used the legacy
dark theme. It now replaces the Rules content workspace in normal document flow, with a Back to rules control.
Scoped foundation/Tailwind aliases provide a light lavender canvas, white panels and readable controls without
reskinning other pages. The palette contains only supported block types; click-to-add supplements existing drag
and drop. Empty-state guidance, compact toolbar and responsive panel widths replace the unbounded empty editor.

Compiler, persistence, validation, dry-run, lifecycle/approval, backend and hardware behavior were not changed.
Adding blocks only edits the local graph. Unsupported branching/timers/AI blocks remain unsupported, not hidden
functional features. Existing rule graph loading and backend-confirmed save/error semantics are retained.

Focused evidence: RuleCanvas, RulesPage and RuleSimulator tests; production build; live Playwright at1500/1024px
checks header/navigation clearance, toolbar bounds, panel/canvas colors, three click-added nodes, invalid-graph
rejection and back navigation. The live test asserts zero backend mutations. Screenshots/report are in
.nexus/validation/rule-builder-ui; final empty and1024px node screenshots were visually inspected.
No full gate, firmware changes, rule activation, commits, push or tags.
