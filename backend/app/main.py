from contextlib import asynccontextmanager
from fastapi import FastAPI, Query
from .db import pool
from .models import CorridorResponse, LaneDto, LaneConnectionDto, LatLng

@asynccontextmanager
async def lifespan(_: FastAPI):
    pool.open()
    yield
    pool.close()

app = FastAPI(
    title="Lane-Level GPS API",
    version="0.1.0",
    description="Self-hosted lane graph API for the lane-level GPS MVP.",
    lifespan=lifespan,
)

@app.get("/health")
def health():
    with pool.connection() as conn:
        ok = conn.execute("SELECT 1").fetchone()[0] == 1
    return {"ok": ok}

@app.get("/v1/corridor", response_model=CorridorResponse)
def corridor(
    lat: float = Query(..., ge=-90, le=90),
    lon: float = Query(..., ge=-180, le=180),
    radius_m: int = Query(1200, ge=100, le=5000),
):
    sql = """
      SELECT l.id::text, l.road_segment_id::text, l.lane_index,
             ST_AsGeoJSON(l.centerline)::json, l.estimated_width_m,
             l.allowed_movements::text[], l.change_left, l.change_right,
             l.source_confidence
      FROM lane l
      WHERE ST_DWithin(
        l.centerline::geography,
        ST_SetSRID(ST_MakePoint(%s, %s), 4326)::geography,
        %s
      )
      ORDER BY l.road_segment_id, l.lane_index
      LIMIT 1000
    """
    with pool.connection() as conn:
        rows = conn.execute(sql, (lon, lat, radius_m)).fetchall()
        lane_ids = [r[0] for r in rows]
        crows = []
        if lane_ids:
            crows = conn.execute("""
              SELECT from_lane_id::text, to_lane_id::text,
                     movement::text, legal, confidence
              FROM lane_connection
              WHERE from_lane_id = ANY(%s::uuid[])
            """, (lane_ids,)).fetchall()

    lanes = [
        LaneDto(
            id=r[0], road_segment_id=r[1], lane_index=r[2],
            centerline=[LatLng(lat=c[1], lon=c[0]) for c in r[3]["coordinates"]],
            estimated_width_m=r[4],
            allowed_movements=[str(x) for x in r[5]],
            change_left=r[6], change_right=r[7], source_confidence=r[8],
        )
        for r in rows
    ]
    connections = [
        LaneConnectionDto(
            from_lane_id=r[0], to_lane_id=r[1], movement=r[2],
            legal=r[3], confidence=r[4]
        )
        for r in crows
    ]
    return CorridorResponse(lanes=lanes, connections=connections)
