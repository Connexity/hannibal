/*
 * Copyright 2014 YMC. See LICENSE for details.
 */

package models

import _root_.scala.Predef._
import anorm._
import play.api.Play.current
import play.api.db.DB
import play.api.Logger
import models.MetricDef._
import collection.mutable.{ListBuffer, MutableList}
import org.apache.hadoop.hbase.util.Bytes
import utils.ByteUtil
import java.security.MessageDigest
import models.MetricRecord
import play.api.libs.json.{Json, JsValue, Writes}


/**
 * Note:
 * The metrics were originally designed to be able to hold metrics other than region-based metrics, thus
 * the paramater is called MetricDef.target and not regionHash. However currently, there is always regions stored in
 * the metrics, so that currently the following is true:
 * - target is always mapped to the hashed region name
 * - targetDesc is always mapped to the region name
 */
object MetricDef {

  val ALL_REGION_METRICS = Set("storefileSizeMB", "memstoreSizeMB", "storefiles", "compactions")

  val STOREFILE_SIZE_MB = "storefileSizeMB"

  def STOREFILE_SIZE_MB(region: String): MetricDef = findRegionMetricDef(region, STOREFILE_SIZE_MB)

  val MEMSTORE_SIZE_MB = "memstoreSizeMB"

  def MEMSTORE_SIZE_MB(region: String): MetricDef = findRegionMetricDef(region, MEMSTORE_SIZE_MB)

  val STOREFILES = "storefiles"

  def STOREFILES(region: String): MetricDef = findRegionMetricDef(region, STOREFILES)

  val COMPACTIONS = "compactions"
  def COMPACTIONS(region: String) : MetricDef = findRegionMetricDef(region, COMPACTIONS)

  val READ_RATE = "readRate"
  def READ_RATE(region: String) : MetricDef = findRegionMetricDef(region, READ_RATE)

  val WRITE_RATE = "writeRate"
  def WRITE_RATE(region: String) : MetricDef = findRegionMetricDef(region, WRITE_RATE)

  def findRegionMetricDef(region: String, name: String) = find(RegionHash.byName(region).hash, name, region)

  def find(target: String, name: String, targetDesc:String) = {
    DB.withConnection {
      implicit c =>
        val stream = SQL_FIND_METRIC.on("name" -> name, "target" -> target)()

        if (stream.isEmpty) {
          Logger.info("creating new metric for " + target + " : " + name)
          val id = SQL_INSERT_METRIC.on("target" -> target, "name" -> name).executeInsert()
          MetricDef(id.get, target, name, 0.0, 0, targetDesc)
        } else {
          val row = stream.head

          MetricDef(
            row[Long]("id"),
            target,
            row[String]("name"),
            row[Double]("last_value"),
            row[Long]("last_update"),
            targetDesc // this is currently always the region name
          )
        }
    }
  }

  def findByName(name: String): Seq[MetricDef] = {
    DB.withConnection {
      implicit c =>
        val stream = SQL_FIND_METRIC_ALL.on("name" -> name)()

        if (stream.isEmpty) {
          Logger.info("no metrics found for : " + name)
          List()
        } else {
          stream.map(row => {
            val regionHash = RegionHash.byHash(row[String]("target"))
            // NOTE: actually, using the RegionHash here is against the intended design. Metrics itself were not
            // designed specific for regions. However we currently don't use the metrics for anything else,
            // so it's OK for now to resolve the targetDesc by using the RegionHash.
            MetricDef(
              row[Long]("id"),
              regionHash.hash,
              row[String]("name"),
              row[Double]("last_value"),
              row[Long]("last_update"),
              regionHash.name
            )
          }).toList
        }
    }
  }

  def clean(until: Long = now() - 1000 * 3600 * 24 * 7) = {
    var recordsCleaned = 0;
    var metricsCleaned = 0;
    DB.withConnection {
      implicit c =>
        recordsCleaned = SQL_DELETE_RECORDS.on("until" -> until).executeUpdate()
        metricsCleaned = SQL_DELETE_METRICS.on("until" -> until).executeUpdate()
    }
    Tuple2(metricsCleaned, recordsCleaned);
  }

  def now() = new java.util.Date().getTime()

  val SQL_FIND_METRIC_ALL = SQL( """
    SELECT
      id, target, name, last_value, last_update
    FROM
      metric
    WHERE
      name={name}
                                 """)

  val SQL_FIND_METRIC = SQL( """
    SELECT
      id, target, name, last_value, last_update
    FROM
      metric
    WHERE
      name={name} AND target={target}
                             """)

  val SQL_INSERT_METRIC = SQL( """
    INSERT INTO
      metric(target, name, last_value, last_update)
    VALUES
      ({target}, {name}, 0.0, 0)
                               """)

  val SQL_UPDATE_METRIC = SQL("UPDATE metric SET last_value={last_value}, last_update={last_update} WHERE id={id}")

  val SQL_INSERT_RECORD = SQL( """
    INSERT INTO
      record(metric_id, timestamp, prev_value, value)
    VALUES
      ({metric_id}, {timestamp}, {prev_value}, {value})
                               """)

  val SQL_FIND_RECORDS = SQL( """
    SELECT
      timestamp, prev_value, value
    FROM
      record
    WHERE
      metric_id = {metric_id} AND timestamp > {since} AND timestamp <= {until}
    ORDER BY
      timestamp
                              """)

  val SQL_DELETE_RECORDS = SQL( """
    DELETE FROM
      record
    WHERE
      timestamp < {until}
                                """)

  val SQL_DELETE_METRICS = SQL( """
    DELETE FROM
      metric
    WHERE
      last_update < {until}
                                """)

  implicit val metricDefWrites = new Writes[MetricDef] {
    override def writes(o: MetricDef): JsValue = Json.obj(
      "id" -> o.id,
      "target" -> o.target,
      "name" -> o.name,
      "lastValue" -> o.lastValue,
      "lastUpdate" -> o.lastUpdate,
      "targetDesc" -> o.targetDesc
    )
  }
}

case class MetricDef(id: Long, target: String, name: String, var lastValue: Double, var lastUpdate: Long, var targetDesc: String) {
  def update(value: Double, timestamp: Long = now) = {
    var updated = false
    DB.withConnection {
      implicit c =>
        if (lastValue != value) {
          SQL_INSERT_RECORD.on("metric_id" -> id, "timestamp" -> timestamp, "prev_value" -> lastValue, "value" -> value).executeInsert()
          lastValue = value
          updated = true
        }
        if (lastUpdate < timestamp) {
          lastUpdate = timestamp
          SQL_UPDATE_METRIC.on("id" -> id, "last_update" -> lastUpdate, "last_value" -> lastValue).executeUpdate()
        }
    }
    updated
  }

  def metric(since: Long, until: Long): Metric = {
    var values: List[MetricRecord] = null;
    var prevValue: Option[Double] = None;
    DB.withConnection {
      implicit c =>
        values = SQL_FIND_RECORDS.on("metric_id" -> id, "since" -> since, "until" -> until)().map(row => {
          if (prevValue == None) {
            prevValue = Some(row[Double]("prev_value"))
          }
          MetricRecord(row[Long]("timestamp"), row[Double]("value"));
        }).toList
    }
    if (values.size < 1)
      Metric(name, target, since, until, values, lastValue, lastUpdate == 0, targetDesc)
    else
      Metric(name, target, since, until, values, prevValue.get, lastUpdate == 0, targetDesc)
  }
}

object Metric {
  implicit val metricRecordWrites = new Writes[MetricRecord] {
    override def writes(o: MetricRecord): JsValue = Json.obj(
      "ts" -> o.ts,
      "v" -> o.v
    )
  }

  implicit val metricWrites = new Writes[Metric] {
    override def writes(o: Metric): JsValue = Json.obj(
      "name" -> o.name,
      "target" -> o.target,
      "begin" -> o.begin,
      "end" -> o.end,
      "values" -> Json.toJson(o.values),
      "prevValue" -> o.prevValue,
      "isEmpty" -> o.isEmpty,
      "targetDesc" -> o.targetDesc
    )
  }
}

case class Metric(name: String, target: String, begin: Long, end: Long, values: Seq[MetricRecord], prevValue: Double, isEmpty: Boolean, targetDesc: String)

case class MetricRecord(ts: Long, v: Double)



