# Lane guidance build marker

Build trigger for the route-aware lane-view implementation added after field testing on 2026-09-17.

The lane view now carries explicit OSM `turn:lanes` data through the lane model and highlights only route-compatible target lanes when the current carriageway mapping is unambiguous.
