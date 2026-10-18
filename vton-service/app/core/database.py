"""vton-service DB 영속성 및 저장소 레이어 (PostgreSQL vton 스키마 + SQLite 로컬 폴백)."""

from __future__ import annotations

import json
import re
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def _validate_identifier(identifier: str) -> str:
    if not re.match(r"^[a-zA-Z_][a-zA-Z0-9_]*$", identifier):
        raise ValueError(f"유효하지 않은 SQL 식별자입니다: {identifier}")
    return identifier


class VtonRepository:
    def __init__(
        self,
        db_path: str = ":memory:",
        postgres_dsn: str | None = None,
        postgres_schema: str = "vton",
    ) -> None:
        self.db_path = db_path
        self.postgres_dsn = (postgres_dsn or "").strip() or None
        self.postgres_schema = _validate_identifier(postgres_schema or "vton")
        self._use_postgres = self.postgres_dsn is not None and db_path != ":memory:"
        self._lock = threading.RLock()
        self._persistent_conn: sqlite3.Connection | None = None
        if not self._use_postgres and db_path == ":memory:":
            self._persistent_conn = sqlite3.connect(
                ":memory:", check_same_thread=False
            )
            self._persistent_conn.row_factory = sqlite3.Row
        self._init_schema()

    def _get_pg_connection(self) -> Any:
        try:
            import psycopg
            from psycopg.rows import dict_row

            return psycopg.connect(
                self.postgres_dsn,
                row_factory=dict_row,
                options=f"-c search_path={self.postgres_schema}",
            )
        except ImportError:
            import psycopg2
            import psycopg2.extras

            return psycopg2.connect(
                self.postgres_dsn,
                cursor_factory=psycopg2.extras.RealDictCursor,
                options=f"-c search_path={self.postgres_schema}",
            )

    def _get_sqlite_connection(self) -> sqlite3.Connection:
        if self._persistent_conn is not None:
            return self._persistent_conn
        conn = sqlite3.connect(self.db_path, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        return conn

    def _close_sqlite_if_transient(self, conn: sqlite3.Connection) -> None:
        if conn is not self._persistent_conn:
            conn.close()

    def _init_schema(self) -> None:
        if self._use_postgres:
            schema = self.postgres_schema
            with self._lock:
                with self._get_pg_connection() as conn:
                    with conn.cursor() as cur:
                        cur.execute(f"CREATE SCHEMA IF NOT EXISTS {schema};")
                        cur.execute(
                            f"""
                            CREATE TABLE IF NOT EXISTS {schema}.fittings (
                                fitting_id BIGSERIAL PRIMARY KEY,
                                user_id BIGINT NOT NULL,
                                photo_id BIGINT,
                                status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                                result_image_url TEXT,
                                error_message TEXT,
                                created_at TEXT NOT NULL,
                                updated_at TEXT NOT NULL
                            );\n
                            CREATE INDEX IF NOT EXISTS idx_fittings_user_created
                                ON {schema}.fittings (user_id, fitting_id DESC);

                            CREATE TABLE IF NOT EXISTS {schema}.fitting_products (
                                id BIGSERIAL PRIMARY KEY,
                                fitting_id BIGINT NOT NULL
                                    REFERENCES {schema}.fittings(fitting_id) ON DELETE CASCADE,
                                product_id BIGINT NOT NULL,
                                product_name TEXT NOT NULL,
                                price INTEGER NOT NULL DEFAULT 0,
                                image_url TEXT NOT NULL DEFAULT '',
                                category_name VARCHAR(64) NOT NULL DEFAULT ''
                            );
                            CREATE INDEX IF NOT EXISTS idx_fitting_products_fitting
                                ON {schema}.fitting_products (fitting_id);
                            """
                        )
                        conn.commit()
        else:
            with self._lock:
                conn = self._get_sqlite_connection()
                try:
                    cur = conn.cursor()
                    cur.execute(
                        """
                        CREATE TABLE IF NOT EXISTS fittings (
                            fitting_id INTEGER PRIMARY KEY AUTOINCREMENT,
                            user_id INTEGER NOT NULL,
                            photo_id INTEGER,
                            status TEXT NOT NULL DEFAULT 'PENDING',
                            result_image_url TEXT,
                            error_message TEXT,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        );
                        """
                    )
                    cur.execute(
                        """
                        CREATE INDEX IF NOT EXISTS idx_fittings_user_created
                            ON fittings (user_id, fitting_id DESC);
                        """
                    )
                    cur.execute(
                        """
                        CREATE TABLE IF NOT EXISTS fitting_products (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            fitting_id INTEGER NOT NULL,
                            product_id INTEGER NOT NULL,
                            product_name TEXT NOT NULL,
                            price INTEGER NOT NULL DEFAULT 0,
                            image_url TEXT NOT NULL DEFAULT '',
                            category_name TEXT NOT NULL DEFAULT '',
                            FOREIGN KEY (fitting_id) REFERENCES fittings (fitting_id) ON DELETE CASCADE
                        );
                        """
                    )
                    cur.execute(
                        """
                        CREATE INDEX IF NOT EXISTS idx_fitting_products_fitting
                            ON fitting_products (fitting_id);
                        """
                    )
                    conn.commit()
                finally:
                    self._close_sqlite_if_transient(conn)

    def create_fitting(
        self,
        user_id: int,
        photo_id: int | None,
        product_ids: list[int],
    ) -> int:
        now = datetime.now(timezone.utc).isoformat()
        if self._use_postgres:
            schema = self.postgres_schema
            with self._lock:
                with self._get_pg_connection() as conn:
                    with conn.cursor() as cur:
                        cur.execute(
                            f"""
                            INSERT INTO {schema}.fittings (
                                user_id, photo_id, status, created_at, updated_at
                            ) VALUES (%s, %s, 'PENDING', %s, %s)
                            RETURNING fitting_id;
                            """,
                            (user_id, photo_id, now, now),
                        )
                        fitting_id = int(cur.fetchone()["fitting_id"])
                        conn.commit()
                        return fitting_id
        else:
            with self._lock:
                conn = self._get_sqlite_connection()
                try:
                    cur = conn.cursor()
                    cur.execute(
                        """
                        INSERT INTO fittings (
                            user_id, photo_id, status, created_at, updated_at
                        ) VALUES (?, ?, 'PENDING', ?, ?);
                        """,
                        (user_id, photo_id, now, now),
                    )
                    fitting_id = int(cur.lastrowid)
                    conn.commit()
                    return fitting_id
                finally:
                    self._close_sqlite_if_transient(conn)

    def save_fitting_products(
        self,
        fitting_id: int,
        products: list[dict[str, Any]],
    ) -> None:
        if not products:
            return
        if self._use_postgres:
            schema = self.postgres_schema
            with self._lock:
                with self._get_pg_connection() as conn:
                    with conn.cursor() as cur:
                        for p in products:
                            cur.execute(
                                f"""
                                INSERT INTO {schema}.fitting_products (
                                    fitting_id, product_id, product_name, price, image_url, category_name
                                ) VALUES (%s, %s, %s, %s, %s, %s);
                                """,
                                (
                                    fitting_id,
                                    int(p["productId"]),
                                    str(p.get("productName", "")),
                                    int(p.get("price", 0)),
                                    str(p.get("imageUrl", "")),
                                    str(p.get("categoryName", "")),
                                ),
                            )
                        conn.commit()
        else:
            with self._lock:
                conn = self._get_sqlite_connection()
                try:
                    cur = conn.cursor()
                    for p in products:
                        cur.execute(
                            """
                            INSERT INTO fitting_products (
                                fitting_id, product_id, product_name, price, image_url, category_name
                            ) VALUES (?, ?, ?, ?, ?, ?);
                            """,
                            (
                                fitting_id,
                                int(p["productId"]),
                                str(p.get("productName", "")),
                                int(p.get("price", 0)),
                                str(p.get("imageUrl", "")),
                                str(p.get("categoryName", "")),
                            ),
                        )
                    conn.commit()
                finally:
                    self._close_sqlite_if_transient(conn)

    def mark_completed(
        self,
        fitting_id: int,
        result_image_url: str,
    ) -> None:
        now = datetime.now(timezone.utc).isoformat()
        if self._use_postgres:
            schema = self.postgres_schema
            with self._lock:
                with self._get_pg_connection() as conn:
                    with conn.cursor() as cur:
                        cur.execute(
                            f"""
                            UPDATE {schema}.fittings
                            SET status = 'COMPLETED',
                                result_image_url = %s,
                                updated_at = %s
                            WHERE fitting_id = %s;
                            """,
                            (result_image_url, now, fitting_id),
                        )
                        conn.commit()
        else:
            with self._lock:
                conn = self._get_sqlite_connection()
                try:
                    cur = conn.cursor()
                    cur.execute(
                        """
                        UPDATE fittings
                        SET status = 'COMPLETED',
                            result_image_url = ?,
                            updated_at = ?
                        WHERE fitting_id = ?;
                        """,
                        (result_image_url, now, fitting_id),
                    )
                    conn.commit()
                finally:
                    self._close_sqlite_if_transient(conn)

    def mark_failed(
        self,
        fitting_id: int,
        error_message: str,
    ) -> None:
        now = datetime.now(timezone.utc).isoformat()
        if self._use_postgres:
            schema = self.postgres_schema
            with self._lock:
                with self._get_pg_connection() as conn:
                    with conn.cursor() as cur:
                        cur.execute(
                            f"""
                            UPDATE {schema}.fittings
                            SET status = 'FAILED',
                                error_message = %s,
                                updated_at = %s
                            WHERE fitting_id = %s;
                            """,
                            (error_message[:1000], now, fitting_id),
                        )
                        conn.commit()
        else:
            with self._lock:
                conn = self._get_sqlite_connection()
                try:
                    cur = conn.cursor()
                    cur.execute(
                        """
                        UPDATE fittings
                        SET status = 'FAILED',
                            error_message = ?,
                            updated_at = ?
                        WHERE fitting_id = ?;
                        """,
                        (error_message[:1000], now, fitting_id),
                    )
                    conn.commit()
                finally:
                    self._close_sqlite_if_transient(conn)

    def get_fitting_detail(self, fitting_id: int) -> dict[str, Any] | None:
        if self._use_postgres:
            schema = self.postgres_schema
            with self._lock:
                with self._get_pg_connection() as conn:
                    with conn.cursor() as cur:
                        cur.execute(
                            f"""
                            SELECT fitting_id, user_id, photo_id, status,
                                   result_image_url, error_message, created_at, updated_at
                            FROM {schema}.fittings
                            WHERE fitting_id = %s;
                            """,
                            (fitting_id,),
                        )
                        fitting_row = cur.fetchone()
                        if not fitting_row:
                            return None

                        cur.execute(
                            f"""
                            SELECT product_id, product_name, price, image_url, category_name
                            FROM {schema}.fitting_products
                            WHERE fitting_id = %s
                            ORDER BY id ASC;
                            """,
                            (fitting_id,),
                        )
                        product_rows = cur.fetchall() or []
        else:
            with self._lock:
                conn = self._get_sqlite_connection()
                try:
                    cur = conn.cursor()
                    cur.execute(
                        """
                        SELECT fitting_id, user_id, photo_id, status,
                               result_image_url, error_message, created_at, updated_at
                        FROM fittings
                        WHERE fitting_id = ?;
                        """,
                        (fitting_id,),
                    )
                    row = cur.fetchone()
                    if not row:
                        return None
                    fitting_row = dict(row)

                    cur.execute(
                        """
                        SELECT product_id, product_name, price, image_url, category_name
                        FROM fitting_products
                        WHERE fitting_id = ?
                        ORDER BY id ASC;
                        """,
                        (fitting_id,),
                    )
                    product_rows = [dict(r) for r in cur.fetchall()]
                finally:
                    self._close_sqlite_if_transient(conn)

        products = [
            {
                "productId": int(r["product_id"]),
                "productName": r["product_name"],
                "price": int(r["price"]),
                "imageUrl": r["image_url"],
                "categoryName": r["category_name"],
            }
            for r in product_rows
        ]

        return {
            "fittingId": int(fitting_row["fitting_id"]),
            "userId": int(fitting_row["user_id"]),
            "photoId": fitting_row["photo_id"],
            "status": fitting_row["status"],
            "resultImageUrl": fitting_row["result_image_url"],
            "errorMessage": fitting_row["error_message"],
            "products": products,
            "createdAt": fitting_row["created_at"],
            "updatedAt": fitting_row["updated_at"],
        }
