import os
from psycopg_pool import ConnectionPool

DATABASE_URL = os.getenv("DATABASE_URL", "postgresql://gps:gps@localhost:5432/gps")
pool = ConnectionPool(conninfo=DATABASE_URL, min_size=1, max_size=8, open=False)
