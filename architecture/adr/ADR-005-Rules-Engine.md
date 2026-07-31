# ADR 005: Rules Engine

## Context
NEXUS needs a way to evaluate streaming IoT data against user-defined business logic dynamically at runtime, without hardcoding conditional statements into the core platform.

## Decision
We built an in-memory Rules Engine separated into two distinct responsibilities:
1. `RuleEvaluator`: Pure, stateless functional evaluation of `DomainEvent`s against strictly-typed `RuleCondition`s (Enums for fields and operators).
2. `RuleMatcher`: Subscribes to the `EventDispatcher`, orchestrates the evaluation of enabled rules, and emits `RuleMatchedEvent`s.

*Crucially, the Rules Engine does NOT execute actions.* It only makes the decision.

## Alternatives Considered
- **Scripting Engines (MVEL / SpEL / Nashorn)**: Allowing users to write JavaScript or SpEL snippets for rules. *Rejected* due to severe security implications (sandbox escapes) and complexity in validation.
- **Executing Actions directly**: Having the Rules Engine send emails or hardware commands. *Rejected* because it tangles decision-making with side-effects, making testing incredibly difficult.

## Consequences
- **Pros**: Extremely fast, highly testable, secure (no arbitrary code execution), and strongly typed. Decoupling decision from execution prepares the ground perfectly for an Action Execution Framework (M8).
- **Cons**: Complex nested logic (OR conditions, groupings) is not currently supported by the simple list of conditions (implicit AND).
