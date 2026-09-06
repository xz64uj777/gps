import argparse
import json
import os
import re
from dataclasses import dataclass
from typing import Any

import httpx

from .db import pool
from .osm_lane_parser import NormalizedLane, normalize_lanes

DEFAULT_OVERPASS_URL = os.getenv(
    "OVERPASS_URL",
    "https://overpass-api.de/api/interpreter",
)
DEFAULT_LANE_WIDTH_M = 3.6


@dataclass(frozen=True)
class DirectionalWay:
    osm_way_id: int
    direction: int
    coordinates: tuple[tuple[float, float], ...]  # (lon, lat), travel order
    lanes: tuple[NormalizedLane, ...]
    source_confidence: float
    speed_limit_kph: int | None


def build_overpass_query(lat: float, lon: float, radius_m: int) -> str:
    radius = max(100, min(int(radius_m), 5000))
    return f"""
[out:json][timeout:25];
(
  way(around:{radius},{lat:.7f},{lon:.7f})["highway"]["lanes"];
  way(around:{radius},{lat:.7f},{lon:.7f})["highway"]["lanes:forward"];
  way(around:{radius},{lat:.7f},{lon:.7f})["highway"]["lanes:backward"];
);
out tags geom;
""".strip()


def fetch_overpass(lat: float, lon: float, radius_m: int, url: str = DEFAULT_OVERPASS_URL) -> dict[str, Any]:
    query = build_overpass_query(lat, lon, radius_m)
    with httpx.Client(timeout=35.0, headers={"User-Agent": "LaneGPS-MVP/0.1"}) as client:
        response = client.post(url, data={"data": query})
        response.raise_for_status()
        return response.json()


def directional_ways_from_overpass(payload: dict[str, Any]) -> list[DirectionalWay]:
    result: list[DirectionalWay] = []
    for element in payload.get("elements", []):
        if element.get("type") != "way" or not element.get("geometry"):
            continue
        tags = {str(k): str(v) for k, v in element.get("tags", {}).items()}
        if "highway" not in tags:
            continue

        coordinates = tuple(
            (float(point["lon"]), float(point["lat"]))
            for point in element["geometry"]
            if "lon" in point and "lat" in point
        )
        if len(coordinates) < 2:
            continue

        way_id = int(element["id"])
        oneway = tags.get("oneway", "").strip().lower()

        if oneway in {"yes", "1", "true"}:
            lanes = tuple(normalize_lanes(tags, forward=True))
            if lanes:
                result.append(_make_directional_way(way_id, 1, coordinates, lanes, tags))
            continue

        if oneway == "-1":
            lanes = tuple(normalize_lanes(tags, forward=False))
            if lanes:
                result.append(_make_directional_way(way_id, -1, tuple(reversed(coordinates)), lanes, tags))
            continue

        # For two-way roads, only import directions whose lane count is explicitly
        # directional. Falling back from `lanes` would split total lanes incorrectly.
        if tags.get("lanes:forward"):
            lanes = tuple(normalize_lanes(tags, forward=True))
            if lanes:
                result.append(_make_directional_way(way_id, 1, coordinates, lanes, tags))
        if tags.get("lanes:backward"):
            lanes = tuple(normalize_lanes(tags, forward=False))
            if lanes:
                result.append(_make_directional_way(way_id, -1, tuple(reversed(coordinates)), lanes, tags))

    return result


def _make_directional_way(
    way_id: int,
    direction: int,
    coordinates: tuple[tuple[float, float], ...],
    lanes: tuple[NormalizedLane, ...],
    tags: dict[str, str],
) -> DirectionalWay:
    explicit_turns = all(lane.confidence >= 0.99 for lane in lanes)
    confidence = 0.95 if explicit_turns else 0.72
    return DirectionalWay(
        osm_way_id=way_id,
        direction=direction,
        coordinates=coordinates,
        lanes=lanes,
        source_confidence=confidence,
        speed_limit_kph=_parse_maxspeed_kph(tags.get("maxspeed")),
    )


def _parse_maxspeed_kph(value: str | None) -> int | None:
    if not value:
        return None
    text = value.strip().lower()
    match = re.match(r"^(\d+(?:\.\d+)?)\s*(mph|km/h|kph)?$", text)
    if not match:
        return None
    speed = float(match.group(1))
    unit = match.group(2)
    if unit == "mph":
        speed *= 1.609344
    return max(1, min(round(speed), 255))


def utm_srid(lat: float, lon: float) -> int:
    zone = max(1, min(60, int((lon + 180.0) // 6.0) + 1))
    return (32600 if lat >= 0 else 32700) + zone


def import_corridor(
    lat: float,
    lon: float,
    radius_m: int = 1200,
    overpass_url: str = DEFAULT_OVERPASS_URL,
    manage_pool: bool = True,
) -> dict[str, int]:
    payload = fetch_overpass(lat, lon, radius_m, overpass_url)
    ways = directional_ways_from_overpass(payload)

    if manage_pool:
        pool.open()
    try:
        return persist_directional_ways(ways)
    finally:
        if manage_pool:
            pool.close()


def persist_directional_ways(ways: list[DirectionalWay]) -> dict[str, int]:
    imported_lanes = 0
    with pool.connection() as conn:
        for way in ways:
            _replace_way(conn, way)
            imported_lanes += len(way.lanes)
        conn.commit()
    return {"ways": len(ways), "lanes": imported_lanes}


def _replace_way(conn, way: DirectionalWay) -> None:
    first_lon, first_lat = way.coordinates[0]
    srid = utm_srid(first_lat, first_lon)
    geojson = json.dumps({"type": "LineString", "coordinates": way.coordinates})

    conn.execute(
        "DELETE FROM road_segment WHERE osm_way_id = %s AND direction = %s",
        (way.osm_way_id, way.direction),
    )
    row = conn.execute(
        """
        INSERT INTO road_segment (
            osm_way_id, geometry, direction, speed_limit_kph,
            lane_count, source_confidence
        )
        VALUES (
            %s,
            ST_SetSRID(ST_GeomFromGeoJSON(%s), 4326),
            %s, %s, %s, %s
        )
        RETURNING id
        """,
        (
            way.osm_way_id,
            geojson,
            way.direction,
            way.speed_limit_kph,
            len(way.lanes),
            way.source_confidence,
        ),
    ).fetchone()
    segment_id = row[0]

    lane_count = len(way.lanes)
    for lane in way.lanes:
        # OSM lane lists are left-to-right in the travel direction. PostGIS
        # positive offsets are left of the line, negative offsets are right.
        offset_m = ((lane_count - 1) / 2.0 - lane.index) * DEFAULT_LANE_WIDTH_M
        conn.execute(
            """
            INSERT INTO lane (
                road_segment_id, lane_index, centerline, estimated_width_m,
                allowed_movements, change_left, change_right, source_confidence
            )
            SELECT
                %s, %s,
                ST_Transform(
                    ST_OffsetCurve(ST_Transform(rs.geometry, %s), %s),
                    4326
                ),
                %s, %s::lane_movement[], %s, %s, %s
            FROM road_segment rs
            WHERE rs.id = %s
            """,
            (
                segment_id,
                lane.index,
                srid,
                offset_m,
                DEFAULT_LANE_WIDTH_M,
                list(lane.turns),
                lane.change_left,
                lane.change_right,
                lane.confidence,
                segment_id,
            ),
        )


def main() -> None:
    parser = argparse.ArgumentParser(description="Import an OSM lane corridor into Lane GPS PostGIS")
    parser.add_argument("--lat", type=float, required=True)
    parser.add_argument("--lon", type=float, required=True)
    parser.add_argument("--radius-m", type=int, default=1200)
    parser.add_argument("--overpass-url", default=DEFAULT_OVERPASS_URL)
    args = parser.parse_args()
    result = import_corridor(args.lat, args.lon, args.radius_m, args.overpass_url)
    print(json.dumps(result))


if __name__ == "__main__":
    main()
