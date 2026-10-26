"""Breaking-change detection between two versions of a schema (adapter-contract.md section 6, ledger P5-024/P5-025).

Allowed in a minor: a new optional property and a relaxed bound. Forbidden (needs a new major): removing or renaming a
property, changing a type, making an optional property required, narrowing a range, length, pattern or enum.
Local ``$ref`` pointers (``#/$defs/...``) are resolved on both sides before comparing.
"""

from __future__ import annotations

_LOWER_BOUNDS = ("minimum", "exclusiveMinimum", "minLength", "minItems")
_UPPER_BOUNDS = ("maximum", "exclusiveMaximum", "maxLength", "maxItems")
_BRANCHES = ("items", "not", "then", "else")


def _types(node: dict) -> set[str] | None:
    value = node.get("type")
    if value is None:
        return None
    return set(value) if isinstance(value, list) else {value}


def _resolve(node: dict, root: dict) -> dict:
    seen = 0
    while isinstance(node, dict) and "$ref" in node and node["$ref"].startswith("#/") and seen < 16:
        target = root
        for part in node["$ref"][2:].split("/"):
            target = target[part]
        node = {**target, **{k: v for k, v in node.items() if k != "$ref"}}
        seen += 1
    return node


def compare(old: dict, new: dict) -> list[str]:
    """Returns the list of breaking changes from ``old`` to ``new`` (empty when ``new`` is a compatible evolution)."""
    return _compare(old, new, old, new, "$")


def _compare(old: dict, new: dict, old_root: dict, new_root: dict, path: str) -> list[str]:
    old, new = _resolve(old, old_root), _resolve(new, new_root)
    problems: list[str] = []
    old_types, new_types = _types(old), _types(new)
    if old_types is not None and new_types is not None and not old_types <= new_types:
        problems.append(f"{path}: type changed or narrowed {sorted(old_types)} -> {sorted(new_types)}")
    if old_types is None and new_types is not None:
        problems.append(f"{path}: type constraint added {sorted(new_types)}")
    for key in _LOWER_BOUNDS:
        if key in new and (key not in old or new[key] > old[key]):
            problems.append(f"{path}: {key} narrowed")
    for key in _UPPER_BOUNDS:
        if key in new and (key not in old or new[key] < old[key]):
            problems.append(f"{path}: {key} narrowed")
    if "pattern" in new and new.get("pattern") != old.get("pattern"):
        problems.append(f"{path}: pattern changed")
    if "enum" in old and "enum" in new and not set(map(repr, old["enum"])) <= set(map(repr, new["enum"])):
        problems.append(f"{path}: enum values removed")
    if "enum" in new and "enum" not in old:
        problems.append(f"{path}: enum added")
    added_required = set(new.get("required", [])) - set(old.get("required", []))
    if added_required:
        problems.append(f"{path}: now required {sorted(added_required)}")
    old_props, new_props = old.get("properties", {}), new.get("properties", {})
    for name, sub in old_props.items():
        if name not in new_props:
            problems.append(f"{path}.{name}: property removed or renamed")
        else:
            problems.extend(_compare(sub, new_props[name], old_root, new_root, f"{path}.{name}"))
    for name in _BRANCHES:
        if isinstance(old.get(name), dict) and isinstance(new.get(name), dict):
            problems.extend(_compare(old[name], new[name], old_root, new_root, f"{path}.{name}"))
    for name in ("anyOf", "oneOf", "allOf"):
        if name in old or name in new:
            old_list, new_list = old.get(name, []), new.get(name, [])
            if len(old_list) != len(new_list):
                problems.append(f"{path}: {name} changed shape")
            else:
                for index, (a, b) in enumerate(zip(old_list, new_list)):
                    problems.extend(_compare(a, b, old_root, new_root, f"{path}.{name}[{index}]"))
    if old.get("additionalProperties") is not False and new.get("additionalProperties") is False:
        problems.append(f"{path}: unknown properties now rejected")
    return problems
