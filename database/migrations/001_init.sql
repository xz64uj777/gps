CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TYPE lane_movement AS ENUM (
  'STRAIGHT', 'LEFT', 'RIGHT', 'SLIGHT_LEFT', 'SLIGHT_RIGHT',
  'MERGE', 'EXIT', 'UTURN', 'UNKNOWN'
);

CREATE TABLE road_segment (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  osm_way_id BIGINT NOT NULL,
  geometry GEOMETRY(LineString, 4326) NOT NULL,
  direction SMALLINT NOT NULL DEFAULT 1 CHECK (direction IN (-1, 1)),
  speed_limit_kph SMALLINT,
  lane_count SMALLINT NOT NULL CHECK (lane_count > 0),
  data_version INTEGER NOT NULL DEFAULT 1,
  source_confidence REAL NOT NULL DEFAULT 1.0 CHECK (source_confidence BETWEEN 0 AND 1)
);
CREATE INDEX idx_road_segment_geometry ON road_segment USING GIST (geometry);
CREATE INDEX idx_road_segment_osm_way_id ON road_segment (osm_way_id);

CREATE TABLE lane (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  road_segment_id UUID NOT NULL REFERENCES road_segment(id) ON DELETE CASCADE,
  lane_index SMALLINT NOT NULL CHECK (lane_index >= 0),
  centerline GEOMETRY(LineString, 4326) NOT NULL,
  estimated_width_m REAL NOT NULL DEFAULT 3.6 CHECK (estimated_width_m > 0),
  lane_type TEXT NOT NULL DEFAULT 'GENERAL',
  allowed_movements lane_movement[] NOT NULL DEFAULT ARRAY['UNKNOWN']::lane_movement[],
  change_left BOOLEAN NOT NULL DEFAULT TRUE,
  change_right BOOLEAN NOT NULL DEFAULT TRUE,
  source_confidence REAL NOT NULL DEFAULT 0.5 CHECK (source_confidence BETWEEN 0 AND 1),
  UNIQUE (road_segment_id, lane_index)
);
CREATE INDEX idx_lane_centerline ON lane USING GIST (centerline);
CREATE INDEX idx_lane_segment ON lane (road_segment_id);

CREATE TABLE junction (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  osm_node_id BIGINT,
  geometry GEOMETRY(Geometry, 4326) NOT NULL,
  complexity_score REAL NOT NULL DEFAULT 0 CHECK (complexity_score BETWEEN 0 AND 1)
);
CREATE INDEX idx_junction_geometry ON junction USING GIST (geometry);

CREATE TABLE lane_connection (
  from_lane_id UUID NOT NULL REFERENCES lane(id) ON DELETE CASCADE,
  to_lane_id UUID NOT NULL REFERENCES lane(id) ON DELETE CASCADE,
  junction_id UUID REFERENCES junction(id) ON DELETE SET NULL,
  movement lane_movement NOT NULL DEFAULT 'UNKNOWN',
  legal BOOLEAN NOT NULL DEFAULT TRUE,
  confidence REAL NOT NULL DEFAULT 0.5 CHECK (confidence BETWEEN 0 AND 1),
  PRIMARY KEY (from_lane_id, to_lane_id)
);
CREATE INDEX idx_lane_connection_to ON lane_connection (to_lane_id);
