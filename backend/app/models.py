from typing import Literal
from pydantic import BaseModel, Field

Movement = Literal[
    "STRAIGHT", "LEFT", "RIGHT", "SLIGHT_LEFT", "SLIGHT_RIGHT",
    "MERGE", "EXIT", "UTURN", "UNKNOWN"
]

class LatLng(BaseModel):
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)

class LaneDto(BaseModel):
    id: str
    road_segment_id: str
    lane_index: int
    centerline: list[LatLng]
    estimated_width_m: float
    allowed_movements: list[Movement]
    change_left: bool
    change_right: bool
    source_confidence: float

class LaneConnectionDto(BaseModel):
    from_lane_id: str
    to_lane_id: str
    movement: Movement
    legal: bool
    confidence: float

class CorridorResponse(BaseModel):
    lanes: list[LaneDto]
    connections: list[LaneConnectionDto]
