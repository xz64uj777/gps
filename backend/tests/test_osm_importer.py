import unittest

from app.osm_importer import (
    build_overpass_query,
    directional_ways_from_overpass,
    utm_srid,
)
from app.osm_lane_parser import normalize_lanes


class OsmLaneParserTests(unittest.TestCase):
    def test_change_only_left_and_only_right(self):
        lanes = normalize_lanes(
            {
                "lanes": "2",
                "turn:lanes": "left|through",
                "change:lanes": "only_left|only_right",
            }
        )
        self.assertTrue(lanes[0].change_left)
        self.assertFalse(lanes[0].change_right)
        self.assertFalse(lanes[1].change_left)
        self.assertTrue(lanes[1].change_right)


class OsmImporterTests(unittest.TestCase):
    def test_overpass_query_is_bounded_and_requests_directional_lane_tags(self):
        query = build_overpass_query(41.5, -72.5, 99_999)
        self.assertIn("around:5000", query)
        self.assertIn('"lanes:forward"', query)
        self.assertIn('"lanes:backward"', query)

    def test_oneway_imports_lane_order_and_turns(self):
        payload = {
            "elements": [
                {
                    "type": "way",
                    "id": 123,
                    "tags": {
                        "highway": "primary",
                        "oneway": "yes",
                        "lanes": "3",
                        "turn:lanes": "left|through|right",
                        "change:lanes": "no|yes|no",
                        "maxspeed": "45 mph",
                    },
                    "geometry": [
                        {"lat": 41.0, "lon": -72.0},
                        {"lat": 41.001, "lon": -72.001},
                    ],
                }
            ]
        }
        ways = directional_ways_from_overpass(payload)
        self.assertEqual(1, len(ways))
        way = ways[0]
        self.assertEqual(1, way.direction)
        self.assertEqual(3, len(way.lanes))
        self.assertEqual(("LEFT",), way.lanes[0].turns)
        self.assertEqual(("STRAIGHT",), way.lanes[1].turns)
        self.assertEqual(("RIGHT",), way.lanes[2].turns)
        self.assertEqual(72, way.speed_limit_kph)
        self.assertGreaterEqual(way.source_confidence, 0.9)

    def test_ambiguous_two_way_total_lanes_are_skipped(self):
        payload = {
            "elements": [
                {
                    "type": "way",
                    "id": 5,
                    "tags": {"highway": "secondary", "lanes": "4"},
                    "geometry": [
                        {"lat": 41.0, "lon": -72.0},
                        {"lat": 41.1, "lon": -72.1},
                    ],
                }
            ]
        }
        self.assertEqual([], directional_ways_from_overpass(payload))

    def test_explicit_two_way_directional_lanes_become_two_travel_ways(self):
        payload = {
            "elements": [
                {
                    "type": "way",
                    "id": 6,
                    "tags": {
                        "highway": "trunk",
                        "lanes": "4",
                        "lanes:forward": "2",
                        "lanes:backward": "2",
                        "turn:lanes:forward": "through|right",
                        "turn:lanes:backward": "through|left",
                    },
                    "geometry": [
                        {"lat": 41.0, "lon": -72.0},
                        {"lat": 41.1, "lon": -72.1},
                    ],
                }
            ]
        }
        ways = directional_ways_from_overpass(payload)
        self.assertEqual(2, len(ways))
        self.assertEqual({-1, 1}, {way.direction for way in ways})
        backward = next(way for way in ways if way.direction == -1)
        self.assertEqual((-72.1, 41.1), backward.coordinates[0])

    def test_utm_zone_for_connecticut(self):
        self.assertEqual(32618, utm_srid(41.67, -72.65))


if __name__ == "__main__":
    unittest.main()
