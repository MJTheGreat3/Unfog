package com.matt.unfog.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class UnfogDb(context: Context) : SQLiteOpenHelper(context, "unfog.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE trips (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER,
                distance_m REAL NOT NULL DEFAULT 0
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE points (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                trip_id INTEGER NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
                t_ms INTEGER NOT NULL,
                lat REAL NOT NULL,
                lng REAL NOT NULL,
                acc REAL NOT NULL,
                speed REAL NOT NULL
            )
            """
        )
        db.execSQL("CREATE INDEX points_trip ON points(trip_id, t_ms)")
        db.execSQL(
            """
            CREATE TABLE photos (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                trip_id INTEGER NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
                uri TEXT NOT NULL,
                taken_ms INTEGER NOT NULL,
                lat REAL,
                lng REAL,
                from_exif INTEGER NOT NULL DEFAULT 0,
                hidden INTEGER NOT NULL DEFAULT 0,
                UNIQUE(trip_id, uri)
            )
            """
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}
