from dataclasses import dataclass


@dataclass(frozen=True)
class NormalizedLane:
    index: int
    turns: tuple[str, ...]
    change_left: bool
    change_right: bool
    confidence: float


def _split(value):
    return [] if not value else value.split("|")


def _turns(value):
    if not value:
        return ("UNKNOWN",)
    mapping = {
        "through": "STRAIGHT",
        "left": "LEFT",
        "slight_left": "SLIGHT_LEFT",
        "right": "RIGHT",
        "slight_right": "SLIGHT_RIGHT",
        "reverse": "UTURN",
        "merge_to_left": "MERGE",
        "merge_to_right": "MERGE",
        "none": "UNKNOWN",
    }
    return tuple(mapping.get(v.strip(), "UNKNOWN") for v in value.split(";"))


def _change_permissions(value: str) -> tuple[bool, bool]:
    value = value.strip().lower()
    if value == "no":
        return False, False
    if value in {"not_left", "only_right"}:
        return False, True
    if value in {"not_right", "only_left"}:
        return True, False
    return True, True


def normalize_lanes(tags: dict[str, str], forward: bool = True):
    suffix = "forward" if forward else "backward"
    raw_count = tags.get(f"lanes:{suffix}") or tags.get("lanes")
    if not raw_count:
        return []
    try:
        count = int(raw_count)
    except ValueError:
        return []

    turns = _split(tags.get(f"turn:lanes:{suffix}") or tags.get("turn:lanes"))
    changes = _split(tags.get(f"change:lanes:{suffix}") or tags.get("change:lanes"))
    explicit = len(turns) == count

    result = []
    for i in range(count):
        change = changes[i] if i < len(changes) else "yes"
        change_left, change_right = _change_permissions(change)
        result.append(
            NormalizedLane(
                index=i,
                turns=_turns(turns[i] if i < len(turns) else ""),
                change_left=change_left,
                change_right=change_right,
                confidence=1.0 if explicit else 0.65,
            )
        )
    return result
